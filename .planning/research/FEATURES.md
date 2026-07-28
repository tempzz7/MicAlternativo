# Feature Research

**Domain:** Android audio-recorder app with input-source selection (mic-workaround utility), PT-BR, no-root, share-to-WhatsApp
**Researched:** 2026-07-28
**Confidence:** MEDIUM (core Android platform mechanics HIGH/verified against official SDK reference; competitor feature claims and WhatsApp share behavior MEDIUM/LOW — vendor docs and community reports, not independently reproduced)

## Feature Landscape

### Table Stakes (Users Expect These)

Features users assume exist in any voice recorder app. Missing these makes the app feel broken or unusable — and for this project specifically, missing any of these defeats the Core Value ("record and send audio despite broken main mic").

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Record / Stop / Pause-resume (pause optional) | Baseline function of any recorder; users expect a single obvious record button and clear recording state | LOW | `MediaRecorder` (or `AudioRecord` if raw PCM control is needed) start/stop lifecycle; pause is Android 24+ only — treat as stretch, not table stakes |
| RECORD_AUDIO runtime permission flow with rationale | Android 6.0+ requires runtime request for RECORD_AUDIO (dangerous permission); apps that just crash or silently fail without explaining why feel broken | LOW | Request just-in-time (when user taps Record, not on app launch); show a plain-language PT-BR rationale first if `shouldShowRequestPermissionRationale()` is true; handle permanent denial by deep-linking to app settings |
| Input/audio-source selection (MIC, CAMCORDER, VOICE_RECOGNITION, VOICE_COMMUNICATION, DEFAULT) | This *is* the app's reason to exist — Easy Voice Recorder proves users with mic/hardware quirks actively look for and use a source picker (its Settings > Tuning > Microphone option is a known, used feature) | MEDIUM | `MediaRecorder.AudioSource` / `AudioRecord` — verified official constants (Android SDK reference): CAMCORDER = "microphone audio source with same orientation as camera if available, the main device microphone otherwise"; VOICE_RECOGNITION = tuned for speech, minimal AGC/noise suppression; VOICE_COMMUNICATION = echo cancellation + AGC for VoIP-style capture; MIC = generic baseline. For this project's exact hardware bug (bottom/main mic dead, camera-side mic OK), CAMCORDER is the primary target source, with MIC/VOICE_RECOGNITION/DEFAULT as fallback options to try since OEM (Samsung One UI) source-to-hardware-pin routing is not publicly guaranteed and can vary by device/Android version |
| Playback before sending | Users won't trust or send a recording blind — they need to confirm the workaround actually captured audio (this is the single most important trust-builder for a "does my broken mic even work now" app) | LOW | Standard `MediaPlayer` on the just-recorded file; show duration/waveform optional, simple play/pause is enough |
| Save to device storage / MediaStore | Recordings must persist and be visible outside the app (Files app, other players) or users feel the data is "trapped" | LOW–MEDIUM | `MediaStore.Audio` insert via `ContentResolver` gives a shareable `content://` URI for free (no FileProvider XML needed) — matches project's existing decision; minSdk 29 avoids legacy storage-permission complexity |
| Share to WhatsApp (and other apps) via system share sheet | The explicit core value ("enviar áudios pelo WhatsApp"); users expect one tap from "I have a recording" to "it's in WhatsApp" | LOW | `Intent.ACTION_SEND` with `EXTRA_STREAM` = MediaStore content URI, `type="audio/*"` (or specific mime e.g. `audio/mp4`/`audio/3gpp`), `FLAG_GRANT_READ_URI_PERMISSION`. **Important nuance (community-sourced, not WhatsApp-official):** WhatsApp delivers files shared via ACTION_SEND as a playable **audio/document attachment** with a waveform-like player bubble, not as a native voice-note recorded in-app; this is functionally what the user needs (a "tocável" audio) and is the same mechanism Samsung Voice Recorder and Easy Voice Recorder use, but do not market this as "genuine WhatsApp voice message" — it plays back like one but is technically an audio attachment |
| List of past recordings | Users record more than once (testing sources, retries); need to find/replay/re-share previous recordings, not just the last one | LOW | Simple list backed by MediaStore query filtered to the app's recordings; rename/delete are natural companions |
| Recording list actions: delete, rename, re-share | Once a list exists, users expect to manage entries (Samsung Voice Recorder and Easy Voice Recorder both support this baseline) | LOW | Standard `RecyclerView`/list + `ContentResolver.delete`/`update` |
| Visible recording state / basic level indicator | Users need feedback that the mic is actually picking up sound (timer + simple amplitude bar), especially critical here because the whole point is diagnosing "is this source capturing audio at all" | LOW–MEDIUM | `MediaRecorder.getMaxAmplitude()` polled on a timer is the cheapest implementation; doubles as the mechanism for the auto-detect differentiator below |

### Differentiators (Competitive Advantage)

Features that set this product apart from generic recorders. Not required for a recorder to function, but this is where the app's specific value over Easy Voice Recorder / Samsung Voice Recorder lives — because generic recorders make you *manually* discover which source works, in English-first UX, with no awareness of this specific hardware failure mode.

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Auto-detect working mic (test each `AudioSource`, measure amplitude, recommend best) | Removes the trial-and-error burden entirely — the single biggest UX improvement over Easy Voice Recorder's manual "Settings > Tuning > Microphone" picker; directly targets a non-technical user who doesn't know what "CAMCORDER" even means | MEDIUM–HIGH | Sequentially open a short (~1–2s) `AudioRecord` session per candidate source, read `getMaxAmplitude()` / buffer RMS, rank sources by signal level, auto-select the best and remember the choice (SharedPreferences) so the user doesn't repeat the test every time. Must handle sources throwing on `startRecording()` (some OEMs restrict CAMCORDER/VOICE_COMMUNICATION) and skip gracefully. This is genuinely differentiating — no competitor found does this automatically |
| PT-BR guidance/explainer about *why* the main mic fails and what this app does about it | Directly addresses the emotional/trust gap: a user who thinks their phone is broken needs reassurance this is a known, common hardware issue (dirty/damaged bottom mic) and that switching source is a legitimate workaround, not a hack that will make things worse | LOW | Static onboarding/help screen in Portuguese; explicitly set expectations (works for recording/WhatsApp uploads; does **not** fix phone calls — see anti-feature below). High value, near-zero engineering cost — should ship in MVP |
| One-tap "share to WhatsApp" shortcut (vs generic share sheet) | Saves a step for the single most common destination; user journey is explicitly "record → send via WhatsApp," so surfacing a dedicated WhatsApp button (in addition to the generic share sheet) reduces friction from "share → find WhatsApp in list → pick contact" | LOW | Detect WhatsApp package (`com.whatsapp` / `com.whatsapp.w4b` for Business) via `PackageManager`, and if installed, show a direct-launch button alongside (not instead of) the generic `ACTION_SEND` chooser as fallback for users without WhatsApp or who want another app |
| Persisted "this source works on my phone" preference | Once auto-detect (or manual testing) finds the working source, defaulting to it on next launch removes repeated friction — turns a one-time diagnostic into a permanent fix | LOW | SharedPreferences flag; still expose manual override in case the user later fixes the mic or switches phones |
| Visual/textual labeling of sources in plain PT-BR ("Microfone de trás/vídeo" instead of "CAMCORDER") | Generic recorders expose raw Android technical names or vague "Main/Bluetooth" labels; this app's users are specifically non-technical people diagnosing a hardware defect, so clear source labeling *is* part of the differentiation, not cosmetic polish | LOW | Map each `AudioSource` enum to a short, honest PT-BR label + one-line description of what it usually captures |

### Anti-Features (Commonly Requested, Often Problematic)

Features that seem good but create disproportionate complexity, false expectations, or are technically impossible within this project's stated constraints (no root, no external services, single-purpose scope).

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|------------------|-------------|
| Reroute the mic used during phone calls | "If it fixes recording, it should fix calls too" — natural user assumption | Call audio is routed by the modem/telephony HAL, entirely outside app-level `AudioSource` control; **impossible without root**, already correctly listed Out of Scope in PROJECT.md | Document workaround in-app: use viva-voz (speakerphone), a wired/Bluetooth headset mic, or get the mic physically repaired (cheap part) |
| Reroute the mic WhatsApp itself uses when recording a native voice note in-app | Users may assume "fix my mic" means fix it everywhere, including inside WhatsApp's own recorder | Android does not allow one app to intercept/redirect another app's audio capture without root/Accessibility-level hacks that are fragile and policy-risky; also explicitly Out of Scope in PROJECT.md | This app becomes the *recording* tool; the resulting file is *shared into* WhatsApp rather than recorded inside it — reframe the user journey accordingly (and say so plainly in onboarding) |
| Cloud sync / backup / account system | "What if I lose my recordings" / feels like a modern app should have accounts | Directly conflicts with the project's "100% offline, no data leaves the device" constraint; adds auth, backend, privacy-policy, and storage-cost surface for a single-purpose local utility | Recordings already persist in MediaStore, visible in Files/Gallery-style apps; user can manually back up via their own cloud app (Drive, etc.) if desired — not this app's job |
| Audio editing suite (trim, cut, merge, noise reduction/denoise, effects) | Feels like "the next feature" once recording works; competitors like Easy Voice Recorder Pro do offer some editing | Large complexity jump (waveform editing UI, audio processing) for a use case that's fundamentally "record once, send once"; scope creep away from the core value proposition | If trimming is ever needed, defer to v2+ only if users explicitly ask; in the meantime instruct users to just re-record if a take is bad |
| Background/continuous recording, scheduled recording, call recording | Common "power features" of full-featured recorder apps (Easy Voice Recorder, Samsung Voice Recorder both support some of this) | Unrelated to the specific problem (broken mic on manual voice notes); call recording is also legally sensitive in many jurisdictions and technically entangled with the "can't reroute call mic" limitation | Not in scope; app is single-purpose: manual record → review → share |
| Multiple audio formats/quality/bitrate settings, advanced encoder configuration | Audiophile/power-user expectation from full recorder apps | Adds UI complexity and decision fatigue for a non-technical target user whose only goal is "make a WhatsApp-sendable clip"; risks producing files WhatsApp handles poorly | Ship one sane default format/quality (e.g., AAC in M4A container, mono, voice-appropriate bitrate) that is known to share cleanly to WhatsApp; do not expose format pickers in MVP |
| In-app contact picker / send directly to a specific WhatsApp contact | "One-tap share to WhatsApp" could be over-engineered into "one-tap send to Mom" | WhatsApp does not expose a public, reliable API for pre-selecting a specific chat/contact from a third-party share intent (it opens WhatsApp's own contact/chat picker after ACTION_SEND) — attempting to bypass this is unsupported/fragile and breaks on WhatsApp updates | Launch WhatsApp (or the generic chooser) and let WhatsApp's own native contact-selection UI take over — this is already "one tap" from the app's perspective |
| Bluetooth microphone input support | Present in Easy Voice Recorder as a paid/pro feature; seems like a natural extension of "input source selection" | Out of scope for this specific hardware-defect workaround (the working mic is *internal*, not external); adds Bluetooth SCO complexity, permissions (BLUETOOTH_CONNECT on Android 12+), and pairing UX for a use case that doesn't need it | If the user has an external/BT mic, that's a separate, already-solved problem (any recorder handles it); this app's differentiation is specifically internal-source switching |

## Feature Dependencies

```
RECORD_AUDIO permission flow
    └──requires──> (nothing; foundational, must ship first)

Record/Stop (single source, e.g. CAMCORDER default)
    └──requires──> RECORD_AUDIO permission flow

Input/audio-source selection (manual picker)
    └──requires──> Record/Stop
    └──enhances──> Record/Stop (turns "one fixed source" into "user-chosen source")

Playback
    └──requires──> Record/Stop (needs a file to exist)

Save to MediaStore
    └──requires──> Record/Stop
    └──enables──> Share to WhatsApp (content:// URI comes from this)
    └──enables──> Recording list

Share to WhatsApp (generic ACTION_SEND)
    └──requires──> Save to MediaStore

One-tap WhatsApp shortcut
    └──requires──> Share to WhatsApp (generic ACTION_SEND)
    └──enhances──> Share to WhatsApp

Recording list (with delete/rename/re-share)
    └──requires──> Save to MediaStore

Auto-detect working mic
    └──requires──> Input/audio-source selection (needs the enum of sources to iterate)
    └──requires──> Visible recording state / amplitude meter (reuses getMaxAmplitude() plumbing)
    └──enhances──> Input/audio-source selection (replaces manual trial-and-error with automation)

Persisted "working source" preference
    └──requires──> Input/audio-source selection (manual) OR Auto-detect working mic (either can produce the answer to persist)

PT-BR guidance/explainer
    └──requires──> (nothing; static content, can ship independently, ideally alongside permission flow)

PT-BR source labeling
    └──enhances──> Input/audio-source selection
    └──enhances──> Auto-detect working mic (surfaces which source won and why, in plain language)
```

### Dependency Notes

- **Input/audio-source selection requires Record/Stop:** you cannot meaningfully offer a source picker without an underlying record pipeline that accepts an `AudioSource` parameter — this should be built together, not sequenced as separate phases, since the recorder must be source-parameterized from day one (retrofitting source-switching onto a hardcoded-source recorder is wasted work).
- **Share to WhatsApp requires Save to MediaStore:** `ACTION_SEND` needs a stable, permission-granted `content://` URI; MediaStore insert is what produces that URI, so storage must land before sharing is testable end-to-end.
- **Auto-detect enhances rather than replaces manual selection:** OEM audio routing is not perfectly predictable across devices/Android versions (the CAMCORDER doc says "if available, main device mic otherwise" — a conditional the app cannot introspect ahead of time), so auto-detect's ranking should remain overridable by a manual picker as a fallback/escape hatch, not a hard gate.
- **PT-BR explainer conflicts with nothing** and has no dependencies — it's pure content and should ship in the very first testable build since it's the cheapest trust-building feature available.
- **One-tap WhatsApp shortcut enhances, does not replace, the generic share sheet:** users without WhatsApp installed (or wanting another app) still need the standard `ACTION_SEND` chooser as a fallback; building the shortcut as the *only* path would be a regression for edge cases.

## MVP Definition

### Launch With (v1)

Minimum viable product — proves the core value: "grave com o mic que funciona, envie pelo WhatsApp."

- [ ] RECORD_AUDIO permission request with clear PT-BR rationale — nothing works without this
- [ ] Record / Stop with CAMCORDER as default source — the specific fix for this user's diagnosed hardware defect
- [ ] Manual source picker (MIC, CAMCORDER, VOICE_RECOGNITION, VOICE_COMMUNICATION, DEFAULT) with plain PT-BR labels — covers devices/situations where CAMCORDER isn't the magic answer
- [ ] Playback of the last recording — the trust check that the workaround actually worked
- [ ] Save to MediaStore — required for both persistence and sharing
- [ ] Share via system `ACTION_SEND` (generic chooser, WhatsApp selectable) — the explicit core value
- [ ] Minimal recording list (at least "last recording," ideally simple list) — without this, testing multiple sources produces orphaned, unfindable files
- [ ] Short PT-BR onboarding/explainer screen — sets correct expectations (fixes recording/sharing, not calls; explains why the bug happens) at near-zero cost

### Add After Validation (v1.x)

Features to add once the core loop (record → play → send) is confirmed working on the actual target device (A15 5G) and, ideally, a couple of other devices/users.

- [ ] Auto-detect working mic (amplitude-based source ranking) — add once manual selection has validated which sources actually matter in practice; automating before validating risks automating the wrong heuristic
- [ ] Persisted "working source" preference — trivial once auto-detect or repeated manual selection exists
- [ ] One-tap WhatsApp shortcut button — nice friction-reducer, but the generic share sheet already gets the user to WhatsApp in 2 taps; add once the base flow is proven
- [ ] Delete/rename on recording list — add once users are actually accumulating recordings worth managing
- [ ] Visible amplitude/level meter during recording — useful polish and doubles as groundwork for auto-detect

### Future Consideration (v2+)

Features to defer until the core mic-workaround value is validated across more devices/users — most of these expand scope beyond "single-purpose workaround utility."

- [ ] Trim/basic edit before sending — only if users request it; adds real UI/processing complexity
- [ ] Multiple format/quality presets — only if WhatsApp compatibility issues surface with the default format on some devices
- [ ] Direct-to-contact share (if WhatsApp/Android ever exposes a supported API) — currently not feasible; revisit if platform changes
- [ ] Support for other OEM devices' known mic-defect patterns (beyond Samsung/CAMCORDER) — expand PT-BR guidance content as real-world reports come in

## Feature Prioritization Matrix

| Feature | User Value | Implementation Cost | Priority |
|---------|------------|---------------------|----------|
| RECORD_AUDIO permission flow | HIGH | LOW | P1 |
| Record/Stop (CAMCORDER default) | HIGH | LOW | P1 |
| Manual audio-source picker | HIGH | MEDIUM | P1 |
| Playback | HIGH | LOW | P1 |
| Save to MediaStore | HIGH | LOW–MEDIUM | P1 |
| Share to WhatsApp (generic ACTION_SEND) | HIGH | LOW | P1 |
| Minimal recording list | MEDIUM | LOW | P1 |
| PT-BR onboarding/explainer | HIGH | LOW | P1 |
| Auto-detect working mic | HIGH | MEDIUM–HIGH | P2 |
| Persisted working-source preference | MEDIUM | LOW | P2 |
| One-tap WhatsApp shortcut | MEDIUM | LOW | P2 |
| Delete/rename on list | LOW–MEDIUM | LOW | P2 |
| Amplitude/level meter | MEDIUM | LOW–MEDIUM | P2 |
| Trim/edit | LOW | HIGH | P3 |
| Format/quality presets | LOW | MEDIUM | P3 |

**Priority key:**
- P1: Must have for launch (MVP)
- P2: Should have, add when possible (v1.x, post-validation)
- P3: Nice to have, future consideration (v2+)

## Competitor Feature Analysis

| Feature | Easy Voice Recorder | Samsung Voice Recorder | Our Approach |
|---------|---------------------|-------------------------|--------------|
| Input source selection | Yes — Settings > Tuning > Microphone ("Main"/"Bluetooth"); manual, requires user to know it exists and experiment | Not user-exposed as a source picker (uses default system mic pipeline) | Manual picker exposed prominently (not buried in settings) as the app's primary screen, plus PT-BR labels a non-technical user can understand |
| Auto-detect best source | No — fully manual, English-first settings menu | No — no source selection at all | Yes (P2) — amplitude-based auto-test/ranking; this is our clearest differentiator vs. both |
| Share to WhatsApp | Yes, via generic Android share sheet | Yes, via generic Android share sheet ("Share" on long-press) | Yes — generic share sheet (P1) + one-tap WhatsApp shortcut (P2) |
| Bluetooth mic input | Yes (Pro feature) | Not found in research | Explicitly out of scope — not the problem this app solves |
| Language / localization | English-first (rationale/settings found in English sources); unclear native PT-BR quality | Samsung's own app, likely well-localized to PT-BR as part of One UI, but generic (no hardware-defect awareness) | Native PT-BR throughout, plus problem-aware guidance content neither competitor offers |
| Recording list management | Yes — full-featured (rename, delete, folders in Pro) | Yes — full-featured, integrated with Samsung ecosystem (Notes, Calendar) | Minimal at MVP (P1), delete/rename added P2 — deliberately smaller scope |
| Editing (trim, effects, noise reduction) | Yes, especially Pro tier | Limited (Samsung's app added AI features like transcription/bookmark in recent One UI versions) | Explicitly deferred to v2+/anti-feature — not core to the mic-workaround use case |
| Hardware-defect awareness / guidance | None found — generic recorder positioning | None — Samsung's own app has no reason to acknowledge its own hardware might be defective | Core differentiator — explicit PT-BR content about the diagnosed bottom-mic failure and this app's role as a workaround |

## Sources

- [MediaRecorder.AudioSource | API reference | Android Developers](https://developer.android.com/reference/android/media/MediaRecorder.AudioSource) — official source list (page required JS render; content confirmed via mirror below) — **HIGH confidence** (official SDK reference)
- [MediaRecorder.AudioSource (mirror, full constant text)](https://stuff.mit.edu/afs/sipb/project/android/docs/reference/android/media/MediaRecorder.AudioSource.html) — verified CAMCORDER/MIC/VOICE_RECOGNITION/VOICE_COMMUNICATION/DEFAULT descriptions, cross-checked against independent WebSearch summary of the same API — **HIGH confidence**
- [MediaRecorder overview | Android media | Android Developers](https://developer.android.com/media/platform/mediarecorder) — general recorder lifecycle guidance — MEDIUM confidence (not independently re-fetched, but standard/stable API)
- [Request runtime permissions | Privacy | Android Developers](https://developer.android.com/training/permissions/requesting) — permission rationale/timing best practices — HIGH confidence (official guidance, well-established pattern)
- [Easy Voice Recorder Help | Digipom](https://www.digipom.com/easy-voice-recorder-help/) — vendor documentation of the Settings > Tuning > Microphone source picker — MEDIUM confidence (vendor-authored, not independently reproduced by installing the app)
- [Easy Voice Recorder — Google Play](https://play.google.com/store/apps/details?id=com.coffeebeanventures.easyvoicerecorder&hl=en_US) — store listing, feature claims — LOW–MEDIUM confidence (marketing copy)
- [How to record, play back, and share voice recordings on your Samsung Galaxy phone | Samsung](https://www.samsung.com/in/support/mobile-devices/how-to-record-play-back-and-share-voice-recordings-on-your-samsung-galaxy-phone/) — official Samsung support doc on Voice Recorder sharing flow — MEDIUM confidence (official but not WhatsApp-specific)
- WebSearch synthesis on WhatsApp ACTION_SEND behavior (Quora/community sources, SendMyVoice blog, AudioUtils blog) — LOW confidence (community-sourced, not WhatsApp-official docs, not independently reproduced end-to-end); flagged in-line where used and should be spot-checked manually during implementation/testing on the target device
- WebSearch synthesis on mic-test/level-meter apps (MicTester, Android Mic Test, Sound Meter, Decibel X) — LOW–MEDIUM confidence (store listings/marketing copy); used only to confirm the pattern ("test source + show amplitude") is a known, buildable UX, not for exact implementation details
- General PT-BR search on broken phone mic causes/workarounds (JBL blog, manualdousuario.net) — LOW confidence (consumer blog content); used only to corroborate that "different mics activate for calls vs. video vs. voice recording" is a commonly understood consumer-facing phenomenon in Brazil, supporting the value of in-app PT-BR guidance

---
*Feature research for: Android audio-recorder / mic-workaround utility, no-root, PT-BR, WhatsApp share*
*Researched: 2026-07-28*
