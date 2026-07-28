# Phase 1: Build Pipeline & Signing - Context

**Gathered:** 2026-07-28
**Status:** Ready for planning

<domain>
## Phase Boundary

Entregar um pipeline de build reproduzível, sem Gradle, que transforma o código-fonte em um APK **assinado e instalável** com um único comando (`scripts/build.sh`), com gates automáticos que impedem gerar APK quebrado. Inclui um app-esqueleto mínimo (MainActivity) apenas para provar que o APK instala e abre no aparelho — a funcionalidade de gravação vem nas fases 2–4. Requisitos: DIST-01, DIST-02, DIST-03.

</domain>

<decisions>
## Implementation Decisions

### Keystore e segredos
- **D-01:** Usar a keystore permanente já gerada em `/home/temp/.micalternativo-keys/release.jks` (alias `micalternativo`, RSA 2048, validade ~30 anos). Credenciais em `/home/temp/.micalternativo-keys/keystore.env` (`STOREPASS=...`, `ALIAS=...`, chmod 600). NUNCA commitar keystore/senha (já coberto pelo `.gitignore`).
- **D-02:** `build.sh` lê `KEYSTORE_PATH` e `KEYSTORE_ENV` de variáveis de ambiente, com default para os caminhos acima. Documentar no script: perder a keystore = impossibilidade de publicar updates instaláveis por cima.

### Interface do build
- **D-03:** Um único `scripts/build.sh` em bash com `set -euo pipefail`; sem Makefile. `ANDROID_HOME` configurável por env, default para o SDK instalado (`/tmp/claude-1000/-home-temp-problem/baf2ba44-b642-4c2a-955d-a883998e26e3/scratchpad/android-sdk`); o script falha com mensagem clara se as ferramentas não existirem.
- **D-04:** Versão em `version.properties` na raiz (`versionCode`, `versionName`); o script injeta no manifest via `aapt2 link --version-code/--version-name`. Artefato final: `dist/MicAlternativo-v{versionName}.apk`. Intermediários em `build/` (ambos gitignored).

### App-esqueleto da Fase 1
- **D-05:** Pacote `br.com.micalternativo`. Uma `MainActivity` Java com UI 100% programática (TextView "MicAlternativo" + versão) — sem XML de layout, sem AndroidX.
- **D-06:** O `AndroidManifest.xml` já nasce na forma final: minSdk 29, targetSdk 34, `<uses-permission android:name="android.permission.RECORD_AUDIO"/>` declarado desde já (fases 2+ usam), `android:label="MicAlternativo"`. Ícone default do sistema na Fase 1 (ícone próprio é polish de fase posterior, não requisito).

### Pipeline e gates
- **D-07:** Ordem fixa: `aapt2 link` (gera APK base com manifest+resources) → `javac` (release 8/`-bootclasspath android.jar`) → `d8` → adicionar `classes.dex` ao APK → `zipalign -f 4` → `apksigner sign`. **zipalign SEMPRE antes de apksigner** — assinatura v2/v3 cobre o layout exato de bytes.
- **D-08:** Gates obrigatórios no fim do build (falha ⇒ exit ≠ 0): `zipalign -c 4` e `apksigner verify --min-sdk-version 29` confirmando esquema v2+. Imprimir resumo (tamanho do APK, esquemas de assinatura, sha256).

### Claude's Discretion
- Flags exatas de cada ferramenta, layout interno do script (funções/etapas), mensagens de log, e se `aapt2 compile` é necessário (sem resources além do manifest, `link` direto pode bastar).
- Estrutura de diretórios de código: seguir proposta da pesquisa (`app/src/main/java/...`, `app/src/main/AndroidManifest.xml`, `scripts/`).

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Pipeline de build e assinatura
- `.planning/research/STACK.md` — pipeline aapt2→javac→d8→zipalign→apksigner completo, esquemas de assinatura, o que NÃO usar
- `.planning/research/PITFALLS.md` — os 3 gates de falha do build manual (v2 ausente, resources.arsc não alinhado, ordem zipalign/apksigner) e demais armadilhas

### Arquitetura e layout do repo
- `.planning/research/ARCHITECTURE.md` — layout de repositório proposto, ordem de construção sugerida
- `.planning/research/SUMMARY.md` — síntese e implicações por fase

### Projeto
- `.planning/PROJECT.md` — decisões travadas (sem Gradle, sem AndroidX, minSdk 29/targetSdk 34)
- `.planning/REQUIREMENTS.md` — DIST-01, DIST-02, DIST-03 (escopo desta fase)

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- Greenfield — nenhum código de app ainda. Ativos de ambiente já prontos:
  - Android SDK instalado: platform android-34, build-tools 34.0.0, platform-tools (no scratchpad da sessão)
  - OpenJDK 21 (`/usr/bin/java`); `javac` compilando com `--release 8`-style bootclasspath para bytecode compatível com d8
  - Keystore permanente + env de credenciais em `/home/temp/.micalternativo-keys/`

### Established Patterns
- Commits atômicos via gsd-tools; `.gitignore` já exclui `build/`, `dist/`, `*.apk`, `*.jks`, `android-sdk/`

### Integration Points
- O APK gerado nesta fase é o mesmo esqueleto que a Fase 2 preenche com gravação — manifest e pacote já ficam definitivos aqui

</code_context>

<specifics>
## Specific Ideas

- O usuário final instala por sideload num Samsung A15 5G (Android 14 / One UI 6) — o critério real de sucesso da fase é "o APK instala e abre nesse aparelho"
- Nome do app visível: "MicAlternativo"

</specifics>

<deferred>
## Deferred Ideas

- Ícone/adaptive icon próprio — polish visual, tratar junto das fases de UI (3) ou distribuição (5)
- CI (GitHub Actions) para build automático — faz sentido junto da Fase 5 / v1.x quando o repo for publicado
- F-Droid / publicação pública — já registrado como DIST-06 (v2)

</deferred>

---

*Phase: 1-Build Pipeline & Signing*
*Context gathered: 2026-07-28*
