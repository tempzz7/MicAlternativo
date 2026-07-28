# Phase 1: Build Pipeline & Signing - Research

**Researched:** 2026-07-28
**Domain:** Gradle-less Android APK build pipeline (aapt2 → javac → d8 → zip → zipalign → apksigner) targeting Android 14, minSdk 29
**Confidence:** HIGH — every pipeline command in this document was executed successfully in this session against the actual SDK (build-tools 34.0.0), actual JDK (OpenJDK 21.0.11), and the actual project keystore, producing a signed APK that passes both gates. Negative tests (unsigned, misaligned, tampered APKs) were also executed to confirm gate exit-code behavior.

## Summary

The entire pipeline was proven end-to-end in this session: a manifest-only `aapt2 link` (no `aapt2 compile` needed), `javac -source 8 -target 8 -bootclasspath android.jar`, `d8 --release --min-api 29`, `zip -j` to insert `classes.dex`, `zipalign -f -p 4`, and `apksigner sign` with the real project keystore produced an 8.6 KB signed APK whose `apksigner verify` and `zipalign -c 4` gates both pass with exit 0, and whose `aapt2 dump badging` shows the correct package, versionCode/versionName, minSdk 29 / targetSdk 34, RECORD_AUDIO permission, and launchable MainActivity. All commands below are copy-ready and verified — the planner can transcribe them into `build.sh` with confidence.

**The single most important finding** (contradicts PITFALLS.md Pitfall 4's literal check): with minSdk 29, `apksigner` from build-tools 34.0.0 emits a **v3-only** signature — `Verified using v2 scheme: false`, `v3: true` — *even when `--v2-signing-enabled true` is passed explicitly* (verified empirically). This is correct, by-design behavior: apksigner selects schemes from the min-sdk range, v3 exists since Android 9, and every device that can install a minSdk-29 APK verifies v3. A gate that greps literally for `v2 scheme...: true` would **fail every valid build**. The DIST-02 gate must therefore assert `apksigner verify` exit 0 **plus** "v2 OR v3 scheme: true" — v3 satisfies the requirement's "v2+" wording.

Second-most important: even a manifest-only APK with an inline `android:label` **does contain a `resources.arsc`** (40 bytes, emitted by aapt2 as Stored/uncompressed — verified via `unzip -lv`). The targetSdk-30+ rule (arsc uncompressed + 4-byte aligned) therefore applies to this project. The verified `zip -j` insertion of `classes.dex` does **not** recompress existing entries, so the arsc stays Stored, and `zipalign` handles the alignment — the pipeline as designed is safe.

**Primary recommendation:** Implement `scripts/build.sh` exactly as the verified command sequence in "Code Examples", with gates checking exit codes directly (never through pipes — `cmd | head; echo $?` reports `head`'s exit, a trap hit during this research) and treating v3 as satisfying "v2+".

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

#### Keystore e segredos
- **D-01:** Usar a keystore permanente já gerada em `/home/temp/.micalternativo-keys/release.jks` (alias `micalternativo`, RSA 2048, validade ~30 anos). Credenciais em `/home/temp/.micalternativo-keys/keystore.env` (`STOREPASS=...`, `ALIAS=...`, chmod 600). NUNCA commitar keystore/senha (já coberto pelo `.gitignore`).
- **D-02:** `build.sh` lê `KEYSTORE_PATH` e `KEYSTORE_ENV` de variáveis de ambiente, com default para os caminhos acima. Documentar no script: perder a keystore = impossibilidade de publicar updates instaláveis por cima.

#### Interface do build
- **D-03:** Um único `scripts/build.sh` em bash com `set -euo pipefail`; sem Makefile. `ANDROID_HOME` configurável por env, default para o SDK instalado (`/tmp/claude-1000/-home-temp-problem/baf2ba44-b642-4c2a-955d-a883998e26e3/scratchpad/android-sdk`); o script falha com mensagem clara se as ferramentas não existirem.
- **D-04:** Versão em `version.properties` na raiz (`versionCode`, `versionName`); o script injeta no manifest via `aapt2 link --version-code/--version-name`. Artefato final: `dist/MicAlternativo-v{versionName}.apk`. Intermediários em `build/` (ambos gitignored).

#### App-esqueleto da Fase 1
- **D-05:** Pacote `br.com.micalternativo`. Uma `MainActivity` Java com UI 100% programática (TextView "MicAlternativo" + versão) — sem XML de layout, sem AndroidX.
- **D-06:** O `AndroidManifest.xml` já nasce na forma final: minSdk 29, targetSdk 34, `<uses-permission android:name="android.permission.RECORD_AUDIO"/>` declarado desde já (fases 2+ usam), `android:label="MicAlternativo"`. Ícone default do sistema na Fase 1 (ícone próprio é polish de fase posterior, não requisito).

#### Pipeline e gates
- **D-07:** Ordem fixa: `aapt2 link` (gera APK base com manifest+resources) → `javac` (release 8/`-bootclasspath android.jar`) → `d8` → adicionar `classes.dex` ao APK → `zipalign -f 4` → `apksigner sign`. **zipalign SEMPRE antes de apksigner** — assinatura v2/v3 cobre o layout exato de bytes.
- **D-08:** Gates obrigatórios no fim do build (falha ⇒ exit ≠ 0): `zipalign -c 4` e `apksigner verify --min-sdk-version 29` confirmando esquema v2+. Imprimir resumo (tamanho do APK, esquemas de assinatura, sha256).

### Claude's Discretion
- Flags exatas de cada ferramenta, layout interno do script (funções/etapas), mensagens de log, e se `aapt2 compile` é necessário (sem resources além do manifest, `link` direto pode bastar).
- Estrutura de diretórios de código: seguir proposta da pesquisa (`app/src/main/java/...`, `app/src/main/AndroidManifest.xml`, `scripts/`).

### Deferred Ideas (OUT OF SCOPE)
- Ícone/adaptive icon próprio — polish visual, tratar junto das fases de UI (3) ou distribuição (5)
- CI (GitHub Actions) para build automático — faz sentido junto da Fase 5 / v1.x quando o repo for publicado
- F-Droid / publicação pública — já registrado como DIST-06 (v2)
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| DIST-01 | `scripts/build.sh` produz o APK de forma reproduzível sem Gradle (aapt2 → javac → d8 → empacotar → zipalign → apksigner), com um comando | Full pipeline executed and verified this session; every command in "Code Examples" ran with exit 0 producing a valid signed APK. `aapt2 compile` confirmed unnecessary (manifest-only `link` works). |
| DIST-02 | O build tem gates automáticos: `apksigner verify` (v2+ presente) e `zipalign -c` passam ou o build falha — nunca gerar APK que "não instala" no Android 11+ | Gate exit codes verified positively AND negatively (unsigned → exit 1; genuinely misaligned → exit 1; tampered-after-sign → exit 1). Critical: with minSdk 29 the scheme is **v3-only by design** — gate must accept v3 as "v2+" (see Pitfall 1). |
| DIST-03 | APK é assinado com a keystore permanente do projeto (mesma assinatura em todos os releases, permitindo updates por cima) | Real keystore used successfully this session (`apksigner sign` exit 0). Cert verified: CN=MicAlternativo, RSA 2048, SHA384withRSA, valid 2026-07-28 → 2056-07-20 (~30 yrs), alias `micalternativo`, PKCS12. SHA-256 cert digest `b6966a76…8d6fe8` — same-cert signing across builds guarantees update-installs. |
</phase_requirements>

## Project Constraints (from CLAUDE.md)

Extracted from `./.claude/CLAUDE.md` (GSD-managed):
- **Tech stack:** Java puro + Android SDK, sem Gradle, sem dependências externas, sem AndroidX (UI programática).
- **Plataforma:** minSdk 29 / targetSdk 34.
- **Distribuição:** APK assinado sempre com a mesma keystore do projeto; instruções de sideload em PT-BR (fase 5).
- **Sem serviços externos:** app 100% offline.
- **Forbidden:** Gradle/AGP, AndroidX (qualquer `androidx.*`), legacy `aapt` v1, v1-only signing, zipalign após apksigner.
- **Workflow:** file changes only through GSD commands (this research is part of `/gsd-plan-phase`).

## Architectural Responsibility Map

This phase is build infrastructure, not a multi-tier app. Capabilities map to pipeline stages/owners:

| Capability | Primary Owner | Secondary | Rationale |
|------------|---------------|-----------|-----------|
| Manifest processing + version injection | `aapt2 link` (SDK build-tools) | `version.properties` + bash parsing | Only aapt2 can produce the binary AndroidManifest.xml; versions injected at link time, never hand-edited |
| Java → bytecode | `javac` (JDK 21, capped at Java 8) | — | Must compile against android.jar as bootclasspath so Android-absent APIs fail at compile time |
| Bytecode → DEX | `d8` (SDK build-tools) | — | Only supported dexer; `dx` is dead |
| APK assembly (dex insertion) | `zip -j` (host zip 3.0) | — | aapt2 link output has no dex; zip appends without touching existing entries (verified) |
| Alignment | `zipalign` (SDK build-tools) | — | Must run BEFORE signing (D-07); `-p` also page-aligns future .so files |
| Signing | `apksigner` (SDK build-tools) | keystore + env file | v2/v3 whole-file signatures; scheme selection is apksigner's job, not the script's |
| Quality gates | `build.sh` (bash exit-code checks) | `apksigner verify`, `zipalign -c`, `aapt2 dump badging` | Script is the build system — gates are its contract (DIST-02) |
| Secrets | `keystore.env` sourced into env, `--ks-pass env:` | — | Password never appears on argv/process list (verified apksigner supports `env:`) |
| On-device install proof | human + `adb install` | — | No device attached in this environment — requires `checkpoint:human-verify` |

## Standard Stack

Zero external packages. Everything is already installed and was exercised this session.

### Core

| Tool | Version (verified) | Purpose | Why Standard |
|------|--------------------|---------|--------------|
| aapt2 | build-tools 34.0.0 | Manifest compile+link, version injection, badging dump | Only supported resource compiler [VERIFIED: local toolchain execution] |
| javac | OpenJDK 21.0.11 | Compile Java → .class at Java 8 level | Host JDK; `-source 8 -target 8 -bootclasspath android.jar` verified producing major version 52 [VERIFIED: local toolchain execution] |
| d8 | build-tools 34.0.0 (R8 8.2.2-dev core) | .class → classes.dex | Official dexer; version string observed in error output [VERIFIED: local toolchain execution] |
| zip / unzip | Info-ZIP 3.0 (`/usr/bin/zip`) | Insert classes.dex into APK | Appends without recompressing existing entries — resources.arsc stays Stored [VERIFIED: local toolchain execution] |
| zipalign | build-tools 34.0.0 | 4-byte alignment + `-c` gate | Exit 1 on genuine misalignment (verified negatively) [VERIFIED: local toolchain execution] |
| apksigner | build-tools 34.0.0 | Sign (v3) + verify gate | Exit 1 on unsigned/tampered APK (verified negatively) [VERIFIED: local toolchain execution] |
| adb | platform-tools 1.0.41 | Device install (when device attached) | Present; **no device currently attached** [VERIFIED: local toolchain execution] |
| bash | 5.2.21 | `build.sh` | `set -euo pipefail` supported [VERIFIED: local toolchain execution] |
| android.jar | platforms/android-34 | Compile/dex classpath | Present at `$ANDROID_HOME/platforms/android-34/android.jar` [VERIFIED: local toolchain execution] |
| keystore | `/home/temp/.micalternativo-keys/release.jks` | Signing identity | PKCS12, alias `micalternativo`, RSA 2048, SHA384withRSA, valid to 2056-07-20; sign succeeded with it this session [VERIFIED: local toolchain execution] |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `javac -source 8 -target 8 -bootclasspath android.jar` | `javac --release 8 -classpath android.jar` | Also works (verified, major version 52) but resolves `java.*` against the JDK's ct.sym instead of android.jar — an Android-absent `java.*` API would slip through to d8/runtime. Bootclasspath variant is safer; its 3 "obsolete" warnings are suppressible with `-Xlint:-options`. |
| `zip -j` dex insertion | `aapt2 link -o dir --output-to-dir` + repack whole APK | Repacking risks recompressing resources.arsc with a generic zip tool — the exact regression PITFALLS.md warns about. `zip -j` append is verified safe. Don't repack. |
| `--ks-pass env:STOREPASS` | `--ks-pass file:...` | `file:` also keeps the password off argv, but `env:` composes naturally with sourcing `keystore.env` (which also carries `ALIAS`). Never use `pass:` (visible in `ps`). |

**Installation:** nothing to install — all tools present (see Environment Availability).

## Package Legitimacy Audit

**Not applicable.** This phase installs zero external packages (no npm/PyPI/crates, no Maven artifacts, no AndroidX). The toolchain is the pre-installed Android SDK + JDK + coreutils, all verified on disk. No slopsquatting surface exists.

## Architecture Patterns

### System Architecture Diagram (verified data flow)

```
version.properties ──(bash grep/cut parse)──► VERSION_CODE, VERSION_NAME
                                                     │
app/src/main/AndroidManifest.xml ────────────────────┤
                                                     ▼
                     [1] aapt2 link --manifest ... -I android.jar
                         --version-code --version-name  -o build/base.apk
                         (NO aapt2 compile step — verified unnecessary)
                                                     │
                             base.apk = {AndroidManifest.xml (deflated),
                                         resources.arsc (40 B, Stored)}
                                                     │
app/src/main/java/**/*.java                          │
        │                                            │
        ▼                                            │
[2] javac -source 8 -target 8                        │
    -bootclasspath android.jar → build/obj/*.class   │
        │                                            │
        ▼                                            │
[3] d8 --release --min-api 29 --lib android.jar      │
    --output build/dex  (dir MUST pre-exist!)        │
        │                                            │
        ▼                                            ▼
[4] cp base.apk unsigned.apk; (cd build/dex && zip -j unsigned.apk classes.dex)
                                                     │
                                                     ▼
[5] zipalign -f -p 4 unsigned.apk aligned.apk        (ALWAYS before signing)
                                                     │
                                                     ▼
[6] apksigner sign --ks $KEYSTORE_PATH --ks-key-alias $ALIAS
    --ks-pass env:STOREPASS --min-sdk-version 29 --out dist/MicAlternativo-v$V.apk
                                                     │
                          ┌──────────────────────────┴───────────────────────┐
                          ▼                                                  ▼
[7-GATE] apksigner verify --verbose --min-sdk-version 29    [8-GATE] zipalign -c 4
         exit 0 AND (v2:true OR v3:true) required            exit 0 required
                          │                                                  │
                          └───────────────┬──────────────────────────────────┘
                                          ▼
[9] Summary: size, sha256, scheme lines, aapt2 dump badging sanity
                                          ▼
              dist/MicAlternativo-v0.1.0.apk  ──(human)──► adb install -r / sideload on A15 5G
```

### Recommended Project Structure (per D-04 + Claude's-discretion layout from phase questions)

```
/home/temp/problem/
├── version.properties                 # versionCode=1 / versionName=0.1.0
├── app/
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml
│           └── java/
│               └── br/com/micalternativo/
│                   └── MainActivity.java
├── scripts/
│   └── build.sh                       # the whole pipeline, set -euo pipefail
├── build/                             # gitignored intermediates (obj/, dex/, *.apk)
└── dist/                              # gitignored final artifact MicAlternativo-v{versionName}.apk
```

### Pattern 1: Exit-code gates, never piped

**What:** Every gate checks the tool's own exit code directly (`if ! "$APKSIGNER" verify ...; then die; fi`). Never `tool | head; echo $?` — during this research that exact construct silently reported `head`'s exit 0 for a failing `apksigner verify`.
**When to use:** All gate steps in build.sh. With `set -euo pipefail`, a bare failing command already aborts; capture output to a file/variable when the text is needed for the scheme grep.

### Pattern 2: Secrets via sourced env file + `env:` password format

**What:** `set -a; source "$KEYSTORE_ENV"; set +a` then `--ks-pass env:STOREPASS`. Password never appears in argv (invisible to `ps`), never echoed. Verified working this session with the real keystore. [VERIFIED: local toolchain execution; `env:` format CITED: developer.android.com/tools/apksigner]
**When to use:** The sign step. Guard with a pre-check that `$KEYSTORE_PATH` and `$KEYSTORE_ENV` exist and that `STOREPASS`/`ALIAS` are non-empty after sourcing, failing with a clear PT-BR/EN message (D-03 style).

### Pattern 3: Preflight tool check

**What:** Before stage 1, resolve `ANDROID_HOME` (env override, default to the known SDK path), then verify each required binary exists (`aapt2`, `d8`, `zipalign`, `apksigner`, `android.jar`, `javac`, `zip`) and fail with a message naming the missing tool (D-03).
**Why:** The default SDK path lives in a session-scoped scratchpad directory — the single most likely thing to be wrong on a future run (see Assumptions A1).

### Anti-Patterns to Avoid

- **Grepping for `v2 scheme...: true` as the only pass condition:** fails every valid minSdk-29 build (verified — apksigner emits v3-only even with `--v2-signing-enabled true`). Accept v2 OR v3.
- **Repacking the aapt2 output with a fresh `zip -r`:** risks deflating resources.arsc (install failure on targetSdk 30+). Only *append* with `zip -j`.
- **Any modification after `apksigner sign`:** invalidates the signature (verified: appending one stored file → verify exit 1). The gates run on the final artifact and nothing touches it afterward.
- **`--release 8` together with `-bootclasspath`:** hard error `option --boot-class-path cannot be used together with --release` (verified).
- **Piping gate output before reading `$?`** (see Pattern 1).

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Binary AndroidManifest.xml encoding | Any XML→AXML converter | `aapt2 link` | Proprietary binary format; aapt2 also emits the required Stored resources.arsc |
| DEX generation | javac-bytecode tricks / dx | `d8 --release --min-api 29` | Only supported dexer; handles desugaring automatically if lambdas appear in later phases |
| APK signing | jarsigner, openssl | `apksigner` | v2/v3 are whole-file signatures over exact byte layout; jarsigner produces v1-only which Android 14 effectively rejects for this target |
| Alignment verification | Custom zip-offset parser | `zipalign -c 4` (exit code) | Verified to exit 1 on genuine misalignment; knows the stored-entry-only rule (compressed entries exempt) |
| Install-readiness metadata check | Manual zip inspection | `aapt2 dump badging` | One command yields package, versions, sdkVersion, permissions, launchable-activity |

**Key insight:** the build *script* is hand-rolled (that's the point — it replaces Gradle), but every *format-touching* operation must go through the official tool. The script's only jobs are sequencing, argument passing, and gate enforcement.

## Common Pitfalls

### Pitfall 1: The v2 gate trap (HIGHEST RISK for this phase)
**What goes wrong:** DIST-02 says "v2+ presente"; PITFALLS.md Pitfall 4 says confirm `Verified using v2 scheme: true`. But build-tools 34.0.0 apksigner with minSdk 29 emits **v3-only** — `v2: false, v3: true` — even when `--v2-signing-enabled true` is explicitly passed (verified twice this session, with and without the flag).
**Why it happens:** apksigner picks schemes from the min-sdk range [CITED: developer.android.com/tools/apksigner]; v3 exists since Android 9 [CITED: source.android.com/docs/security/features/apksigning], so no installable device (Android 10+) needs v2.
**How to avoid:** Gate = `apksigner verify --verbose` exit 0 AND output contains `v2 scheme (APK Signature Scheme v2): true` **OR** `v3 scheme (APK Signature Scheme v3): true`. v3 satisfies "v2+" (it is the successor scheme; Android 11+'s requirement is "v2 or newer").
**Warning signs:** build gate failing while `apksigner verify` exits 0.

### Pitfall 2: d8 does not create its output directory
**What goes wrong:** `d8 --output build/dex ...` fails with `Invalid output: build/dex — Output must be a .zip or .jar archive or an existing directory` (verified).
**How to avoid:** `mkdir -p build/dex` (and all of `build/`, `dist/`) at script start.

### Pitfall 3: Pipe swallows gate exit codes
**What goes wrong:** `apksigner verify apk | head -3; echo $?` printed `exit=0` for a failing verify during this research — `$?` was `head`'s.
**How to avoid:** Run gates bare (with `set -euo pipefail` they abort on failure) or capture to a file: `out=$("$APKSIGNER" verify --verbose ...)` then grep `$out`. Note `set -o pipefail` alone fixes the pipe case, but bare invocation is clearer.

### Pitfall 4: javac flag conflicts and warnings
**What goes wrong:** `--release 8` + `-bootclasspath` = hard error. `-source 8 -target 8` emits 3 "obsolete" warnings (non-fatal).
**How to avoid:** Use `-source 8 -target 8 -bootclasspath "$ANDROID_JAR" -classpath "$ANDROID_JAR"`; optionally `-Xlint:-options` to silence the obsolete warnings. Do NOT add `-Werror` (would turn the obsolete warnings fatal).

### Pitfall 5: Accidental recompression of resources.arsc during dex insertion
**What goes wrong:** A repack (`zip -r` of an extracted tree) deflates resources.arsc → install failure on targetSdk 30+.
**How to avoid:** Only append: `(cd build/dex && zip -j ../unsigned.apk classes.dex)` — verified to leave `resources.arsc  Stored  0%` intact. The manifest-only APK **does** contain resources.arsc (40 bytes), so this rule is live even in Phase 1.

### Pitfall 6: Trusting `zipalign -c` passing before the align step as proof
**What goes wrong:** The pre-alignment `unsigned.apk` happened to already pass `zipalign -c` in this session (offsets coincidentally aligned). A future layout change could break that silently if the `-f -p 4` step were dropped as "unnecessary".
**How to avoid:** Always run `zipalign -f -p 4` unconditionally (D-07); the `-c` gate runs on the FINAL signed artifact (verified: apksigner preserves alignment — signed APK passes `-c`).

### Pitfall 7: version.properties CRLF / whitespace
**What goes wrong:** A CRLF-saved properties file leaves `\r` in parsed values, corrupting `--version-name` and the artifact filename.
**How to avoid:** Parse with `grep -E '^versionCode=' | head -1 | cut -d= -f2 | tr -d '[:space:]'` (verified to handle CRLF and comments) and validate `[[ "$VERSION_CODE" =~ ^[0-9]+$ ]]`.

### Pitfall 8: adb install cannot be a scripted gate here
**What goes wrong:** Success criterion 3 (installs on the A15 5G) cannot be verified in this environment — `adb devices` shows no device attached (verified).
**How to avoid:** build.sh's gates prove install-*readiness* (signature scheme, alignment, badging). The actual device install is a `checkpoint:human-verify` task: user runs `adb install -r dist/MicAlternativo-v0.1.0.apk` (or sideloads) and confirms the app opens. A second build with bumped versionCode must update-install without uninstall (proves DIST-03).

## Code Examples

All commands below executed successfully in this session (exit 0 unless noted). Variables assumed:

```bash
ANDROID_HOME="${ANDROID_HOME:-/tmp/claude-1000/-home-temp-problem/baf2ba44-b642-4c2a-955d-a883998e26e3/scratchpad/android-sdk}"
BT="$ANDROID_HOME/build-tools/34.0.0"
ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
KEYSTORE_PATH="${KEYSTORE_PATH:-/home/temp/.micalternativo-keys/release.jks}"
KEYSTORE_ENV="${KEYSTORE_ENV:-/home/temp/.micalternativo-keys/keystore.env}"
```

### Q7 — version.properties parsing (verified, CRLF-safe)
```bash
VERSION_CODE=$(grep -E '^versionCode=' version.properties | head -1 | cut -d= -f2 | tr -d '[:space:]')
VERSION_NAME=$(grep -E '^versionName=' version.properties | head -1 | cut -d= -f2 | tr -d '[:space:]')
[[ "$VERSION_CODE" =~ ^[0-9]+$ ]] || { echo "ERRO: versionCode inválido"; exit 1; }
[[ -n "$VERSION_NAME" ]] || { echo "ERRO: versionName vazio"; exit 1; }
```

### Q1 — aapt2 link, manifest-only, version injection (verified; NO aapt2 compile needed)
```bash
"$BT/aapt2" link \
  --manifest app/src/main/AndroidManifest.xml \
  -I "$ANDROID_JAR" \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  -o build/base.apk
# Verified output: AndroidManifest.xml (deflated) + resources.arsc (40 B, Stored)
```

### Q2 — javac against android.jar on JDK 21 (verified; produces major version 52)
```bash
javac \
  -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" \
  -classpath "$ANDROID_JAR" \
  -encoding UTF-8 \
  -Xlint:-options \
  -d build/obj \
  $(find app/src/main/java -name '*.java')
# NOTE: '--release 8 -bootclasspath' is a HARD ERROR (verified) — do not combine.
```

### Q3 — d8 (verified; output dir must pre-exist)
```bash
mkdir -p build/dex
"$BT/d8" --release --min-api 29 --lib "$ANDROID_JAR" \
  --output build/dex \
  $(find build/obj -name '*.class')
# Produces build/dex/classes.dex
```

### Q4 — insert classes.dex (verified; preserves Stored resources.arsc)
```bash
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -j ../unsigned.apk classes.dex)
# classes.dex lands at APK root, deflated (legal — only resources.arsc must stay Stored)
```

### Q5 — zipalign then sign, password via env (verified with real keystore)
```bash
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk

set -a; source "$KEYSTORE_ENV"; set +a   # exports STOREPASS and ALIAS
: "${STOREPASS:?ERRO: STOREPASS ausente em $KEYSTORE_ENV}"
: "${ALIAS:?ERRO: ALIAS ausente em $KEYSTORE_ENV}"

APK_OUT="dist/MicAlternativo-v${VERSION_NAME}.apk"
"$BT/apksigner" sign \
  --ks "$KEYSTORE_PATH" \
  --ks-key-alias "$ALIAS" \
  --ks-pass env:STOREPASS \
  --min-sdk-version 29 \
  --out "$APK_OUT" \
  build/aligned.apk
# Password never on argv (env: format) — verified working.
```

### Q5/Q6 — gates (verified positively AND negatively)
```bash
# GATE 1: signature — exit code + scheme check (v3 counts as "v2+", see Pitfall 1)
verify_out=$("$BT/apksigner" verify --verbose --min-sdk-version 29 "$APK_OUT")
echo "$verify_out"
echo "$verify_out" | grep -Eq '(v2 scheme \(APK Signature Scheme v2\): true|v3 scheme \(APK Signature Scheme v3\): true)' \
  || { echo "ERRO: APK sem assinatura v2/v3 — não instalará no Android 11+"; exit 1; }

# GATE 2: alignment on the FINAL signed artifact
"$BT/zipalign" -c 4 "$APK_OUT" || { echo "ERRO: APK desalinhado"; exit 1; }

# Observed gate outputs this session (valid build):
#   Verified using v2 scheme (APK Signature Scheme v2): false
#   Verified using v3 scheme (APK Signature Scheme v3): true
# Negative tests: unsigned APK -> apksigner exit 1 ("DOES NOT VERIFY: Missing META-INF/MANIFEST.MF")
#                 misaligned stored entry -> zipalign -c exit 1 ("Verification FAILED")
#                 file appended after signing -> apksigner exit 1
```

### Q6 — install-readiness without a device + summary (verified)
```bash
"$BT/aapt2" dump badging "$APK_OUT"
# Verified fields this session:
#   package: name='br.com.micalternativo' versionCode='1' versionName='0.1.0'
#   sdkVersion:'29'  targetSdkVersion:'34'
#   uses-permission: name='android.permission.RECORD_AUDIO'
#   launchable-activity: name='br.com.micalternativo.MainActivity'

echo "APK:    $APK_OUT ($(stat -c %s "$APK_OUT") bytes)"
echo "SHA256: $(sha256sum "$APK_OUT" | cut -d' ' -f1)"
"$BT/apksigner" verify --print-certs "$APK_OUT"
# Cert digest must stay constant across builds (DIST-03):
#   SHA-256: b6966a76565821025c2ed22ea8325e4108c9beff70686baacb5dee496b8d6fe8

# With a device attached (human step):
# "$ANDROID_HOME/platform-tools/adb" install -r "$APK_OUT"
```

### Q8 — skeleton MainActivity (compiled + dexed successfully this session)
```java
// app/src/main/java/br/com/micalternativo/MainActivity.java
package br.com.micalternativo;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        TextView title = new TextView(this);
        title.setText("MicAlternativo");
        title.setTextSize(24f);
        root.addView(title);
        TextView version = new TextView(this);
        String v;
        try { v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { v = "?"; }
        version.setText("v" + v);
        root.addView(version);
        setContentView(root);
    }
}
```

### Manifest (linked successfully this session)
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="br.com.micalternativo">
    <uses-sdk android:minSdkVersion="29" android:targetSdkVersion="34" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <application android:label="MicAlternativo">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```
Note: `android:exported="true"` is mandatory on targetSdk 31+ for activities with intent filters — omitting it fails at install time. Badging confirmed `launchable-activity` present. [VERIFIED: local toolchain execution — link + badging succeeded with this exact manifest]

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `aapt` v1 single-pass | `aapt2 compile`+`link` (link-only OK for manifest-only) | AGP 3.0 era | Legacy `aapt` binary still ships in build-tools 34.0.0 but is deprecated — don't use it |
| `dx` | `d8` | Build-tools 28+ | d8 is the only option; core is R8 8.2.2-dev in this build-tools |
| jarsigner then zipalign | zipalign then apksigner | APK Sig Scheme v2 (Android 7) | Order is inverted vs old tutorials; D-07 already encodes this |
| Grep "v2 scheme: true" as gate | Accept v2 OR v3 true | apksigner scheme auto-selection by min-sdk | v3-only is the norm for minSdk ≥ 28 apps (verified) |
| `--ks-pass pass:...` in scripts | `--ks-pass env:VAR` / `file:` | long-standing apksigner feature | Keeps secrets out of process listings |

**Deprecated/outdated:** `aapt` v1, `dx`, `jarsigner` for APKs, v1-only signing, `zipalign` after signing.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | The Android SDK at the session scratchpad path will still exist when `build.sh` runs in later sessions (scratchpad is session-scoped) [ASSUMED] | Standard Stack / Pattern 3 | Build fails at preflight. Mitigated by D-03: `ANDROID_HOME` env override + clear failure message. Planner should treat the default path as a convenience, not a guarantee. |
| B1 | A v3-only APK sideloads via the Package Installer UI on the A15 5G exactly as `adb install` would accept it (cannot test without device; scheme validity itself is doc-backed) [CITED: source.android.com/docs/security/features/apksigning — v3 supported Android 9+] | Pitfall 1 / Pitfall 8 | If Samsung's installer behaved differently (no evidence of this), the human-verify checkpoint catches it; recovery is a re-sign, not a redesign. |

No other assumptions — all pipeline behavior claims were executed locally this session.

## Open Questions

1. **Does the APK actually install and open on the physical A15 5G?**
   - What we know: all machine-checkable install-readiness signals pass (v3 signature, alignment, badging, minSdk/targetSdk correct).
   - What's unclear: only a real sideload proves success criterion 3; no device is attached in this environment.
   - Recommendation: plan a `checkpoint:human-verify` task — install build #1, open app, then bump versionCode, rebuild, and update-install over it (proves DIST-03's same-key update path in one pass).

2. **Should `release.jks` file permissions be tightened?**
   - What we know: `keystore.env` is 600 (correct per D-01), but `release.jks` is currently **664 (group/world-readable)** — observed via `ls -la`.
   - Recommendation: add `chmod 600 "$KEYSTORE_PATH"` as a one-time hardening step (or a preflight warning in build.sh). Low effort, closes a real gap versus D-01's intent.

## Environment Availability

All probes executed this session:

| Dependency | Required By | Available | Version / Detail | Fallback |
|------------|------------|-----------|------------------|----------|
| aapt2 | stage 1, badging | ✓ | build-tools 34.0.0 | — |
| d8 | stage 3 | ✓ | build-tools 34.0.0 (R8 8.2.2-dev) | — |
| zipalign | stages 5, gate 2 | ✓ | build-tools 34.0.0 | — |
| apksigner | stage 6, gate 1 | ✓ | build-tools 34.0.0 | — |
| android.jar | javac/d8 classpath | ✓ | platforms/android-34 | — |
| javac / java | stages 2; d8+apksigner host JVM | ✓ | OpenJDK 21.0.11 | — |
| zip / unzip | stage 4 / inspection | ✓ | Info-ZIP 3.0 (`/usr/bin`) | `jar -uf` could substitute but unnecessary |
| bash | build.sh | ✓ | 5.2.21 | — |
| sha256sum, stat, grep, cut, tr | summary + parsing | ✓ | coreutils | — |
| keystore + env file | signing | ✓ | `release.jks` (PKCS12, alias micalternativo, valid→2056) + `keystore.env` (600) | — (loss = no more updates; D-02 documents this) |
| adb | device install | ✓ tool / ✗ device | platform-tools 1.0.41; **no device attached** | Human sideload + `checkpoint:human-verify` |
| keytool | not needed by build.sh (keystore pre-exists) | ✓ | JDK 21 | — |

**Missing dependencies with no fallback:** none for the build itself.
**Missing with fallback:** physical A15 5G device (human-verify step covers it).

## Security Domain

`security_enforcement: true` (ASVS level 1). No network, no user input, no auth in this phase — the security surface is the signing supply chain.

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no | — |
| V3 Session Management | no | — |
| V4 Access Control | no | — |
| V5 Input Validation | yes (minimal) | Validate `version.properties` values (`^[0-9]+$` for versionCode, non-empty versionName) before injecting into aapt2 argv — verified pattern in Code Examples |
| V6 Cryptography | yes | Never hand-roll signing — `apksigner` only. Key: RSA 2048 / SHA384withRSA (verified adequate for APK signing); keystore PKCS12 |
| V14 Configuration / Secrets | yes | Password via `--ks-pass env:` (off argv), keystore + env file outside repo, `.gitignore` excludes `*.jks`/`*.apk`; tighten `release.jks` to 600 (currently 664 — see Open Question 2) |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Keystore password leak via process list / shell history | Information Disclosure | `env:STOREPASS` format (verified); never `pass:`; never echo sourced values |
| Keystore/password committed to git | Information Disclosure | `.gitignore` already excludes `*.jks`; build.sh reads paths from env, never copies secrets into repo |
| Keystore loss → no update path (signature identity lost) | Denial of Service (of updates) | D-02 documentation duty in build.sh; cert SHA-256 recorded in summary output for continuity checking |
| Tampered/corrupted APK shipped | Tampering | Both gates on the final artifact; verified that any post-sign modification → apksigner exit 1 |
| Silent downgrade to v1-only signing | Tampering/Spoofing | Scheme grep gate (v2 OR v3 true) — v1-only output would fail it |

## Sources

### Primary (HIGH confidence)
- **Local toolchain execution (this session)** — every pipeline command, gate, and negative test run against build-tools 34.0.0, OpenJDK 21.0.11, android-34 platform, and the real project keystore; outputs quoted verbatim in this document. This is the authoritative source for all `[VERIFIED: local toolchain execution]` claims.
- Project-internal: `01-CONTEXT.md` (decisions), `.claude/CLAUDE.md` (constraints + STACK.md mirror), `.planning/research/PITFALLS.md`, `.planning/research/ARCHITECTURE.md`.

### Secondary (MEDIUM confidence — official docs via WebFetch)
- [developer.android.com/tools/apksigner](https://developer.android.com/tools/apksigner) — scheme auto-selection by min-sdk range; `--ks-pass` formats (`pass:`, `env:`, `file:`, `stdin`); zipalign-before-sign caution. Consistent with empirical results.
- [source.android.com/docs/security/features/apksigning](https://source.android.com/docs/security/features/apksigning) — v2 since Android 7, v3 since Android 9; multi-scheme advice is for pre-minSdk device compatibility.

### Tertiary (LOW confidence)
- None used for load-bearing claims.

### Note on prior research corrections
- `.planning/research/STACK.md` standalone file is corrupted (contains only "cf") — its content survives intact inside `.claude/CLAUDE.md`'s stack section; planner should rely on the CLAUDE.md mirror (or this document). Consider repairing the file (out of this phase's scope; flag to `/gsd-health`).
- PITFALLS.md Pitfall 4's literal "confirm v2: true" check is **superseded** by this document's Pitfall 1 (v3 counts as v2+; forcing v2 doesn't work at minSdk 29).

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all tools verified on disk and exercised
- Pipeline commands: HIGH — executed end-to-end, artifact produced and gates passed
- Gate semantics: HIGH — positive and negative tests executed (unsigned/misaligned/tampered → exit 1)
- Signature scheme behavior: HIGH empirically for this toolchain; the "installs on real device" leap is doc-backed (MEDIUM) pending the human-verify checkpoint
- Security/secrets handling: HIGH — `env:` flow verified with real credentials

**Research date:** 2026-07-28
**Valid until:** ~2026-08-27 (stable toolchain; re-verify only if SDK/build-tools or JDK versions change, or if the scratchpad SDK path disappears)
