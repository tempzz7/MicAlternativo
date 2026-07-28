# Roadmap: MicAlternativo

## Overview

MicAlternativo goes from zero to an installable, signed Android APK that lets someone with a
broken primary microphone record audio through a working secondary source and send it over
WhatsApp. The journey starts by de-risking the hardest infrastructure piece — a Gradle-free
build/signing pipeline — before any app code exists, so every later phase has a reliable
"compile, sign, install" loop to test against. From there it builds the record→save loop on a
single hardcoded source (proving permissions and MediaStore scoped storage independently of
source-routing uncertainty), then layers on the app's actual reason to exist — the manual
audio-source picker — plus the PT-BR onboarding that sets correct expectations. Playback and
sharing come next, closing the loop from "recorded" to "heard on the other end via WhatsApp."
The roadmap ends with distribution hardening: a PT-BR sideload guide covering One UI 6's Auto
Blocker and Play Protect friction, and a versioned APK artifact ready to hand to the user.

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

- [ ] **Phase 1: Build Pipeline & Signing** - A signed, installable "Hello World" APK is produced by one script, with automated gates that guarantee it installs on Android 14
- [ ] **Phase 2: Permission & Core Record/Save Loop** - User grants mic permission and can record and save audio that reliably appears in the Files app, with graceful error handling
- [ ] **Phase 3: Audio Source Selection & Onboarding** - User can switch between mic sources with PT-BR labels, see a level meter to confirm capture, and understand what the app does/doesn't fix
- [ ] **Phase 4: Playback, History & Share to WhatsApp** - User can preview any recording, browse past recordings, and send one to WhatsApp as a playable audio message
- [ ] **Phase 5: Distribution Hardening** - User can download a versioned signed APK and sideload-install it on a Samsung One UI 6 device following a PT-BR guide

## Phase Details

### Phase 1: Build Pipeline & Signing
**Goal**: A signed, installable "Hello World" APK is produced by one script, with automated gates that guarantee it installs on Android 14
**Mode:** mvp
**Depends on**: Nothing (first phase)
**Requirements**: DIST-01, DIST-02, DIST-03
**Success Criteria** (what must be TRUE):
  1. Running `scripts/build.sh` with a single command produces a signed APK with no manual steps
  2. The build fails loudly (non-zero exit) if `apksigner verify` or `zipalign -c` do not pass — a broken APK can never be silently produced
  3. The APK installs successfully via sideload on the real Android 14 target device (A15 5G)
  4. Every build uses the same permanent project keystore, so a second build can update-install over the first without uninstalling
**Plans:** 2 plans

Plans:
- [ ] 01-01-PLAN.md — Walking Skeleton: fontes do app + scripts/build.sh (pipeline aapt2→javac→d8→zip→zipalign→apksigner) com gates e prova de mesma-keystore (DIST-01, DIST-02, DIST-03)
- [ ] 01-02-PLAN.md — Checkpoint humano: sideload do v0.1.0 + update-install do v0.1.1 no Samsung A15 5G real (SC3, DIST-03)

### Phase 2: Permission & Core Record/Save Loop
**Goal**: User grants mic permission and can record and save audio that reliably appears in the Files app, with graceful error handling
**Mode:** mvp
**Depends on**: Phase 1
**Requirements**: PERM-01, PERM-02, GRAV-01, GRAV-02, GRAV-04, STOR-01, STOR-02
**Success Criteria** (what must be TRUE):
  1. On first tap of Gravar, the user sees a PT-BR explanation and is prompted for RECORD_AUDIO permission
  2. If the user permanently denies the permission, the app explains why and offers a deep-link shortcut to the app's Settings screen
  3. User can start and stop a recording with one button, with a clearly visible recording/stopped state, using CAMCORDER as the default source
  4. A completed recording appears in the device's Files app / a music player immediately after saving (MediaStore pending-row flow finalized correctly)
  5. If recording fails mid-way (mic busy, unsupported source, native error), the app shows a clear PT-BR error message, recovers without crashing, and leaves no orphaned/invisible file behind
**Plans**: TBD
**UI hint**: yes

### Phase 3: Audio Source Selection & Onboarding
**Goal**: User can switch between mic sources with PT-BR labels, see a level meter to confirm capture, and understand what the app does/doesn't fix
**Mode:** mvp
**Depends on**: Phase 2
**Requirements**: FONTE-01, FONTE-02, FONTE-03, GRAV-03, ONBD-01
**Success Criteria** (what must be TRUE):
  1. User can pick between MIC, CAMCORDER, VOICE_RECOGNITION, VOICE_COMMUNICATION and DEFAULT from a picker visible on the main screen, not buried in settings
  2. Each source shows a plain PT-BR label and description instead of the raw constant name
  3. The last source chosen is remembered and becomes the default the next time the app opens
  4. While recording, the user sees a running timer and a simple level/amplitude indicator confirming the selected source is actually picking up sound
  5. A help/onboarding screen in PT-BR explains the broken-mic scenario, what the app fixes, and what it explicitly does not fix (phone calls, WhatsApp's own recorder)
**Plans**: TBD
**UI hint**: yes

### Phase 4: Playback, History & Share to WhatsApp
**Goal**: User can preview any recording, browse past recordings, and send one to WhatsApp as a playable audio message
**Mode:** mvp
**Depends on**: Phase 3
**Requirements**: PLAY-01, STOR-03, SHARE-01
**Success Criteria** (what must be TRUE):
  1. User can play and pause a recording right after making it, before deciding to share
  2. User can see a list of previously made recordings (queried from MediaStore) and play any of them
  3. User can share any recording — new or past — through the system share sheet, and it arrives in a real WhatsApp chat as a playable audio attachment
**Plans**: TBD
**UI hint**: yes

### Phase 5: Distribution Hardening
**Goal**: User can download a versioned signed APK and sideload-install it on a Samsung One UI 6 device following a PT-BR guide
**Mode:** mvp
**Depends on**: Phase 4
**Requirements**: DIST-04, DIST-05
**Success Criteria** (what must be TRUE):
  1. A PT-BR `INSTALAR.md` guide walks the user through enabling install-from-unknown-sources, working around Samsung's Auto Blocker, and dismissing the Play Protect warning
  2. A versioned APK (e.g. `MicAlternativo-v1.0.0.apk`) is available as a downloadable artifact in the project repo/release
  3. Following the guide on the real One UI 6 target device results in a successful clean install
**Plans**: TBD

## Progress

**Execution Order:**
Phases execute in numeric order: 1 → 2 → 3 → 4 → 5

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Build Pipeline & Signing | 0/? | Not started | - |
| 2. Permission & Core Record/Save Loop | 0/? | Not started | - |
| 3. Audio Source Selection & Onboarding | 0/? | Not started | - |
| 4. Playback, History & Share to WhatsApp | 0/? | Not started | - |
| 5. Distribution Hardening | 0/? | Not started | - |
