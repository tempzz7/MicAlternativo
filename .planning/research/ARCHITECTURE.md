# Architecture Research

**Domain:** Minimal single-Activity Android audio recorder (Java, no Gradle, no AndroidX)
**Researched:** 2026-07-28
**Confidence:** HIGH (official Android docs + confirmed community build recipes; MEDIUM on some build-script edge cases where sources diverge on exact flag ordering)

## Standard Architecture

### System Overview

This is a single-process, single-Activity Android app. There is no client/server split — the "architecture" here is really two separate systems that must both be designed:

1. **Runtime architecture** — how the app's components collaborate inside the Android process.
2. **Build architecture** — the scripted toolchain that turns source into a signed APK, and the distribution path that gets the APK onto the phone.

```
┌──────────────────────────────────────────────────────────────────┐
│                         RUNTIME (on-device)                       │
├──────────────────────────────────────────────────────────────────┤
│  ┌────────────────────────────────────────────────────────────┐  │
│  │                    MainActivity (UI layer)                  │  │
│  │  programmatic views: source spinner, record/stop button,    │  │
│  │  play button, share button, status text                     │  │
│  └───────┬───────────────┬───────────────┬──────────────┬──────┘  │
│          │               │               │              │         │
│  ┌───────▼──────┐ ┌──────▼───────┐ ┌─────▼──────┐ ┌─────▼──────┐ │
│  │ Permission    │ │ RecordingEngine│ │ Playback  │ │ ShareHelper│ │
│  │ Gate          │ │ (MediaRecorder │ │ (MediaPlayer│ │ (Intent   │ │
│  │ (RECORD_AUDIO)│ │  wrapper)      │ │  wrapper)   │ │  ACTION_SEND)│
│  └───────────────┘ └───────┬────────┘ └──────┬──────┘ └─────┬──────┘ │
│                             │                  │              │      │
├─────────────────────────────┴──────────────────┴──────────────┴──────┤
│                    MediaStoreRepository (data-access layer)          │
│         insert() → Uri, openFileDescriptor(), IS_PENDING flip        │
├────────────────────────────────────────────────────────────────────┤
│                    Android system: MediaStore (Audio collection)     │
│              content://media/external/audio/media/<id>               │
└──────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│                         BUILD (scripted, no Gradle)                │
├──────────────────────────────────────────────────────────────────┤
│  res/ + AndroidManifest.xml                                        │
│        │  aapt2 compile → aapt2 link                               │
│        ▼                                                            │
│  gen/R.java + compiled resources (.flat / linked APK skeleton)      │
│        │  javac (-classpath android.jar)                            │
│        ▼                                                            │
│  .class files                                                       │
│        │  d8 (--lib android.jar)                                    │
│        ▼                                                            │
│  classes.dex                                                        │
│        │  package into APK (aapt2 link -o or manual zip add)        │
│        ▼                                                            │
│  unsigned.apk                                                       │
│        │  zipalign                                                  │
│        ▼                                                            │
│  aligned.apk                                                        │
│        │  apksigner sign --ks project.keystore                      │
│        ▼                                                            │
│  MicAlternativo.apk  →  GitHub Release / local HTTP serve / QR      │
└──────────────────────────────────────────────────────────────────┘
```

### Component Responsibilities

| Component | Responsibility | Typical Implementation |
|-----------|----------------|-------------------------|
| `MainActivity` | Owns the UI, wires user actions to the other components, holds current app state (idle/recording/recorded/playing), requests permission | `extends Activity` (not `AppCompatActivity` — no AndroidX), views built in `onCreate()` via `new LinearLayout(this)` etc., no XML layouts required (though a minimal `res/layout` can still be used if desired — see note below) |
| `PermissionGate` | Checks/requests `RECORD_AUDIO` at runtime, surfaces rationale, re-checks on `onRequestPermissionsResult` | Small static helper or a few methods inlined in `MainActivity`; `ActivityCompat` is AndroidX — use `Activity.requestPermissions()` (framework API, available since API 23) directly instead |
| `RecordingEngine` | Owns one `MediaRecorder` instance and its lifecycle; knows nothing about UI | Plain Java class wrapping `MediaRecorder`; exposes `start(Uri target, int audioSource)`, `stop()`, `release()`; internally does `setAudioSource → setOutputFormat → setAudioEncoder → setOutputFile(fd) → prepare() → start()` |
| `MediaStoreRepository` | Creates the pending MediaStore row, opens the writable file descriptor, finalizes (`IS_PENDING=0`) or deletes on failure | Plain Java class wrapping `ContentResolver` calls; single source of truth for how audio files are created/finalized/deleted |
| `PlaybackEngine` | Owns one `MediaPlayer` instance for previewing the last recording | Plain Java class wrapping `MediaPlayer`; `setDataSource(context, uri) → prepare()/prepareAsync() → start()`, release on stop/reuse |
| `ShareHelper` | Builds and fires the `ACTION_SEND` intent for the recorded file | Static method building `Intent` with `EXTRA_STREAM = contentUri`, `type = "audio/*"` (or exact MIME, e.g. `audio/mp4`), `FLAG_GRANT_READ_URI_PERMISSION` |
| `AudioSourceCatalog` (optional/diagnostic) | Static list of `MediaRecorder.AudioSource` constants to show in the picker; optionally enumerates `AudioDeviceInfo` via `AudioManager.getDevices()` for diagnostics text | Plain data class / enum-like int-to-label map; `AudioManager` device enumeration is read-only info, not wired into recording itself (framework does not let you pick a *device*, only a *source* constant) |

**Key boundary clarification:** `RecordingEngine` and `MediaStoreRepository` are deliberately separate. The Activity asks `MediaStoreRepository` for a writable target (a `Uri` + `ParcelFileDescriptor`), then hands the *file descriptor* to `RecordingEngine`. `RecordingEngine` never touches `ContentResolver` directly — this keeps the MediaRecorder wrapper reusable/testable and keeps all scoped-storage knowledge in one place.

## Recommended Project Structure

```
app/
├── AndroidManifest.xml
├── keystore/
│   └── .gitignore              # keystore file itself is NOT committed
├── res/
│   ├── values/
│   │   └── strings.xml          # PT-BR strings (even with programmatic UI, keep
│   │                             # user-facing strings in resources — required for
│   │                             # app label in manifest, easy to find/edit)
│   └── mipmap-*/  (or just mipmap-anydpi/ + one PNG)
│                                 # launcher icon — minimum viable icon set
├── src/
│   └── com/example/micalternativo/
│       ├── MainActivity.java           # UI + orchestration
│       ├── PermissionGate.java         # RECORD_AUDIO flow
│       ├── RecordingEngine.java        # MediaRecorder wrapper
│       ├── MediaStoreRepository.java   # MediaStore insert/finalize/delete
│       ├── PlaybackEngine.java         # MediaPlayer wrapper
│       ├── ShareHelper.java            # ACTION_SEND builder
│       └── AudioSourceCatalog.java     # AudioSource enum + labels (PT-BR)
├── build/                        # gitignored — all generated artifacts
│   ├── gen/                      # aapt2-generated R.java
│   ├── obj/                      # javac .class output
│   ├── dex/                      # d8 output (classes.dex)
│   └── out/
│       ├── unsigned.apk
│       ├── aligned.apk
│       └── MicAlternativo.apk    # final signed artifact
scripts/
├── build.sh                      # the entire no-Gradle pipeline, idempotent
├── sign.sh                       # (optional split-out) keystore creation + signing
├── install.sh                    # adb install -r for local testing
└── release.sh                    # tag + gh release upload (or scp to local server)
.planning/                        # GSD planning artifacts (not part of app)
README.md                         # sideload instructions in PT-BR
```

### Structure Rationale

- **`app/src/.../` flat package, no layered `ui/`, `data/`, `domain/` subpackages:** at ~7 classes total, Java-package-per-layer is overkill. One flat package keeps the build script's `javac`/`d8` invocations trivial (`javac -d build/obj $(find src -name '*.java')`) and avoids premature structure for a codebase this small. Revisit only if the class count roughly doubles.
- **`res/` kept minimal but present, not eliminated:** "UI programática" means *layouts* are built in code, not that resources disappear entirely. The manifest still needs `@string/app_name` and a launcher icon reference; `aapt2` still needs a `res/` directory to link against (an empty or near-empty one is fine — pointing `aapt2 link` at a directory with zero required resources works, but shipping without any launcher icon produces a default/no icon and a slightly awkward install experience). Recommendation: keep `res/values/strings.xml` and one `mipmap` icon; skip `res/layout` entirely.
- **`build/` fully gitignored, `scripts/` fully committed:** the entire point of the no-Gradle constraint is reproducibility. Nothing under `build/` should ever be hand-edited or committed; `scripts/build.sh` is the single source of truth for how source becomes APK, runnable identically by a human or CI.
- **`keystore/` directory present but keystore file itself gitignored:** the project needs the *same* keystore across builds to allow future update installs over the existing app (Android refuses to install an APK signed with a different key over an existing install of the same package). The directory documents *where* it lives; the actual `.jks`/`.keystore` file and its password must be handled as a secret (local file outside git, or CI secret store) — never committed in plaintext.
- **Scripts split by concern (`build.sh`, `sign.sh`, `install.sh`, `release.sh`)** rather than one monolithic script: `build.sh` should be runnable/testable without touching signing secrets (useful for CI checks that don't have keystore access); `install.sh` is a fast local dev-loop convenience; `release.sh` is the one-shot "ship it" script invoked manually.

## Architectural Patterns

### Pattern 1: Thin wrapper classes around framework lifecycle objects (`MediaRecorder`, `MediaPlayer`)

**What:** Each stateful Android framework object that has a strict call-order contract (`MediaRecorder`: `setAudioSource → setOutputFormat → setAudioEncoder → setOutputFile → prepare → start → stop → release`; `MediaPlayer`: `setDataSource → prepare → start → stop/release`) gets its own small Java class that owns exactly one instance, exposes a reduced state machine (`idle → recording → stopped`), and centralizes error handling (`IllegalStateException` from calling methods out of order, `IOException` from `prepare()`).

**When to use:** Always, for this app — `MediaRecorder`/`MediaPlayer` are notoriously easy to misuse (calling `stop()` without a preceding successful `start()` throws; forgetting `release()` leaks the audio hardware and blocks *other* apps, including retrying within the same app).

**Trade-offs:** Adds one extra class per framework object versus inlining calls in the Activity, but pays for itself immediately — the Activity becomes UI orchestration only, and lifecycle bugs (leaked recorder across rotation, double-release) are isolated to one place to test/reason about.

**Example:**
```java
public class RecordingEngine {
    private MediaRecorder recorder;
    private boolean recording = false;

    public void start(FileDescriptor fd, int audioSource) throws IOException {
        if (recording) throw new IllegalStateException("already recording");
        recorder = new MediaRecorder();
        recorder.setAudioSource(audioSource);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setOutputFile(fd);
        recorder.prepare();   // throws IOException — caller must clean up MediaStore row on failure
        recorder.start();
        recording = true;
    }

    public void stop() {
        if (!recording) return;
        try {
            recorder.stop();
        } finally {
            recorder.release();
            recorder = null;
            recording = false;
        }
    }
}
```

### Pattern 2: MediaStore "pending row" write pattern

**What:** Because the app targets scoped storage (minSdk 29+), audio files are never written to a raw filesystem path the app owns. Instead: (1) `ContentResolver.insert()` a new row into `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` with `ContentValues` containing `DISPLAY_NAME`, `MIME_TYPE`, `RELATIVE_PATH` (e.g. `Music/MicAlternativo/`), and `IS_PENDING = 1`; (2) open a `ParcelFileDescriptor` on the returned `Uri` via `openFileDescriptor(uri, "w")` and hand its `FileDescriptor` to `MediaRecorder.setOutputFile()`; (3) on successful `stop()`, flip `IS_PENDING = 0` via `update()` so the file becomes visible to other apps (Files, music players); (4) on any failure, `delete()` the row to avoid orphaned pending entries.

**When to use:** Every recording save. This is the *only* correct way to persist user-generated audio on API 29+ without `WRITE_EXTERNAL_STORAGE` (which is why minSdk 29 was chosen per PROJECT.md).

**Trade-offs:** More ceremony than `File(getExternalFilesDir(), "rec.m4a")`, but that alternative either requires broad storage permissions (deprecated path, poor UX, blocked on newer targetSdk for shared media) or writes to app-private storage that other apps (and the sharing flow) can't see without a `FileProvider`. MediaStore's own `content://` URI is also what makes Pattern 3 (sharing) trivial — no separate `FileProvider` XML/config needed.

**Example:**
```java
ContentValues values = new ContentValues();
values.put(MediaStore.Audio.Media.DISPLAY_NAME, fileName);
values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
values.put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/MicAlternativo");
values.put(MediaStore.Audio.Media.IS_PENDING, 1);

Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
Uri itemUri = resolver.insert(collection, values);

try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(itemUri, "w")) {
    recordingEngine.start(pfd.getFileDescriptor(), audioSource);
    // ... later, on stop():
    values.clear();
    values.put(MediaStore.Audio.Media.IS_PENDING, 0);
    resolver.update(itemUri, values, null, null);
} catch (IOException e) {
    resolver.delete(itemUri, null, null); // cleanup orphaned pending row
}
```

### Pattern 3: MediaStore URI reused directly for sharing (no FileProvider)

**What:** The same `content://media/external/audio/media/<id>` `Uri` returned by the `MediaStore` insert is passed straight into `Intent.EXTRA_STREAM` for `ACTION_SEND`, with `FLAG_GRANT_READ_URI_PERMISSION` set on the intent. Because it's a system content provider URI (not a file path or app-private URI), the receiving app (WhatsApp) can read it without any custom `<provider>` declaration in the manifest.

**When to use:** Always for this app's share flow — this is precisely why "MediaStore em vez de FileProvider" was already logged as a key decision in PROJECT.md.

**Trade-offs:** None significant here — FileProvider would be strictly more code (manifest `<provider>` entry + `file_paths.xml` + `getUriForFile()`) for equivalent behavior, and would only be *necessary* if the file lived in app-private storage instead of MediaStore.

**Example:**
```java
Intent send = new Intent(Intent.ACTION_SEND);
send.setType("audio/mp4");
send.putExtra(Intent.EXTRA_STREAM, recordedUri);
send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
startActivity(Intent.createChooser(send, getString(R.string.share_chooser_title)));
```

## Data Flow

### Recording Flow

```
[User taps "Gravar"]
    ↓
[MainActivity] → PermissionGate.ensureRecordAudio()
    │   (if not granted: requestPermissions() → onRequestPermissionsResult → retry)
    ↓ (granted)
[MainActivity] reads selected AudioSource from spinner (AudioSourceCatalog)
    ↓
[MainActivity] → MediaStoreRepository.createPendingAudioFile(displayName, mime)
    ↓ returns (Uri, ParcelFileDescriptor)
[MainActivity] → RecordingEngine.start(pfd.getFileDescriptor(), audioSource)
    ↓ (MediaRecorder: setAudioSource→setOutputFormat→setAudioEncoder→setOutputFile→prepare→start)
[User taps "Parar"]
    ↓
[MainActivity] → RecordingEngine.stop()  (stop + release internally)
    ↓
[MainActivity] → MediaStoreRepository.finalize(uri)  (IS_PENDING = 0)
    ↓
[MainActivity] stores `currentRecordingUri` in Activity state → enables Play/Share buttons
```

### Playback Flow

```
[User taps "Ouvir"]
    ↓
[MainActivity] → PlaybackEngine.play(currentRecordingUri)
    ↓ (MediaPlayer: reset→setDataSource(context, uri)→prepare/prepareAsync→start)
[Playback completes] → OnCompletionListener → PlaybackEngine.release()
    ↓
[MainActivity] resets Play button state
```

### Share Flow

```
[User taps "Compartilhar"]
    ↓
[MainActivity] → ShareHelper.share(context, currentRecordingUri, mimeType)
    ↓ builds Intent(ACTION_SEND) with EXTRA_STREAM=uri, FLAG_GRANT_READ_URI_PERMISSION
    ↓
[Android system chooser] → user picks WhatsApp
    ↓
[WhatsApp] reads content:// URI directly (no data copy needed by MicAlternativo)
```

### Key Data Flows

1. **Audio bytes:** microphone hardware → `MediaRecorder` (native encoder) → `ParcelFileDescriptor` → MediaStore-backed file on disk. The app's Java code never touches raw PCM/encoded bytes directly; it only manages the *lifecycle* and the *destination handle*. This is the correct and only supported flow — do not attempt manual `AudioRecord` + manual encoding unless a future requirement needs raw PCM access (out of scope here).
2. **Uri as the shared handle:** once created, the `content://` `Uri` is the single identifier passed between `MediaStoreRepository` (creates it), `PlaybackEngine` (reads it), and `ShareHelper` (grants+passes it) — the Activity holds it as its one piece of durable state (persist across rotation via `onSaveInstanceState`).
3. **AudioSource selection is write-once-per-recording:** the source constant is read at the moment `RecordingEngine.start()` is called and is not changeable mid-recording; switching sources requires stopping and starting a new recording. UI should reflect this (disable the source spinner while recording).

## Scaling Considerations

Not applicable in the traditional multi-user sense — this is a single-user, single-device, offline app (per PROJECT.md: "Sem serviços externos"). "Scaling" here means graceful degradation across recording count and device diversity, not user load.

| Scale | Architecture Adjustments |
|-------|---------------------------|
| First use / few recordings | Current design (list not required — app can just track "last recording" as MVP) is sufficient |
| Many recordings accumulated | If a future milestone wants a "gravações anteriores" list, add a `MediaStoreRepository.queryRecordings()` using a `Cursor`/`ContentResolver.query()` filtered by `RELATIVE_PATH LIKE 'Music/MicAlternativo%'` — no architectural change needed, just a new read path parallel to the existing write path |
| Wide device diversity (different OEMs, different mic layouts) | `AudioSourceCatalog` + optional `AudioManager.getDevices()` diagnostics already anticipate this — the picker exists precisely because AudioSource behavior is OEM/device-specific and not something the app can hardcode correctly for all devices |

### Scaling Priorities

1. **First real risk: `AudioSource` behavior varying by device/OEM/Android version.** Not a code scaling issue but a *correctness* one — this is why the app exposes a picker instead of hardcoding `CAMCORDER`. No architectural mitigation beyond what's already planned (user-facing trial-and-error via the source picker + optional diagnostics screen).
2. **Second: storage growth on-device.** Each recording is a full MediaStore-visible file (not app-private cache) — over long-term use, files accumulate. Out of scope for MVP; if it becomes a problem, add a "delete recording" action using `ContentResolver.delete(uri)` (straightforward addition, no restructuring).

## Anti-Patterns

### Anti-Pattern 1: Writing to a raw `File` path (`getExternalStorageDirectory()` / `getExternalFilesDir()`) and hoping it "just shows up" as shareable audio

**What people do:** Skip MediaStore entirely, write with `new FileOutputStream(new File(path))`, then try to share via `Uri.fromFile(file)`.

**Why it's wrong:** On API 29+ with scoped storage, `Uri.fromFile()` produces a `file://` URI that most receiving apps (including WhatsApp) reject or can't read due to `StrictMode`/`FileUriExposedException`, and app-private directories (`getExternalFilesDir()`) aren't visible to other apps' file pickers or media scanners at all. This is exactly the trap PROJECT.md's "MediaStore em vez de FileProvider" decision already avoids — but it's worth stating explicitly as the anti-pattern being avoided, since it's the single most common mistake in Android audio-recorder tutorials found online (many predate scoped storage).

**Do this instead:** Pattern 2 above — MediaStore insert with `IS_PENDING`, write via the returned content Uri's file descriptor.

### Anti-Pattern 2: Reusing a single `MediaRecorder`/`MediaPlayer` instance across multiple record/play cycles without full `release()`

**What people do:** Call `recorder.reset()` and reuse the same object for the next recording, or keep a `MediaPlayer` alive "just in case" across Activity pause/resume.

**Why it's wrong:** `MediaRecorder`/`MediaPlayer` hold onto the audio hardware/codec resources; failing to `release()` promptly — especially across `onPause()`/`onStop()` — can leave the microphone or audio decoder locked, causing the *next* `prepare()` call (in this app or another) to throw `IllegalStateException` or silently fail. This is a frequent source of "recording just stops working until app restart" bug reports in Android audio apps.

**Do this instead:** Treat both engines as strictly single-use per operation — always `release()` in `stop()`/`onCompletion()`/`onPause()`/`onDestroy()`, and construct a fresh `MediaRecorder`/`MediaPlayer` instance for each new start. The wrapper classes in Pattern 1 enforce this by construction.

### Anti-Pattern 3: Building the APK pipeline as one giant opaque script with no intermediate artifact inspection

**What people do:** Chain `aapt2 | javac | d8 | zip | zipalign | apksigner` in one shell one-liner with no error checking between stages.

**Why it's wrong:** Each tool in this chain has a narrow, cryptic failure mode (e.g., `aapt2 link` failing on a missing resource reference produces a different error class than `d8` failing on an unsupported bytecode feature); without `set -e` and clear per-stage echo/logging, a failure in stage 2 can produce a corrupt-but-present file that stage 5 then "successfully" signs, yielding an APK that installs but crashes on launch.

**Do this instead:** `scripts/build.sh` should use `set -euo pipefail`, echo a clear "Step N: ..." before each tool invocation, and fail loudly with the tool's exact stderr on any non-zero exit. Keep each stage's output as a separate, inspectable file under `build/` (not piped in-memory) so a failure can be diagnosed by inspecting the last-good intermediate artifact.

## Integration Points

### External Services

None. Per PROJECT.md constraint "Sem serviços externos: app 100% offline; nenhum dado sai do aparelho," there is no backend, no analytics, no crash reporting service. The only "external" integration is the Android OS itself (MediaStore, MediaRecorder/MediaPlayer, Intent system) and, for distribution only (not runtime), GitHub Releases.

| Service | Integration Pattern | Notes |
|---------|----------------------|-------|
| WhatsApp (or any ACTION_SEND target) | Implicit `Intent` + system chooser | App never links against WhatsApp; fully decoupled — works with any app that registers an `ACTION_SEND` audio/* intent filter |
| GitHub Releases (distribution, not runtime) | `gh release create` / `gh release upload` from `scripts/release.sh`, or manual web upload | Not part of the app itself — purely a delivery mechanism for the signed APK artifact |

### Internal Boundaries

| Boundary | Communication | Notes |
|----------|----------------|-------|
| `MainActivity` ↔ `PermissionGate` | Direct method calls, callback via `onRequestPermissionsResult` override | Activity owns the permission-result callback since only an Activity/Fragment can receive it; `PermissionGate` can be a stateless helper with static methods, or hold a weak reference — stateless is simpler and sufficient here |
| `MainActivity` ↔ `RecordingEngine` | Direct method calls (`start`/`stop`), synchronous | No threading concerns — `MediaRecorder.start()`/`stop()` are synchronous but fast; do not need a background thread for this app's simple record/stop model |
| `MainActivity` ↔ `MediaStoreRepository` | Direct method calls, returns `Uri`/throws `IOException` | Should run on a background thread only if `ContentResolver` calls show jank in testing — for single-file audio inserts this is typically fast enough on the main thread, but wrapping in `AsyncTask`-free approach (e.g. `Executor` + `Handler.post` back to main) is a reasonable defensive choice given no AndroidX (no ViewModel/LiveData/Coroutines available) |
| `MainActivity` ↔ `PlaybackEngine` | Direct method calls, `OnCompletionListener` callback | Same threading note — `MediaPlayer.prepareAsync()` (not blocking `prepare()`) is recommended if any UI jank is observed during testing |
| `MainActivity` ↔ `ShareHelper` | Direct static method call, fire-and-forget `startActivity()` | No return value needed — app doesn't need to know what the user chose in the chooser |
| `RecordingEngine` ↔ Android `MediaRecorder` | Framework API calls | Strict lifecycle contract (Pattern 1) |
| `MediaStoreRepository` ↔ Android `ContentResolver`/`MediaStore` | Framework API calls | Scoped storage contract (Pattern 2) |
| `scripts/build.sh` ↔ Android SDK command-line tools | Shell subprocess calls (`aapt2`, `javac`, `d8`, `zipalign`, `apksigner`) | Tools are already validated in the environment per PROJECT.md context (`android-sdk/` in scratchpad); paths to these tools should be parameterized (env vars or a `scripts/env.sh` with `ANDROID_SDK_ROOT`, `BUILD_TOOLS_VERSION`, `PLATFORM_VERSION`) so the script is portable to CI |

## Suggested Build Order (component dependency graph)

This is the order in which components should be implemented, driven by what each depends on:

1. **Build pipeline skeleton first** (`scripts/build.sh` producing an installable "Hello World" APK with just `MainActivity` showing a `TextView`). This validates the entire toolchain (manifest, aapt2, javac, d8, zipalign, apksigner, keystore) before any recording logic exists — de-risks the highest-uncertainty piece (the no-Gradle build) independently of app logic.
2. **Permission flow** (`PermissionGate` + manifest `<uses-permission android:name="android.permission.RECORD_AUDIO"/>`) — needed before recording can be tested at all on-device.
3. **`MediaStoreRepository`** (create-pending-row + finalize) — can be built and manually tested (e.g., write a dummy byte via `openOutputStream` and confirm the file appears in Files app) *before* wiring up `MediaRecorder`, isolating scoped-storage correctness from recording correctness.
4. **`RecordingEngine`** wired to `MediaStoreRepository`'s file descriptor — first true end-to-end "record and save" slice. Hardcode `AudioSource.MIC` or `CAMCORDER` initially to reduce variables while validating the record→save path.
5. **`AudioSourceCatalog` + UI picker** — layer source selection on top of the now-working record/save path.
6. **`PlaybackEngine`** — independent of recording once a MediaStore Uri exists; can be developed/tested against any existing audio file in MediaStore, not just app-recorded ones.
7. **`ShareHelper`** — last, since it depends on having a valid, finalized (non-pending) MediaStore Uri from steps 3–4.
8. **Signing/release hardening** (`sign.sh`, `release.sh`, README sideload instructions) — once the app itself is functionally complete, finalize the *same* keystore for all future builds and document the distribution path (GitHub Release / local HTTP serve / QR code — all equivalent from an architecture standpoint: they just need to serve the static `.apk` file, no server-side logic).

**Dependency rationale:** Steps 1–3 are prerequisites that de-risk infrastructure (build toolchain, permissions, storage) independently of each other and of the recording logic proper. Step 4 is the core value-delivering slice and should be reached as early as possible once its three prerequisites exist. Steps 5–7 are additive features layered onto a working core loop, each independently testable. Step 8 is packaging/delivery and has no bearing on app correctness, so it belongs last (though the keystore itself should be *generated* early, e.g. right after step 1, so every subsequent test build is already signed with the final, permanent key rather than a throwaway one).

## Sources

- [MediaRecorder overview — Android Developers](https://developer.android.com/media/platform/mediarecorder) — HIGH confidence (official docs)
- [MediaStore.Audio.Media — Android Developers API reference](https://developer.android.com/reference/android/provider/MediaStore.Audio.Media) — HIGH confidence (official docs)
- [Working with Scoped Storage — ProAndroidDev](https://proandroiddev.com/working-with-scoped-storage-8a7e7cafea3) — MEDIUM-HIGH confidence (well-known community reference, consistent with official docs on IS_PENDING/RELATIVE_PATH pattern)
- [Media Store access using openFile() on Android 10 — Joe Birch](https://joebirch.co/android/media-store-access-using-openfile-on-android-10/) — MEDIUM confidence (community, consistent with official pattern)
- [Building an Android App from the Command Line — hanshq.net](https://www.hanshq.net/command-line-android.html) — HIGH confidence (widely-cited, detailed, verified working command-line build recipe including aapt/d8/zipalign/apksigner/keytool sequence)
- [Android CLI APK Builder gist](https://gist.github.com/felix021/e7179596244ee81852c646f904adddf6) — MEDIUM confidence (community script, corroborates the same pipeline shape)
- [AAPT2 — Android Studio Developers](https://developer.android.com/tools/aapt2) — HIGH confidence (official docs on compile/link split and SDK-based resource filtering)
- [Send simple data to other apps — Android Developers](https://developer.android.com/training/sharing/send) — HIGH confidence (official docs on ACTION_SEND + EXTRA_STREAM pattern)
- [Sharing files through Intents (part 2) — Medium](https://medium.com/@quiro91/sharing-files-through-intents-part-2-fixing-the-permissions-before-lollipop-ceb9bb0eec3a) — MEDIUM confidence (community, corroborates FLAG_GRANT_READ_URI_PERMISSION behavior with ACTION_SEND/EXTRA_STREAM)
- Project-internal: `.planning/PROJECT.md` — authoritative for constraints (no Gradle, no AndroidX, minSdk 29, MediaStore-over-FileProvider decision already made)

---
*Architecture research for: Minimal Android audio recorder, no-dependency build pipeline*
*Researched: 2026-07-28*
