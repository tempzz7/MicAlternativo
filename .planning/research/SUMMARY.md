# Project Research Summary

**Project:** MicAlternativo
**Domain:** Android utility app — audio recorder with mic-source-selection workaround, no-Gradle native toolchain build, PT-BR, sideload distribution
**Researched:** 2026-07-28
**Confidence:** MEDIUM-HIGH (platform/build mechanics HIGH, verified against official Android docs; OEM-specific mic-routing behavior and Samsung One UI sideload friction MEDIUM/LOW, inferred from community sources)

## Executive Summary

MicAlternativo is a single-purpose Android utility: a person with a physically broken primary microphone on a Samsung Galaxy A15 5G needs to record and send voice audio via WhatsApp using whichever secondary mic (camera-side) still works. This is not a generic "voice recorder app" — it's a targeted hardware workaround where the entire value proposition rests on `MediaRecorder.AudioSource` selection (CAMCORDER, MIC, VOICE_RECOGNITION, VOICE_COMMUNICATION, DEFAULT) exposed as a first-class, user-facing feature rather than a buried settings option, which is exactly how existing competitors (Easy Voice Recorder, Samsung Voice Recorder) under-serve this need. The project also carries an unusual build constraint: no Gradle, no AndroidX, a hand-scripted `aapt2 → javac → d8 → zip → zipalign → apksigner` pipeline, distributed via sideload/GitHub Releases rather than Play Store.

The recommended approach is a small, flat-package Java app (~7 classes: `MainActivity`, `PermissionGate`, `RecordingEngine`, `MediaStoreRepository`, `PlaybackEngine`, `ShareHelper`, `AudioSourceCatalog`) built entirely on platform APIs — `MediaRecorder`, `MediaStore.Audio.Media` (scoped-storage pending-row pattern), and `Intent.ACTION_SEND` with the MediaStore `content://` URI reused directly for sharing (no FileProvider needed). minSdk 29 is deliberately chosen because it's the first version where MediaStore inserts work without `WRITE_EXTERNAL_STORAGE`, matching the no-dependency, scoped-storage-native design. MVP scope is tightly bounded: record/stop with a manual source picker, playback-before-send (critical trust-builder), save to MediaStore, share via system chooser, a minimal recording list, and a PT-BR onboarding explainer — with auto-detect-best-source, persisted preferences, and a one-tap WhatsApp shortcut explicitly deferred to post-validation (v1.x).

The two biggest risk clusters, both requiring on-device validation on the actual A15 5G rather than emulator/generic-device testing: (1) **AudioSource routing is not a guaranteed contract** — CAMCORDER may not reliably map to the working mic on this specific device/firmware, so the source-switcher must be built as core functionality from day one, never as an afterthought, with silence-detection as a safety net; and (2) **the hand-rolled build/signing pipeline has several silent-failure traps** (v1-only signing, misaligned/compressed `resources.arsc`, wrong zipalign/apksigner ordering) that install fine in casual testing but fail on the real Android 14 target — these must be gated by automated `apksigner verify` and `zipalign -c` checks baked directly into the build script, not manual review. A third, Samsung-specific risk — One UI 6's Auto Blocker silently blocking sideload installs beyond the standard "unknown sources" toggle — must be addressed in distribution instructions, not code.

## Key Findings

### Recommended Stack

No external dependencies: everything ships via the platform SDK (`android.jar` at API 34) and the manually-scripted `aapt2`/`javac`/`d8`/`zipalign`/`apksigner` toolchain (build-tools 34.0.0, OpenJDK 21), all already present in the environment. This is intentional per PROJECT.md's no-Gradle/no-AndroidX constraint — the build script itself *is* the build system.

**Core technologies:**
- `MediaRecorder` + `MediaRecorder.AudioSource` — the correct high-level recording API; source selection is the core mechanism for the mic-workaround feature.
- `MediaStore.Audio.Media` (scoped storage, pending-row pattern) — persists recordings and yields a shareable `content://` URI without any storage permission, required on minSdk 29+.
- `Intent.ACTION_SEND` (`EXTRA_STREAM` + `FLAG_GRANT_READ_URI_PERMISSION`) — shares the MediaStore URI directly to WhatsApp/any app; no FileProvider needed.
- `aapt2` → `javac` → `d8` → zip-add-dex → `zipalign` → `apksigner` — the entire no-Gradle build pipeline, wrapped in a single idempotent `build.sh` with `set -euo pipefail` and per-stage logging.
- Plain `Activity.requestPermissions()` (framework, API 23+) — runtime `RECORD_AUDIO` permission flow without AndroidX's `ActivityResultContracts`.

### Expected Features

The app's entire reason to exist is the audio-source picker; everything else is standard voice-recorder table stakes plus WhatsApp-focused sharing.

**Must have (table stakes / MVP):**
- RECORD_AUDIO runtime permission flow with PT-BR rationale
- Record/Stop with CAMCORDER as default source
- Manual audio-source picker (MIC, CAMCORDER, VOICE_RECOGNITION, VOICE_COMMUNICATION, DEFAULT) with plain PT-BR labels
- Playback of the last recording (trust check)
- Save to MediaStore
- Share via system `ACTION_SEND` (WhatsApp selectable via generic chooser)
- Minimal recording list
- PT-BR onboarding/explainer screen

**Should have (differentiators, v1.x — post core-loop validation):**
- Auto-detect working mic (amplitude-based source ranking) — this app's clearest edge over competitors, none of which automate source discovery
- Persisted "working source" preference
- One-tap WhatsApp shortcut button
- Delete/rename on recording list
- Visible amplitude/level meter during recording

**Defer (v2+ / anti-features):**
- Audio editing (trim, effects, noise reduction) — scope creep away from "record once, send once"
- Cloud sync/accounts — conflicts with 100% offline constraint
- Rerouting call-audio mic or WhatsApp's in-app recorder mic — technically impossible without root, already correctly out of scope
- Multiple format/quality presets, direct-to-contact share, Bluetooth mic input — unnecessary complexity for this use case

### Architecture Approach

Single-process, single-Activity app split into thin wrapper classes around each stateful framework object (`MediaRecorder`, `MediaPlayer`), each enforcing its own reduced state machine and centralizing lifecycle error handling — this isolates the framework's notoriously strict call-order contracts from the UI layer. `RecordingEngine` never touches `ContentResolver` directly; `MediaStoreRepository` creates the pending row and hands a `FileDescriptor` to the recorder, keeping scoped-storage knowledge in one place. A parallel build architecture (scripted CLI pipeline) sits alongside the runtime architecture, with `scripts/build.sh` as the single source of truth for compiling source into a signed APK.

**Major components:**
1. `MainActivity` — UI orchestration, state (idle/recording/recorded/playing), permission requests
2. `RecordingEngine` — owns one `MediaRecorder` instance and its strict lifecycle
3. `MediaStoreRepository` — MediaStore insert/finalize (IS_PENDING)/delete, the single source of truth for scoped-storage correctness
4. `PlaybackEngine` — owns one `MediaPlayer` instance for preview
5. `ShareHelper` — builds the `ACTION_SEND` intent with the MediaStore URI
6. `AudioSourceCatalog` — PT-BR labeled list of `AudioSource` constants

**Suggested build order:** build pipeline skeleton (de-risk toolchain) → permission flow → `MediaStoreRepository` (tested independently) → `RecordingEngine` wired to storage (first end-to-end slice, hardcoded source) → source picker UI → `PlaybackEngine` → `ShareHelper` → signing/release hardening (keystore generated early, right after step 1).

### Critical Pitfalls

1. **AudioSource doesn't reliably route to the working mic** — CAMCORDER/MIC/etc. are OEM/HAL-dependent hints, not contracts; never hardcode a single source, build the switcher as core functionality, and add silence/amplitude detection as a safety net so users aren't fooled by a "successful" but silent recording.
2. **MediaRecorder state-machine crashes** — rapid source-switch testing, rotation mid-recording, or double-tap can throw `IllegalStateException`/`RuntimeException`; always recreate a fresh `MediaRecorder` per attempt, wrap every transition in try/catch with `reset()` on failure, and guard against too-short recordings.
3. **Build pipeline silent signing/alignment failures** — v1-only signing, non-uncompressed/misaligned `resources.arsc`, or zipalign-after-sign ordering mistakes all install fine in casual local testing but fail on the real Android 14 target with an unhelpful error; must be caught by automated `apksigner verify -v` and `zipalign -c -v 4` gates baked into the build script, verified on the real A15 5G device.
4. **MediaStore IS_PENDING mishandling** — skipping or misordering the insert→write→clear-pending sequence leaves files invisible/orphaned; must verify files actually appear in a real Files app, not just that insert didn't throw.
5. **WhatsApp share fails silently or crashes** — using a `file://` URI throws `FileUriExposedException`; forgetting `FLAG_GRANT_READ_URI_PERMISSION` on an otherwise-correct `content://` URI causes WhatsApp-side failures with no crash on the app's side — must be verified with a real on-device share to an actual WhatsApp chat.
6. **Permanent RECORD_AUDIO denial mishandled** — `shouldShowRequestPermissionRationale()` returning false is ambiguous (never-asked vs. permanently-denied); must track request history locally and deep-link to Settings on permanent denial.
7. **Samsung Auto Blocker blocks sideload despite a correct APK** — One UI 6 has an additional install gate beyond standard "unknown sources," undocumented in generic Android tutorials; must be covered explicitly in PT-BR distribution instructions and validated with a real clean install on a One UI 6 device.

## Implications for Roadmap

Based on research, suggested phase structure:

### Phase 1: Build Pipeline Skeleton
**Rationale:** The no-Gradle toolchain is the single highest-uncertainty piece of infrastructure and has zero dependency on app logic — de-risk it independently and first, producing an installable "Hello World" APK.
**Delivers:** `scripts/build.sh` (aapt2 → javac → d8 → zip → zipalign → apksigner) producing a signed, installable minimal APK; the permanent release keystore generated here (reused for every future build).
**Uses:** Android SDK 34, build-tools 34.0.0, OpenJDK 21 (Stack)
**Avoids:** Pitfalls 4, 5, 6 (v1-only signing, resources.arsc misalignment, wrong zipalign/apksigner order) — bake `apksigner verify` + `zipalign -c` as automated, non-optional build-script gates from the very first commit.

### Phase 2: Permissions + Core Record/Save Loop
**Rationale:** Nothing else works without RECORD_AUDIO granted, and MediaStore correctness should be validated independently of MediaRecorder before combining them.
**Delivers:** `PermissionGate` (with permanent-denial handling), `MediaStoreRepository` (pending-row insert/finalize, tested with dummy bytes before wiring MediaRecorder), and `RecordingEngine` wired end-to-end with a single hardcoded source (MIC or CAMCORDER) to validate the record→save path with minimal variables.
**Addresses:** RECORD_AUDIO permission flow, Record/Stop, Save to MediaStore (FEATURES.md P1 items)
**Avoids:** Pitfall 9 (permanent denial mishandling), Pitfall 7 (IS_PENDING mishandling), Pitfall 2 (state-machine crashes)

### Phase 3: Audio-Source Selection + PT-BR Onboarding
**Rationale:** This is the app's core differentiator and must be layered onto a now-working record/save path; PT-BR explainer content is cheap, independent, and high-trust-value, so ship it alongside.
**Delivers:** `AudioSourceCatalog` with PT-BR labels, manual source picker UI, PT-BR onboarding/explainer screen setting expectations (fixes recording/WhatsApp, not phone calls).
**Addresses:** Manual audio-source picker, PT-BR onboarding/explainer (FEATURES.md P1 items)
**Avoids:** Pitfall 1 (AudioSource routing assumptions) — must be validated by manual test-record-and-playback across all sources on the real A15 5G before this phase is considered done.

### Phase 4: Playback + Share to WhatsApp
**Rationale:** Playback is independent of recording (works on any existing MediaStore audio) and is the trust-check gate before sharing; sharing requires a finalized (non-pending) MediaStore URI from Phase 2, so it must come after storage is proven solid.
**Delivers:** `PlaybackEngine`, `ShareHelper` (ACTION_SEND with MediaStore URI + FLAG_GRANT_READ_URI_PERMISSION), minimal recording list.
**Addresses:** Playback, Share to WhatsApp, Minimal recording list (FEATURES.md P1 items)
**Avoids:** Pitfall 8 (FileUriExposedException / missing URI permission) — verify with a real on-device share into an actual WhatsApp chat, not just that the intent doesn't crash.

### Phase 5: Distribution / Sideload Hardening
**Rationale:** Packaging/delivery has no bearing on app correctness so belongs last, but is Samsung-specific enough to need its own dedicated validation pass distinct from generic Android build concerns.
**Delivers:** `release.sh`, README with PT-BR sideload instructions (unknown-sources toggle + Auto Blocker troubleshooting + Play Protect warning expectations), GitHub Release publishing flow.
**Avoids:** Pitfalls 10 (Auto Blocker), 11 (Play Protect warning mistaken for a build defect) — validated by an actual clean install on a real or freshly reset One UI 6 device.

### Phase Ordering Rationale

- Build pipeline comes first because it's a project-wide blocker with the highest uncertainty and no app-logic dependency — every other phase needs a working "compile and install" loop to test against.
- Permissions + storage precede recording-feature richness because they're foundational, independently testable infrastructure; source selection is deliberately built on top of an already-proven record/save path rather than combined with it, reducing the variables when the (expected) AudioSource-routing surprises appear.
- Sharing is sequenced after storage/playback are solid because `ACTION_SEND` depends on a finalized, non-pending MediaStore URI — testing share before storage correctness would produce confusing, unattributable failures.
- Distribution hardening is last because it's orthogonal to app correctness, but the keystore itself must be generated in Phase 1 (not deferred) so every subsequent test build is already signed with the final key.
- This ordering directly mirrors the "Suggested Build Order" component dependency graph from ARCHITECTURE.md and the Pitfall-to-Phase Mapping from PITFALLS.md, which independently converge on the same sequence.

### Research Flags

Phases likely needing deeper research during planning:
- **Phase 3 (Audio-Source Selection):** OEM/HAL-specific mic routing on the Samsung A15 5G is not officially documented anywhere; real on-device experimentation may surface behavior not covered by this research (e.g., CAMCORDER routing to the wrong physical mic on this specific firmware).
- **Phase 5 (Distribution):** Samsung Auto Blocker menu paths/wording vary by One UI sub-version and are only community-documented (LOW confidence); verify current wording against the actual device at implementation time.

Phases with standard patterns (skip research-phase):
- **Phase 1 (Build Pipeline):** Well-documented against official Android developer docs (aapt2, d8, apksigner, zipalign) — HIGH confidence, standard tool sequence.
- **Phase 2 (Permissions + Core Record/Save):** MediaStore scoped-storage pending-row pattern and RECORD_AUDIO runtime permission flow are both officially documented, stable, HIGH-confidence patterns.
- **Phase 4 (Playback + Share):** ACTION_SEND + MediaStore URI sharing is a well-established, officially documented pattern.

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | Build pipeline, signing, and platform APIs verified directly against developer.android.com/source.android.com; only Samsung-specific mic routing is inferred (no official doc) |
| Features | MEDIUM | Core Android platform mechanics (AudioSource constants, permission flow) HIGH/official; competitor feature claims and WhatsApp ACTION_SEND rendering behavior are MEDIUM/LOW, community-sourced and not independently reproduced |
| Architecture | HIGH | Official docs for MediaRecorder/MediaStore/ACTION_SEND patterns; build-script edge cases (exact flag ordering) MEDIUM where sources diverge |
| Pitfalls | MEDIUM | Core pitfalls (state machine, signing, scoped storage) cross-checked against official docs; OEM-specific (Auto Blocker) and undocumented native errors (start failed -19) are LOW, community-sourced only |

**Overall confidence:** MEDIUM-HIGH

### Gaps to Address

- **Exact AudioSource-to-physical-mic mapping on the Samsung A15 5G:** no public Samsung API or documentation confirms this; must be resolved empirically on the real device during Phase 3, not assumed from research — budget explicit on-device test time before declaring the source picker "done."
- **WhatsApp's exact handling of shared M4A files:** community sources agree it arrives as a playable audio/document attachment (not a native voice-note bubble), but this hasn't been independently reproduced end-to-end in this research — verify manually in Phase 4 and set correct user expectations in onboarding copy.
- **Samsung Auto Blocker exact menu paths/wording:** varies by One UI sub-version (6.0 vs 6.1) per LOW-confidence press/community reporting only — verify against the actual target device (or a freshly reset One UI 6 device) when writing Phase 5 distribution instructions, and phrase instructions with fallback wording.
- **`aapt2` v1 (`aapt`) presence/absence in build-tools 34.0.0:** could not be confirmed from search; the safe assumption (aapt2-only, never depend on legacy `aapt`) is already baked into the Stack recommendation and should not need revisiting, but flagged for awareness.

## Sources

### Primary (HIGH confidence)
- developer.android.com — aapt2, d8, apksigner, zipalign, MediaRecorder overview, MediaRecorder.AudioSource reference, MediaStore.Audio.Media reference, foreground service types (Android 14), runtime permissions, ACTION_SEND/send simple data
- source.android.com — APK signature scheme v2, preprocessing effects / AudioSource behavior confirmation

### Secondary (MEDIUM confidence)
- hanshq.net — "Building an Android App from the Command Line" (full manual build pipeline, cross-checked against official docs)
- CommonsWare — Scoped Storage Stories (MediaStore IS_PENDING pattern, respected community authority)
- ProAndroidDev / Joe Birch — scoped storage / openFile() on Android 10 community writeups, consistent with official pattern
- Digipom (Easy Voice Recorder Help), Samsung official support doc — competitor feature documentation
- Medium (FileUriExposedException, URI permission grant behavior) — corroborated by multiple independent sources

### Tertiary (LOW confidence)
- Android Authority — Samsung Auto Blocker sideload reporting (press/community, not officially documented by Samsung)
- CodingTechRoom — undocumented native MediaRecorder "start failed -19" error
- chattopdf.app and community threads — WhatsApp M4A-as-document attachment behavior (not WhatsApp-official)
- WebSearch aggregate — general mic-test app UX patterns, PT-BR consumer understanding of mic-routing quirks

---
*Research completed: 2026-07-28*
*Ready for roadmap: yes*
