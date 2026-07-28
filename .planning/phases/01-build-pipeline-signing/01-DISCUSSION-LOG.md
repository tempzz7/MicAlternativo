# Phase 1: Build Pipeline & Signing - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-07-28
**Phase:** 1-Build Pipeline & Signing
**Areas discussed:** Keystore e segredos, Interface do build, App-esqueleto, Gates de verificação, Ordem do pipeline
**Mode:** `--auto` (opções recomendadas selecionadas automaticamente; sessão autônoma)

---

## Keystore e segredos

| Option | Description | Selected |
|--------|-------------|----------|
| Reusar keystore permanente já gerada (fora do repo, env com chmod 600) | Mesma assinatura para sempre → updates instalam por cima | ✓ |
| Gerar keystore nova por build | Quebraria updates futuros | |
| Keystore no repo com senha em texto | Vazamento de segredo | |

**User's choice:** [auto] opção recomendada
**Notes:** Keystore criada antecipadamente durante a inicialização do projeto.

---

## Interface do build

| Option | Description | Selected |
|--------|-------------|----------|
| `scripts/build.sh` único, env-driven, `version.properties`, saída `dist/` versionada | Simples, reproduzível, sem dependências | ✓ |
| Makefile multi-target | Complexidade desnecessária p/ 1 artefato | |
| Gradle | Vetado pelo PROJECT.md | |

**User's choice:** [auto] opção recomendada

---

## App-esqueleto

| Option | Description | Selected |
|--------|-------------|----------|
| MainActivity mínima + manifest definitivo (minSdk 29/target 34, RECORD_AUDIO já declarado) | APK instala/abre; manifest não muda depois | ✓ |
| APK vazio sem Activity | Não dá para validar "instala e abre" | |
| Já incluir UI de gravação | Escopo da Fase 2 | |

**User's choice:** [auto] opção recomendada

---

## Gates de verificação

| Option | Description | Selected |
|--------|-------------|----------|
| `zipalign -c 4` + `apksigner verify --min-sdk-version 29` obrigatórios, falha ⇒ build falha | Impede APK que "não instala" (DIST-02) | ✓ |
| Verificação manual/eyeball | Regressão silenciosa garantida | |

**User's choice:** [auto] opção recomendada

---

## Ordem do pipeline

| Option | Description | Selected |
|--------|-------------|----------|
| aapt2 link → javac → d8 → add dex → zipalign → apksigner | Ordem correta; v2/v3 cobre layout de bytes | ✓ |
| Assinar antes de alinhar (padrão jarsigner antigo) | Invalida assinatura v2/v3 | |

**User's choice:** [auto] opção recomendada

## Claude's Discretion

- Flags exatas das ferramentas, estrutura interna do script, necessidade de `aapt2 compile`
- Layout de diretórios de código (seguir proposta da pesquisa)

## Deferred Ideas

- Ícone próprio/adaptive icon → Fase 3 ou 5
- CI (GitHub Actions) → Fase 5 / v1.x
- F-Droid → DIST-06 (v2)
