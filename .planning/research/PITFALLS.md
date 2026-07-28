# Pitfalls Research

**Domain:** Android mic-source-selection recording app + Gradle-less hand-assembled APK sideloaded on Samsung One UI 6 (Android 14)
**Researched:** 2026-07-28
**Confidence:** MEDIUM (core findings cross-checked against official Android developer docs; OEM-specific and community-sourced details flagged LOW where noted)

## Critical Pitfalls

### Pitfall 1: Assuming CAMCORDER (or any AudioSource) reliably maps to the working secondary mic

**What goes wrong:**
The app is built around the premise that `AudioSource.CAMCORDER` routes to the secondary (camera-side) microphone, bypassing the broken primary mic. `CAMCORDER` is documented as "microphone with the same orientation as the camera if available, otherwise the main device microphone" — but this is a *hint*, not a contract. The actual routing is decided by the OEM's audio HAL/mixer config (Samsung's `mixer_paths.xml` equivalent, tuning per SKU/carrier variant). On some Samsung firmware builds, `CAMCORDER` and `MIC` resolve to the same physical input, or `CAMCORDER` follows the *rear* camera even when the front camera (and its associated mic) is what's active, or the source silently falls back to the dead primary mic with no error — recording "succeeds" but produces silence or near-silence.

**Why it happens:**
`MediaRecorder.AudioSource` is an abstraction over a device-specific audio policy table that Android does not expose or guarantee at the API level. Developers treat the constant names (CAMCORDER, VOICE_RECOGNITION, MIC) as if they map to fixed physical mics, but the actual behavior is per-OEM, per-SoC, and sometimes per-firmware-revision. What worked on a Pixel or a different Samsung model is not guaranteed to work identically on the A15 5G.

**How to avoid:**
- Never hardcode a single "correct" source. Build the source-switcher as a first-class feature (already in scope), not a debug tool.
- On first run / setup, guide the user through a short test-record-and-playback loop across all candidate sources (MIC, CAMCORDER, VOICE_RECOGNITION, UNPROCESSED if available, DEFAULT) so they empirically find the one that captures real audio on their specific unit.
- Persist the user's chosen working source (SharedPreferences) so they don't have to re-discover it every session, but always leave "test other sources" reachable.
- Detect near-silent recordings heuristically (e.g., check RMS/amplitude of the captured buffer post-recording) and warn the user "this recording appears silent — try a different source" rather than silently producing an empty/junk file.

**Warning signs:**
- QA on the actual A15 5G unit shows a source that *should* work (per docs) produces silence.
- Playback of a "successful" recording (no exception thrown) is inaudible.
- Behavior differs between two Samsung models used for testing.

**Phase to address:** Core recording phase (source selection + record). This is the central risk of the whole project — should be the first thing validated end-to-end on the real device, before UI polish or sharing features.

---

### Pitfall 2: MediaRecorder state-machine crashes from missing lifecycle guards

**What goes wrong:**
`MediaRecorder` enforces a strict internal state machine (Idle → Initialized → DataSourceConfigured → Prepared → Recording → [Paused] → Stopped). Calling `stop()` without a prior successful `start()`, calling `start()` twice, calling `reset()`/`release()` out of order, or calling `setAudioSource()` after `prepare()` throws `IllegalStateException` and crashes the app. This is especially likely here because: (a) users will be rapidly switching audio sources to test them (start/stop/reconfigure cycles), (b) a failed `start()` (e.g., mic busy) can leave the recorder in an ambiguous state if the catch block doesn't explicitly `reset()` before reuse, and (c) rotation/lifecycle events (activity recreated mid-recording) can call `stop()` on a recorder that never successfully started.

**Why it happens:**
Developers treat `MediaRecorder` like a simple flag-based object instead of a strict FSM. The temptation to reuse a single `MediaRecorder` instance across multiple test-recordings (for the source-switching UX) without fully tearing down and recreating it between attempts is the most common source of this bug class.

**How to avoid:**
- Wrap every `start()`/`stop()` transition in try/catch, and on **any** failure during `start()`, immediately call `reset()` (never leave the recorder in an undefined state) before allowing another attempt.
- Prefer creating a fresh `MediaRecorder` instance per recording attempt rather than trying to reuse and reconfigure one instance — this sidesteps most state-machine edge cases at the cost of trivial object churn.
- Guard UI so the "record" button is disabled/debounced while a transition is in flight (prevents double-tap double-start).
- Handle Activity lifecycle explicitly: stop and release the recorder in `onPause`/`onStop` if a recording is active, and never call `stop()` unconditionally in `onDestroy` without checking recorder state first.
- Wrap `stop()` in try/catch too — a `stop()` called too soon after `start()` (very short recordings) can throw `RuntimeException` ("stop failed") even in the "correct" state, because the encoder had no data to finalize.

**Warning signs:**
- Crash logs showing `IllegalStateException` or `RuntimeException: stop failed` from `android.media.MediaRecorder`.
- Crashes correlated with fast source-switching taps or screen rotation during recording.
- Empty/zero-byte output files after a "successful" stop (symptom of the too-short-recording stop failure).

**Phase to address:** Core recording phase. Should have explicit test cases: rapid start/stop, source switch mid-setup, rotate during recording, minimum recording duration guard (e.g., require >300ms before allowing stop, or catch and discard gracefully).

---

### Pitfall 3: Microphone busy/in-use produces an unhandled crash instead of a user-facing message

**What goes wrong:**
If another app (phone call, voice assistant, another recorder, WhatsApp itself) holds the microphone, `MediaRecorder.start()` throws a `RuntimeException` with native error `-19` (or a generic "start failed") rather than a documented, catchable-by-name exception. Unhandled, this crashes the app outright — a particularly bad experience for a user who already has a broken mic and is relying on this app as their only way to send voice messages.

**Why it happens:**
This exception is undocumented at the Java API level (no `MediaRecorderException` subtype); it surfaces as a bare `RuntimeException`, so it's easy to miss in a quick try/catch that only catches `IllegalStateException`. Developers testing on a quiet device (no competing app claiming the mic) never encounter it, so it ships unhandled.

**How to avoid:**
- Catch `RuntimeException` (broad) around `start()`, not just `IllegalStateException`, and show a clear PT-BR message like "Não foi possível acessar o microfone — feche outros apps que possam estar usando-o (chamada, assistente de voz, etc.) e tente novamente."
- On API 24+ optionally register an `AudioManager.AudioRecordingCallback` to detect silent/interrupted recording configurations, though this is a nice-to-have, not a substitute for the try/catch.
- Test explicitly: start a phone call or voice assistant listening session, then try to record in-app, to confirm the failure path is graceful.

**Warning signs:**
- Crash reports showing `RuntimeException` from `MediaRecorder.start` with no custom handling.
- User reports of app crashing "randomly" (correlates with being on a call or using Bixby/Google Assistant).

**Phase to address:** Core recording phase, alongside state-machine hardening (Pitfall 2) — same error-handling pass.

---

### Pitfall 4: Manual APK missing v2 signature — installs on old devices, silently fails on the actual target device

**What goes wrong:**
If the build/sign script only produces a v1 (JAR) signature, or `apksigner` is invoked without explicitly enabling v2, the APK installs fine on an emulator running an older API level or in casual local testing, but fails on the real target — Android 11+ (which includes the A15 5G's Android 14) — with the generic, unhelpful "App not installed" dialog and no actionable error message for the end user.

**Why it happens:**
`apksigner`'s default signing behavior depends on the tool version and how it's invoked; a build script copy-pasted from an older tutorial may configure v1-only signing, or minSdk/targetSdk settings in the manifest may not trigger v2 automatically as they would under Gradle's AGP defaults. Because there's no Gradle safety net normalizing this, a hand-rolled script is the single most likely place this project fails silently.

**How to avoid:**
- Explicitly pass `--v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true` (or the tool's current equivalent flags) to `apksigner sign`. Do not rely on defaults.
- After signing, verify with `apksigner verify --print-certs -v <apk>` and confirm the output explicitly reports `Verified using v2 scheme: true` (and v3, if targeting broader compatibility).
- Add this verification step as a mandatory, scripted, non-optional step in the build pipeline — not a manual "remember to check" step — so it can never regress silently.
- Test the actual signed APK via real sideload install on the A15 5G (or equivalent Android 11+ device) before every release, not just via `adb install` on a dev machine that may mask install-time signature checks differently than the Package Installer UI does.

**Warning signs:**
- `apksigner verify -v` output missing "Verified using v2 scheme: true".
- Install succeeds via `adb install -r` (which can behave more permissively / give different errors) but fails via tapping the APK in a file manager.
- Generic "App not installed" with no detail dialog on the target device.

**Phase to address:** Build/packaging phase — must be validated before the sideload/distribution phase begins, ideally as an automated check in the build script itself (fail the build if v2 signing verification doesn't pass).

---

### Pitfall 5: resources.arsc compressed or misaligned — install fails on targetSdk 30+ with an obscure error

**What goes wrong:**
Targeting API 30+ (this project targets 34) requires `resources.arsc` inside the APK to be stored **uncompressed** and **4-byte aligned**. A hand-built zip/aapt2 pipeline that naively zips all files with default deflate compression, or that runs `zipalign` with the wrong flags/order, produces an APK that fails to install on the real device with an install error referencing `resources.arsc` alignment — a message a first-time reader of the raw `PackageManager` error won't immediately connect to "my zip step is wrong."

**Why it happens:**
`aapt2 link` typically stores `resources.arsc` uncompressed by default, so this pitfall usually gets introduced *later* in the pipeline — e.g., if any repackaging step re-zips the APK contents (adding classes.dex, resources, assets) using a generic zip tool that recompresses everything, or if `zipalign` is skipped or run with incorrect alignment flags. Because this project's build order is aapt2 → javac → d8 → **package** → zipalign → apksigner, the manual "package" step (assembling the final zip from aapt2's output plus classes.dex) is exactly where this regression is likely to sneak in.

**How to avoid:**
- When assembling the final APK zip, explicitly store `resources.arsc` with `zip -n resources.arsc` (or equivalent "don't compress this entry" flag) rather than a blanket recompress-everything approach — or better, use `aapt2 link`'s output structure directly without repackaging its arsc through a generic zip tool.
- Always run `zipalign -p -f -v 4 input.apk output.apk` **before** `apksigner` (see Pitfall 6 for ordering) — the `-p` flag pages-aligns shared library `.so` files too, which matters if any native libs are ever added later.
- Verify with `zipalign -c -v 4 <apk>` (check mode) as a mandatory pre-release gate — it will explicitly report misalignment.
- Test install on the actual Android 14 target device, not just older emulators, since this check is enforced at install time specifically on API 30+.

**Warning signs:**
- `zipalign -c -v 4` reports "NOT verified" for `resources.arsc` or other entries.
- Install fails on the A15 5G referencing resources.arsc / boundary alignment, even though the same APK "worked" via `adb install` on an older test device or emulator.

**Phase to address:** Build/packaging phase, same gate as Pitfall 4 — bundle these two verification checks (`apksigner verify` + `zipalign -c`) into one automated pre-release script step.

---

### Pitfall 6: Running zipalign and apksigner in the wrong order

**What goes wrong:**
With `apksigner` (the modern signer, unlike legacy `jarsigner`), the correct order is **zipalign first, then apksigner**. If the pipeline signs first and zipaligns afterward (a habit carried over from old jarsigner-era tutorials, where the order is reversed), the alignment step modifies the zip's byte layout *after* the signature was computed over it, invalidating the v2/v3 signature. The APK may appear to build without error but fails to install or fails signature verification.

**Why it happens:**
This is a classic copy-paste-from-outdated-tutorial trap — jarsigner-based guides (pre-APK Signature Scheme v2) explicitly say to zipalign *after* signing, and this instruction is still widely repeated online without the caveat that it flips for apksigner. Since this project uses `apksigner` exclusively, the old advice is actively wrong here.

**How to avoid:**
- Hardcode the pipeline order explicitly in the build script with a comment explaining *why* (so a future edit doesn't "fix" it back to the wrong order): unsigned APK → `zipalign` → `apksigner sign`.
- Never re-run zipalign, re-zip, or otherwise touch the APK's byte contents after `apksigner sign` has run — any post-sign modification invalidates the signature.
- Include `apksigner verify` as the final build step so an order mistake fails the build immediately rather than surfacing later as a mysterious install failure.

**Warning signs:**
- `apksigner verify` reports signature verification failure despite the sign step reporting "success".
- Install fails with a signature-related error rather than the resources.arsc alignment error (helps distinguish this pitfall from Pitfall 5).

**Phase to address:** Build/packaging phase, same automated gate as Pitfalls 4 and 5.

---

### Pitfall 7: MediaStore recording never becomes visible/shareable because IS_PENDING is never cleared, or the write happens before insert

**What goes wrong:**
The scoped-storage MediaStore flow requires: insert a placeholder row with `IS_PENDING=1` → obtain the `content://` Uri → open an `OutputStream` on that Uri and write the encoded audio → update the row to `IS_PENDING=0`. If any step is skipped or ordered wrong (e.g., writing to a raw file path first and inserting into MediaStore as an afterthought, or forgetting to flip `IS_PENDING` back to 0), the resulting file is either invisible to other apps (stuck pending, sometimes auto-deleted by the system as an abandoned pending file), or worse — `MediaRecorder.setOutputFile()` needs a `FileDescriptor`, not a raw path, when writing through a MediaStore-obtained Uri, and mishandling this produces silent failures or IO exceptions that are easy to swallow.

**Why it happens:**
Scoped storage's MediaStore API is meaningfully different from the pre-Android-10 direct-file-path world that most tutorials and Stack Overflow answers still show. Developers instinctively reach for `new File(path)` habits. Additionally, `MediaRecorder.setOutputFile(FileDescriptor)` (needed here, since output must go through the ContentResolver-obtained Uri) behaves slightly differently from `setOutputFile(String path)` in terms of when the file is flushed/finalized — the recorder must fully `stop()` before the FD-backed data is guaranteed complete, which interacts with Pitfall 2's stop-timing concerns.

**How to avoid:**
- Follow the exact sequence: `ContentValues` (DISPLAY_NAME, MIME_TYPE `audio/mp4` or similar, RELATIVE_PATH e.g. `Music/MicAlternativo`, `IS_PENDING=1`) → `contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)` → get `ParcelFileDescriptor` via `contentResolver.openFileDescriptor(uri, "w")` → pass that FD to `mediaRecorder.setOutputFile(pfd.getFileDescriptor())` → after successful `stop()` and `release()`, update the same row with `IS_PENDING=0` via `contentResolver.update(uri, clearedValues, null, null)`.
- Wrap the whole flow so that any failure mid-recording (crash, exception) still attempts to either clean up the pending row (delete it) or clear IS_PENDING with whatever partial data exists — never leave orphaned pending rows, which clutter the user's Music folder view and confuse the "which recording is mine" UX.
- Test explicitly: after recording, verify the file shows up in a file manager / music player app (proves IS_PENDING was cleared and RELATIVE_PATH is sane) before considering the recording feature "done."

**Warning signs:**
- Recorded files don't appear in Files app / any music player even though the app reports "saved successfully."
- `IS_PENDING` visible as still `1` when querying MediaStore for the row.
- Orphaned rows accumulate over multiple test recordings (visible via a MediaStore query or Files app "recently deleted"/pending view).

**Phase to address:** Save/storage phase (MediaStore integration) — verify with an on-device check, not just "insert didn't throw."

---

### Pitfall 8: Sharing to WhatsApp crashes with FileUriExposedException or fails silently due to missing URI permission grant

**What goes wrong:**
Building the share `Intent` with a raw `file://` Uri (e.g., from a leftover File object reference instead of the MediaStore `content://` Uri) throws `FileUriExposedException` and crashes on Android 7.0+ — which is all target devices here. Separately, even when using the correct `content://` Uri, forgetting `intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)` means WhatsApp's process may be denied read access to the Uri (`SecurityException` on WhatsApp's side, or the share silently doesn't attach the file), since granting access to a content Uri across app boundaries isn't automatic just because the Uri is well-formed.

**Why it happens:**
Since this project deliberately uses MediaStore's own `content://` Uri (from the insert step, per the project's Key Decisions) rather than a FileProvider, it's tempting to assume "it's already a content Uri, no extra permission needed" — but the permission grant flag is still required for `ACTION_SEND`/`EXTRA_STREAM` regardless of which mechanism produced the content Uri, because the receiving app runs in a different process/UID and needs an explicit URI permission grant to read it.

**How to avoid:**
- Always build the share intent as: `Intent(ACTION_SEND)` → `type = "audio/mp4"` (matching the actual output format) → `putExtra(EXTRA_STREAM, mediaStoreUri)` → `addFlags(FLAG_GRANT_READ_URI_PERMISSION)` → optionally target WhatsApp's package explicitly (`setPackage("com.whatsapp")`) if the UX calls for a direct-to-WhatsApp button rather than a generic chooser.
- Never construct or hold onto a `file://` Uri anywhere in the sharing code path — the MediaStore Uri obtained at insert time is the single source of truth for the shareable reference.
- Test the actual share action on-device (not just that the intent doesn't crash) — confirm the audio arrives in a WhatsApp chat and is tappable/playable, since a missing permission grant can fail silently on WhatsApp's end without necessarily crashing your app.

**Warning signs:**
- Crash log showing `FileUriExposedException`.
- Share sheet opens, WhatsApp is selected, but the message either doesn't send or arrives with no attachment / a broken attachment icon.

**Phase to address:** Share/WhatsApp integration phase — should include an explicit on-device manual test as acceptance criteria, since this class of bug won't show up in a compile-only check.

---

### Pitfall 9: Treating "don't ask again" RECORD_AUDIO denial as a normal denial

**What goes wrong:**
On first launch, if the user denies the RECORD_AUDIO permission and checks "don't ask again" (or denies twice, which Android treats as permanent denial), calling `requestPermissions()` again does nothing — no dialog appears, and `shouldShowRequestPermissionRationale()` now returns `false` (the same value it returns *before* ever asking, making the two states easy to conflate in naive code: "should I show rationale? no → just request" loops forever with no dialog and no feedback to the user, who is left thinking the app is broken).

**Why it happens:**
`shouldShowRequestPermissionRationale()` returning `false` is ambiguous by design — it means either "never asked yet" or "permanently denied," and distinguishing them requires the app to track its own state (e.g., "have I already requested this permission once before") in SharedPreferences, since the OS API alone doesn't disambiguate.

**How to avoid:**
- Track locally whether a permission request has already been made once (a boolean flag persisted across app restarts).
- On denial, if `shouldShowRequestPermissionRationale()` is true, show an in-app rationale and re-request.
- If denial persists and the local flag shows this isn't the first request, or the request callback reports denial with `shouldShowRequestPermissionRationale()` now false after having previously been true, treat this as permanent denial: show a screen explaining the permission is required, with a button that opens `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` (deep-links to this app's system permission screen) so the user can manually grant it — since in-app re-prompting is no longer possible.
- Write this flow in clear PT-BR: explain *why* the mic permission matters for this specific app's purpose (person has a broken mic, this is their workaround) since a generic "grant permission" message may not motivate a user who's already frustrated with their broken hardware.

**Warning signs:**
- Tapping "record" after a denial does nothing (no dialog, no error) — the classic symptom of not handling the permanent-denial branch.
- User reports "the app doesn't work" with no crash — permission silently blocking all functionality.

**Phase to address:** Permissions/onboarding phase — should be one of the very first flows implemented and tested (deny once, deny twice, verify Settings deep-link works), since it gates every other feature.

---

### Pitfall 10: Sideload install blocked on One UI 6 despite "correct" APK — Auto Blocker and per-source unknown-app permission confusion

**What goes wrong:**
Samsung's One UI 6 (on the A15 5G, Android 14) introduced "Auto Blocker," a security feature that can hard-block installation of apps from unauthorized/unknown sources even when the specific source app (file manager, browser) has been granted the "Install unknown apps" permission. Users following generic Android sideload instructions (enable unknown sources for the browser/file manager) may still hit a block with no clear explanation, because Auto Blocker is a separate, additional gate specific to recent Samsung firmware. Separately, "install unknown apps" itself is granted per source-app, not device-wide — a user who grants it to their file manager but downloads the APK via a different app (e.g., Chrome vs Samsung Internet vs a messaging app) will be blocked again and needs to re-grant for that specific app.

**Why it happens:**
This is Samsung-specific security hardening layered on top of stock Android's per-app unknown-sources model; it's not documented in generic Android sideload tutorials, and the underlying OS-level API (`REQUEST_INSTALL_PACKAGES`) doesn't reflect Auto Blocker's independent veto.

**How to avoid:**
- Write PT-BR sideload instructions that explicitly cover both gates: (1) enabling "Instalar apps desconhecidos" for whichever specific app the user uses to open the downloaded APK (Settings > Segurança e privacidade > Mais configurações de segurança > Instalar apps desconhecidos), AND (2) checking/disabling Auto Blocker if install is still refused (Settings > Segurança e privacidade > Auto Blocker), since One UI 6 ships with this enabled by default on many units.
- Include a troubleshooting section in the distribution instructions specifically for "instalação bloqueada mesmo após permitir fontes desconhecidas" pointing at Auto Blocker.
- Since exact menu paths/wording can vary slightly by One UI sub-version (6.0 vs 6.1) and region, phrase instructions with both the setting name and a fallback ("procure por 'Instalar apps desconhecidos' ou 'Fontes desconhecidas' nas configurações de Segurança").

**Warning signs:**
- User reports install fails/is blocked with no APK-side error (signature verifies fine, resources.arsc aligned, etc.) — points to an OS-level gate rather than a build issue.
- Testing on a fresh/reset A15 5G shows Auto Blocker enabled by default.

**Phase to address:** Distribution/sideload instructions phase — should be validated by actually performing a clean sideload on a real (or freshly reset) One UI 6 device, not assumed from generic Android docs.

---

### Pitfall 11: Google Play Protect scan/warning treated as a build defect instead of expected sideload friction

**What goes wrong:**
Even with a correctly v2-signed, properly built APK, Google Play Protect performs real-time code-level scanning of sideloaded APKs and may show a warning ("Apps from unknown developers can sometimes be unsafe," or an "App scan recommended" prompt) or, in some cases, hard-block installation of apps it flags heuristically — completely independent of whether the app is actually malicious or correctly built. A first-time user (or the developer during testing) may misread this as evidence the APK itself is broken and waste time re-debugging the build pipeline.

**Why it happens:**
Play Protect's scanning heuristics run regardless of Play Store distribution and can flag small, low-reputation, unsigned-by-a-known-developer apps (exactly what a solo-built utility APK looks like) even when there's nothing actually wrong with them. This is expected friction for any sideloaded app from a new/unknown signer, not a defect signal.

**How to avoid:**
- Set expectations in the distribution instructions: mention that Play Protect may show a scan warning during install and that this is normal for sideloaded apps not distributed via Play Store; give the exact tap sequence to proceed ("Mais detalhes" → "Instalar mesmo assim" or equivalent).
- Do not chase "fixing" this warning via build changes — it's not something a hand-built APK's signing/packaging can resolve (short of Play Store distribution or a paid code-signing reputation service), so don't burn build-phase time here.
- Keep this as a documented, expected step, not a bug in the tracker.

**Warning signs:**
- Time spent re-verifying signature/build correctness in response to a Play Protect warning when `apksigner verify` and `zipalign -c` both already pass.

**Phase to address:** Distribution instructions phase — document as expected UX, verify wording covers it, no code-side action needed.

---

## Technical Debt Patterns

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|----------|-------------------|-----------------|------------------|
| Reusing a single `MediaRecorder` instance across multiple test-recordings instead of recreating per attempt | Slightly less object churn/code | State-machine bugs (Pitfall 2) that are hard to reproduce/debug later | Never — recreate per recording attempt from day one |
| Skipping the automated `apksigner verify` + `zipalign -c` gate in the build script, checking "by eye" instead | Faster iteration early on | Silent regression to v1-only signing or misalignment right before a release, caught only by end-user install failure | Never for a release build; acceptable only for the very first local dev-only smoke test before signing is wired up |
| Hardcoding CAMCORDER as the only source without building the source-switcher UI first | Faster to a "recording works on my device" demo | Doesn't work for the actual target device if OEM routing differs from assumption; this is the core premise of the app, so shortcutting it undermines the whole project | Never — the switcher is not optional polish, it's core to the value proposition |
| Skipping the IS_PENDING cleanup-on-failure path (leaving orphaned pending rows on crash) | Simpler happy-path code | Confusing clutter in the user's Music folder over repeated failed attempts, harder-to-debug "where did my recording go" reports | Acceptable to defer cleanup polish to a later pass, but must at minimum not corrupt/hide successful recordings |

## Integration Gotchas

| Integration | Common Mistake | Correct Approach |
|-------------|-----------------|-------------------|
| WhatsApp (via ACTION_SEND) | Assuming M4A will render as a voice-note waveform bubble like a native WhatsApp recording | Accept that M4A arrives as a playable audio/document attachment (tap-to-play), not the waveform UI — this is expected and explicitly acceptable per project scope, not a defect to chase by trying to produce Opus/OGG output |
| MediaStore | Writing to a raw file path and inserting into MediaStore as an afterthought (pre-scoped-storage habit) | Insert first (IS_PENDING=1) → write via the returned Uri's FileDescriptor → clear IS_PENDING=0 on success |
| apksigner / zipalign toolchain | Applying jarsigner-era ordering (sign then zipalign) | zipalign before apksigner, always; verify with `apksigner verify` and `zipalign -c` as build gates |

## Security Mistakes

| Mistake | Risk | Prevention |
|---------|------|------------|
| Losing or rotating the signing keystore between releases | Users can't install updates over the existing app (signature mismatch forces uninstall/reinstall, losing local recordings not yet shared) | Store the keystore securely and back it up outside the repo (never commit it), document this as a critical one-time setup step; project's Key Decisions already commits to "same keystore always" — protect that decision operationally |
| Granting overly broad URI permissions when sharing (e.g., `FLAG_GRANT_WRITE_URI_PERMISSION` when only read is needed) | Unnecessarily exposes write access to the shared audio file to the receiving app | Only grant `FLAG_GRANT_READ_URI_PERMISSION` for the WhatsApp share use case |

## UX Pitfalls

| Pitfall | User Impact | Better Approach |
|---------|-------------|-------------------|
| No feedback when a recording is silent/failed due to wrong mic source | User sends a silent/broken audio to someone via WhatsApp, only discovering the failure after the fact — especially painful for this app's exact use case (broken mic workaround) | Post-recording amplitude check + playback-before-send is already in scope; make silence detection an explicit, tested guard, not just "user should notice on playback" |
| Generic permission-denied dead-end with no path forward | User stuck unable to record with no idea why, no way to retry (Pitfall 9) | Explicit permanent-denial detection + Settings deep-link, worded for this app's specific broken-mic context |
| Sideload instructions that don't mention Auto Blocker | User gets stuck at "app not installed" despite following unknown-sources steps, may give up | Include Auto Blocker troubleshooting explicitly in PT-BR install instructions (Pitfall 10) |

## "Looks Done But Isn't" Checklist

- [ ] **Recording feature:** Often "works" in the emulator/dev device but not on the actual A15 5G with its specific broken-mic hardware quirk — verify on the real target device, not just any Android 14 device.
- [ ] **Build/signing pipeline:** Often reports "build succeeded" with no errors but produces a v1-only or misaligned APK — verify with `apksigner verify -v` and `zipalign -c -v 4`, not just "the script exited 0."
- [ ] **MediaStore save:** Often "saves successfully" (insert doesn't throw) but the file is stuck IS_PENDING or has a broken RELATIVE_PATH — verify the file is visible in a real Files app / music player, not just that no exception was thrown.
- [ ] **WhatsApp share:** Often "opens the share sheet successfully" but the attachment fails silently on WhatsApp's side due to a missing permission grant — verify the audio actually arrives and plays in a real WhatsApp chat, not just that the Intent was launched without crashing.
- [ ] **Sideload instructions:** Often written and "look complete" listing only the standard unknown-sources toggle, missing the Samsung-specific Auto Blocker gate — verify by performing an actual clean install on a real (or freshly reset) One UI 6 device.

## Recovery Strategies

| Pitfall | Recovery Cost | Recovery Steps |
|---------|-----------------|------------------|
| CAMCORDER doesn't route to working mic on target device | LOW | Source-switcher already covers this — user tries next source; no code change needed if switcher UI is built correctly |
| MediaRecorder state-machine crash shipped | LOW-MEDIUM | Patch release with try/catch + reset()-on-failure hardening; no data loss since crash happens before file finalization |
| Missing v2 signature shipped (users can't install) | MEDIUM | Re-sign with correct flags, cut a new release; existing users who already installed a v1-only build (if it somehow installed on an older device) will need a fresh install, not an update, since the signature identity may differ depending on what changed |
| Orphaned IS_PENDING rows accumulating | LOW | Add a cleanup routine (query MediaStore for the app's own stale pending rows older than N minutes, delete them) in a later release; not urgent, cosmetic only |
| Auto Blocker / Play Protect friction not documented | LOW | Update distribution instructions (README/Release notes) — no code change needed |

## Pitfall-to-Phase Mapping

| Pitfall | Prevention Phase | Verification |
|---------|-------------------|----------------|
| CAMCORDER/AudioSource doesn't route to working mic (Pitfall 1) | Core recording phase | Manual test-record-and-playback across all sources on the real A15 5G; confirm at least one source captures audible speech |
| MediaRecorder state-machine crashes (Pitfall 2) | Core recording phase | Test rapid start/stop, source-switch mid-setup, rotation during recording, very-short recordings — no crash in any case |
| Mic-busy unhandled crash (Pitfall 3) | Core recording phase | Test recording while a call/assistant is active; confirm graceful error message, no crash |
| Missing v2 signature (Pitfall 4) | Build/packaging phase | `apksigner verify -v` output explicitly confirms v2 (and v1) scheme verified; automated in build script |
| resources.arsc compression/alignment (Pitfall 5) | Build/packaging phase | `zipalign -c -v 4` passes with no "NOT verified" entries; automated in build script |
| zipalign/apksigner order (Pitfall 6) | Build/packaging phase | Same automated gate as Pitfall 4/5 — order encoded directly in build script with explanatory comment |
| MediaStore IS_PENDING/RELATIVE_PATH mishandling (Pitfall 7) | Save/storage phase | Recorded file visible in Files app / music player after save; IS_PENDING confirmed 0 via query |
| WhatsApp share FileUriExposedException / missing permission grant (Pitfall 8) | Share/WhatsApp integration phase | Manual on-device test: share reaches a real WhatsApp chat and is tappable/playable |
| Permanent RECORD_AUDIO denial not handled (Pitfall 9) | Permissions/onboarding phase | Manually deny permission twice, confirm app shows Settings deep-link path instead of silently doing nothing |
| Auto Blocker / per-source unknown-apps confusion (Pitfall 10) | Distribution/sideload instructions phase | Perform a real clean sideload install on a reset or unfamiliar One UI 6 device following only the written instructions |
| Play Protect scan warning mistaken for build defect (Pitfall 11) | Distribution/sideload instructions phase | Instructions explicitly mention and walk through the Play Protect prompt |

## Sources

- [MediaRecorder overview | Android media | Android Developers](https://developer.android.com/media/platform/mediarecorder) — MEDIUM confidence (official docs)
- [MediaRecorder.AudioSource | API reference | Android Developers](https://developer.android.com/reference/android/media/MediaRecorder.AudioSource) — MEDIUM confidence (official docs)
- [APK signature scheme v2 | Android Open Source Project](https://source.android.com/docs/security/features/apksigning/v2) — MEDIUM confidence (official docs)
- [apksigner | Android Studio | Android Developers](https://developer.android.com/tools/apksigner) — MEDIUM confidence (official docs)
- [zipalign | Android Studio | Android Developers](https://developer.android.com/tools/zipalign) — MEDIUM confidence (official docs)
- [Behavior changes: Apps targeting Android 14 or higher | Android Developers](https://developer.android.com/about/versions/14/behavior-changes-14) — MEDIUM confidence (official docs)
- [Foreground service types are required | Android Developers](https://developer.android.com/about/versions/14/changes/fgs-types-required) — MEDIUM confidence (official docs)
- [Developer Guidance for Google Play Protect Warnings | Google for Developers](https://developers.google.com/android/play-protect/warning-dev-guidance) — MEDIUM confidence (official docs, general Play Protect behavior; sideload-specific blocking details LOW)
- [Scoped Storage Stories: Storing via MediaStore — CommonsWare](https://commonsware.com/blog/2019/12/21/scoped-storage-stories-storing-mediastore.html) — MEDIUM confidence (respected Android community authority, cross-checked against official IS_PENDING semantics)
- [What is android.os.FileUriExposedException — Medium](https://medium.com/@ali.muzaffar/what-is-android-os-fileuriexposedexception-and-what-you-can-do-about-it-70b9eb17c6d0) — MEDIUM confidence (corroborated by CodePath/Commonsware sharing-intent guidance)
- [PSA: New Samsung phones block sideloading by default — Android Authority](https://www.androidauthority.com/enable-sideloading-one-ui-6-1-1-3463446/) — LOW confidence (community/press reporting on Samsung Auto Blocker; not officially documented by Samsung in a stable long-term URL, and menu paths may shift across One UI sub-versions)
- [How to Fix the Android MediaRecording Error: Start Failed -19 — CodingTechRoom](https://codingtechroom.com/question/android-mediarecording-error-start-failed-19-runtimeexception) — LOW confidence (community Q&A, undocumented native error code)
- [WhatsApp Audio Format: Opus, MP3 & AAC Explained — chattopdf.app](https://chattopdf.app/blog/whatsapp-audio-format) and related community threads on M4A-as-document behavior — LOW confidence (third-party blog/community observation, not official WhatsApp documentation, but directly consistent with the project's own stated acceptance criteria)
- [Issue on Android R — iBotPeaches/Apktool #2421](https://github.com/iBotPeaches/Apktool/issues/2421) and [ionic-team/capacitor #6794](https://github.com/ionic-team/capacitor/issues/6794) — MEDIUM confidence (multiple independent tool-maintainer issue threads corroborating the same official constraint)

---
*Pitfalls research for: Android mic-source-selection recorder + Gradle-less APK sideload on Samsung A15 5G (Android 14 / One UI 6)*
*Researched: 2026-07-28*
