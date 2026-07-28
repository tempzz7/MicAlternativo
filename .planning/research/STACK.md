# Stack Research

**Domain:** Android utility app (audio recorder, mic-source workaround) — dependency-free, no-Gradle native toolchain build
**Researched:** 2026-07-28
**Confidence:** HIGH (build pipeline, signing, permissions — verified against developer.android.com / source.android.com) / MEDIUM (Samsung-specific mic routing behavior — no official doc, inferred from community + AOSP audio policy docs)

## Recommended Stack

### Core Technologies

| Technology | Version | Purpose | Why Recommended |
|------------|---------|---------|-----------------|
| Android SDK Platform | android-34 (API 34, Android 14) | Compile/target SDK | Already installed; matches targetSdk 34 requirement; required to compile against current `android.jar` for API surface (foreground service types, etc.) |
| Android SDK Build-Tools | 34.0.0 | aapt2, d8, apksigner, zipalign | Already installed; this is the last build-tools line that ships `d8`/`apksigner`/`zipalign` as documented, matched to platform 34. Legacy `aapt` (non-2) may or may not be present in 34.0.0 — **do not depend on it**; use `aapt2` exclusively (see below). |
| OpenJDK | 21 (LTS) | Compiles Java sources with `javac`, runs `d8`/`apksigner` (both are JVM tools) | Already installed. Android's toolchain (`d8`, `apksigner`) ships as jars invoked via `java`/wrapper scripts, so any modern JDK works as host JVM. Target Java language level for `javac` should still be capped (`--release 11` or `-source/-target 1.8`) — see Pitfalls. |
| aapt2 | bundled in build-tools 34.0.0 | Compile + link resources, generate `resources.arsc`, `R.java`, merge manifest | The only supported resource compiler going forward; `aapt` (v1) is deprecated by Google. Two-stage: `aapt2 compile` (per-file → `.flat`) then `aapt2 link` (→ base APK skeleton + `R.java`). |
| d8 | bundled in build-tools 34.0.0 | Convert `.class` → `classes.dex` | Official DEX compiler since Android Studio 3.1; replaced `dx`. Supports Java 7/8 language constructs (try-with-resources, lambdas need desugaring support — see Pitfalls). |
| apksigner | bundled in build-tools 34.0.0 | Sign final APK (v1/v2/v3) | Mandatory — unsigned APKs cannot install/run on any device, debug included. Auto-selects required signature schemes from `--min-sdk-version`. |
| zipalign | bundled in build-tools 34.0.0 | 4-byte-align uncompressed zip entries before signing | Required for v2/v3 signing to work correctly and for runtime mmap performance; **must run before** `apksigner sign`, never after. |
| keytool | bundled with OpenJDK 21 | Generate the release keystore/keypair used by `apksigner` | Standard JDK tool; no separate install needed. |

### Supporting Libraries

None. This project deliberately has **zero external libraries/dependencies** — no AndroidX, no support-library, no third-party jars. Everything needed (recording, permissions, MediaStore, sharing) is available in the platform SDK (`android.jar`) at API 29+.

| API (platform, not a library) | Package | Purpose | When to Use |
|---|---|---|---|
| `android.media.MediaRecorder` | `android.media` | Record microphone audio directly to an encoded file | Always — this is the correct high-level API for this app (see rationale below) |
| `android.media.MediaRecorder.AudioSource` | `android.media` | Select which physical/logical mic path to capture from | Always — the core mechanism for the mic-source-selection feature |
| `android.provider.MediaStore.Audio.Media` | `android.provider` | Insert recordings into shared storage without filesystem permissions | Always — required for scoped storage on API 29+ and for the file to show up in Files/music apps |
| `android.content.Intent` (`ACTION_SEND`) | `android.content` | Share the recorded file's `content://` MediaStore URI to WhatsApp | Always — for the "share to WhatsApp" requirement |
| `androidx.core.app.ActivityCompat`-equivalent | N/A — use `Activity.requestPermissions()` / `Activity.onRequestPermissionsResult()` directly | Runtime permission request for `RECORD_AUDIO` | Always — since AndroidX is excluded, use the plain `android.app.Activity` runtime-permission methods (available since API 23), not the AndroidX `ActivityResultContracts` wrapper |

### Development Tools

| Tool | Purpose | Notes |
|------|---------|-------|
| `aapt2` (compile + link) | Resource compilation and manifest processing | Run `aapt2 compile <dir> -o compiled/` (or per-file) then `aapt2 link` in a **separate invocation** — compile and link are always two distinct steps, unlike legacy `aapt`. |
| `javac` (JDK 21) | Compile Java sources + generated `R.java` against `android.jar` | Must pass `-classpath android.jar` (as the **only** classpath — do not put JDK's own `rt.jar`-equivalent ahead of it) and cap language level, e.g. `--release 11` or `-source 1.8 -target 1.8`, so `d8` can dex the output without needing desugaring for higher constructs you don't use. |
| `d8` | Dex compilation | Point `--lib` at the same `android.jar` used by `javac`; pass `--min-api 29`. |
| `zip` / `jar` (or a small custom zipper) | Insert `classes.dex` into the aapt2-linked APK skeleton | aapt2 link output has manifest + resources.arsc but **no dex**; you must add `classes.dex` into the archive yourself (standard `zip` CLI, stored or deflated — apksigner/zipalign don't care which as long as it's valid zip). |
| `zipalign` | Align zip entries before signing | `zipalign -f -p 4 unsigned.apk aligned.apk` |
| `apksigner sign` | Final signing step | `apksigner sign --ks keystore.jks --ks-key-alias <alias> --ks-pass pass:<pw> --out final.apk aligned.apk` |
| `keytool -genkeypair` | One-time keystore creation, reused for every release | `-keyalg RSA -keysize 2048 -validity 10000` (~27 years — pick something well past any realistic app lifetime; Google Play itself now requires validity past Oct 2033, a good floor even for sideload-only distribution) |
| Shell script (bash) wrapping all of the above | Reproducible one-command build | Recommended: a single `build.sh` in the repo that runs compile → link → javac → d8 → zip-add-dex → zipalign → sign, failing fast on any non-zero exit code. This is what "reproducible build" means here — there is no Gradle to encode the pipeline, so the script *is* the build system. |

## Installation

No package manager installs are needed — everything is already present in the environment per PROJECT.md:

```bash
# Already installed (per project context) — verify paths:
ANDROID_HOME=<path-to-scratchpad>/android-sdk
PLATFORM="$ANDROID_HOME/platforms/android-34"
BUILD_TOOLS="$ANDROID_HOME/build-tools/34.0.0"
JAVA_HOME=<openjdk-21-path>

# One-time: generate the project's signing keystore (commit to a secrets-safe location, NOT to git)
keytool -genkeypair -v -keystore release.keystore -alias micalternativo \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=MicAlternativo, OU=Dev, O=MicAlternativo, C=BR"
```

No `npm install` / `pip install` / Gradle dependency block applies — this is intentional per the project's no-dependency constraint.

## Alternatives Considered

| Recommended | Alternative | When to Use Alternative |
|-------------|-------------|-------------------------|
| `MediaRecorder` | `AudioRecord` (raw PCM) | Only if the app needed real-time waveform analysis, custom noise-suppression, or a raw/lossless format (WAV) not producible by `MediaRecorder`'s encoders. Not needed here — this app just records-to-file and plays back/shares. |
| MPEG_4 container + AAC encoder | THREE_GPP container + AMR_NB encoder | AMR_NB only makes sense for extreme bandwidth-constrained legacy telephony use cases (8kHz narrowband). For a WhatsApp-shared voice recording, AAC/MPEG_4 gives clearly better quality at a still-small file size and is what WhatsApp/most modern recorders use. |
| No-Gradle shell-script build | Gradle + AGP | Only if the project later needs multi-module builds, dependency resolution, or Jetpack libraries — explicitly out of scope per PROJECT.md; the manual pipeline is well-documented and stable for a single-module, dependency-free app. |
| Plain `Activity.requestPermissions()` | AndroidX `ActivityResultContracts.RequestPermission` | AndroidX contract API is nicer (composable, no `onRequestPermissionsResult` boilerplate) but requires `androidx.activity` — excluded by the no-AndroidX constraint. The platform API (`requestPermissions`/`onRequestPermissionsResult`, API 23+) does the same job with more boilerplate. |
| Foreground-only recording (no service) | Foreground Service with `FOREGROUND_SERVICE_MICROPHONE` type | Only needed if recording must continue while the user backgrounds the app (e.g., switches to WhatsApp mid-recording). If in scope later, Android 14 mandates declaring `android:foregroundServiceType="microphone"` in the manifest, requesting both `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MICROPHONE` permissions, and holding `RECORD_AUDIO` at start time or the OS throws `SecurityException`. |

## What NOT to Use

| Avoid | Why | Use Instead |
|-------|-----|-------------|
| Gradle / Android Studio project structure | Explicitly excluded per PROJECT.md; adds a huge dependency surface (AGP, Kotlin/Groovy DSL, Gradle daemon) that contradicts the "lightweight, reproducible via script" goal | The aapt2 → javac → d8 → zip → zipalign → apksigner shell pipeline documented above |
| AndroidX (any `androidx.*` artifact, including `androidx.core`, `androidx.activity`, `androidx.appcompat`) | Requires dependency resolution (Maven/Gradle) to fetch `.aar` files and merge their manifests/resources — impossible to do cleanly with plain `aapt2`+`javac` without reimplementing a dependency+AAR-extraction pipeline | Plain `android.app.Activity`, `android.widget.*` views built programmatically, platform permission APIs |
| Legacy `aapt` (v1) | Deprecated by Google in favor of `aapt2`; may be absent entirely from build-tools 34.0.0, and lacks the two-stage compile/link model that produces `.flat` intermediates and proper proto-based linking | `aapt2 compile` + `aapt2 link` |
| `AudioSource.DEFAULT`/`MIC` as the *only* option | On the reference device (Galaxy A15 5G, defective primary/bottom mic), `MIC`/`DEFAULT` are typically routed to the primary mic and will capture silence/garbage — this is the exact bug the app exists to work around | Expose `AudioSource.CAMCORDER` as the default, plus a UI to let the user try `MIC`, `VOICE_RECOGNITION`, `VOICE_COMMUNICATION`, `UNPROCESSED` and pick whichever actually captures sound on their specific device (mic routing is OEM/HAL-dependent and not guaranteed identical across Samsung models) |
| `AudioSource.UNPROCESSED` without a support check | Not guaranteed available on all devices/OEM audio HALs; using it unconditionally can throw at `prepare()`/`start()` time | Query `AudioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)` before offering it in the source-picker UI, falling back gracefully |
| Direct `File`/`MediaStore.Files` raw filesystem writes to shared storage | Blocked or restricted by Scoped Storage starting Android 10 (API 29) — writing outside app-private storage without `MediaStore` either fails or requires legacy-storage opt-outs that don't work on API 29+ targeting 34 | `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` insert + `IS_PENDING` pattern (no extra permission needed for the app's own inserts) |
| `FileProvider` + `content_paths.xml` | Needs an XML resource + provider `<meta-data>` manifest wiring — extra moving parts for no benefit here | The `content://` URI that `MediaStore.insert()` already returns is directly shareable via `ACTION_SEND` with `EXTRA_STREAM` and `FLAG_GRANT_READ_URI_PERMISSION` — zero extra config |
| Signing with only v1 (JAR signing) | v1-only signing is effectively obsolete for a minSdk 29 target; offers weaker integrity guarantees (doesn't cover zip's central directory) and Android 11+ treats v1-only more strictly | Let `apksigner` auto-select v1(optional)/v2/v3 based on `--min-sdk-version 29` (it will produce v2+v3, dropping v1 unless you force it) — the tool's default behavior is correct, don't override signing-scheme flags manually |
| Running `zipalign` after `apksigner sign` | Any modification to a signed APK's zip structure after signing invalidates the v2/v3 signature (which covers the exact byte layout) | Always: compile → link → dex → zip-add-dex → **zipalign → sign**, in that order, never reversed |

## Stack Patterns by Variant

**If recording must survive the app going to background (not currently a stated requirement):**
- Add a `Service` declared with `android:foregroundServiceType="microphone"`, request `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` in the manifest, and start it with `startForeground()` holding an active `RECORD_AUDIO` grant, on Android 14+ (API 34) — otherwise the OS throws `SecurityException` at foreground-service start.
- Because: Android 9+ blocks microphone access from background-only apps entirely; Android 14 additionally enforces declared-and-matching foreground-service types.

**If the user later wants a device compatibility list / "which source works on which phone":**
- Persist the AudioSource the user found working (e.g., in `SharedPreferences`, part of the platform SDK — no dependency needed) per install, and optionally let them re-test all 5 sources (MIC, CAMCORDER, VOICE_RECOGNITION, VOICE_COMMUNICATION/UNPROCESSED, DEFAULT) from a settings screen.
- Because: mic-to-AudioSource mapping is OEM/HAL-specific (confirmed no public Samsung API exposes this mapping directly) — the app's actual value is empirical trial across sources, not a hardcoded assumption.

**If distribution scope grows beyond sideload (e.g., F-Droid later):**
- Reproducible/deterministic builds become important (fixed file timestamps in the zip, deterministic `.flat` ordering). Not needed for GitHub Releases + sideload today, but worth flagging as a future constraint on the build script's design (e.g., sort input file lists, use `zip -X` to strip extra metadata) if reproducibility becomes a hard requirement later.

## Version Compatibility

| Component | Compatible With | Notes |
|-----------|-----------------|-------|
| `android-34` platform `android.jar` | build-tools 34.0.0, targetSdk 34 | Keep platform and build-tools versions matched (both "34"); mismatches between `aapt2`'s expected resource format and the platform SDK version have historically caused link errors. |
| `d8` (build-tools 34.0.0) | `javac --release 11` output (or `-source/-target 1.8`+) | `d8` dexes standard `.class` bytecode; keep `javac` target at Java 8 language level unless you've confirmed d8's desugaring handles anything newer (safest: stay at 8, since no AndroidX/library needs newer syntax). |
| `apksigner` (build-tools 34.0.0) | minSdk 29 | Auto-applies v2 (API24+) and v3 (API28+) since 29 > both thresholds; v1 not required. Do not manually disable v2/v3 — that's what actually satisfies Android 14's stricter signing verification. |
| `MediaStore.Audio.Media` scoped-storage insert flow | minSdk 29 (Android 10) exactly matches PROJECT.md's chosen minSdk | This is precisely why minSdk 29 was chosen — API 29 is the first version where MediaStore inserts work without `WRITE_EXTERNAL_STORAGE` at all; going lower (e.g., minSdk 21) would require a legacy-storage code path, adding real complexity for no benefit given the target device is Android 14. |
| `FOREGROUND_SERVICE_MICROPHONE` | API 34 only (declared but harmless as a no-op concept below 34 since foreground service *type* enforcement is 34+) | Not currently needed per PROJECT.md (recording is a foreground/in-app action), but flagged above under Stack Patterns in case scope grows. |

## Sources

- [aapt2 | Android Studio | Android Developers](https://developer.android.com/tools/aapt2) — official compile/link stage description — HIGH confidence
- [d8 | Android Studio | Android Developers](https://developer.android.com/tools/d8) — official dexer description — HIGH confidence
- [apksigner | Android Studio | Android Developers](https://developer.android.com/tools/apksigner) — fetched directly; signing scheme table, minSdkVersion-driven scheme selection, zipalign-before-sign requirement — HIGH confidence
- [zipalign | Android Studio | Android Developers](https://developer.android.com/tools/zipalign) — alignment purpose and ordering — HIGH confidence
- [MediaRecorder overview | Android media | Android Developers](https://developer.android.com/media/platform/mediarecorder) — fetched directly; MediaRecorder usage, AudioSource options, background-recording restriction since API 28, permission flow — HIGH confidence
- [MediaRecorder.AudioSource | API reference | Android Developers](https://developer.android.com/reference/android/media/MediaRecorder.AudioSource) — AudioSource constant semantics (CAMCORDER/MIC/VOICE_RECOGNITION/VOICE_COMMUNICATION) — HIGH confidence
- [Configure preprocessing effects | Android Open Source Project](https://source.android.com/docs/core/audio/implement-pre-processing) — confirms VOICE_RECOGNITION disables AGC/NS, CAMCORDER applies AGC — HIGH confidence
- [Foreground service types are required | Android Developers](https://developer.android.com/about/versions/14/changes/fgs-types-required) — Android 14 FOREGROUND_SERVICE_MICROPHONE requirement — HIGH confidence
- [Declare foreground services and request permissions | Android Developers](https://developer.android.com/develop/background-work/services/fgs/declare) — HIGH confidence
- [Access media files from shared storage | Android Developers](https://developer.android.com/training/data-storage/shared/media) — MediaStore insert pattern, RELATIVE_PATH/IS_PENDING — HIGH confidence
- [Request runtime permissions | Privacy | Android Developers](https://developer.android.com/training/permissions/requesting) — runtime permission flow, shouldShowRequestPermissionRationale — HIGH confidence
- [Building an Android App from the Command Line — hanshq.net](https://www.hanshq.net/command-line-android.html) — complete, widely-cited manual build pipeline (aapt→javac→d8→zip→zipalign→apksigner); used here for pipeline shape though its example predates aapt2's two-stage model — MEDIUM confidence (community, cross-checked against official docs above)
- [SDK Build Tools release notes | Android Developers](https://developer.android.com/tools/releases/build-tools) — build-tools versioning context — MEDIUM confidence (could not confirm exact `aapt` v1 presence/absence in 34.0.0 from search; treat aapt2-only as the safe assumption)
- WebSearch aggregate findings (Samsung Pro Video mic picker, general community build-pipeline writeups, AMR vs AAC quality comparison) — MEDIUM/LOW confidence, used only for contextual/non-critical claims (e.g., "no public Samsung API for CAMCORDER mic routing" is inferred, not documented by Samsung) — flagged explicitly in the tables above wherever it applies

---
*Stack research for: Android audio recorder utility (no-Gradle, no-AndroidX, sideload distribution)*
*Researched: 2026-07-28*
