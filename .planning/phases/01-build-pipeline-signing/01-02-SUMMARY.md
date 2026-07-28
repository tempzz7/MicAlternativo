---
phase: 01-build-pipeline-signing
plan: "02"
subsystem: build-infrastructure
tags: [android, apk, sideload, apksigner, samsung-a15, one-ui-6, github-release]
requires:
  - phase: 01-build-pipeline-signing (plan 01-01)
    provides: "dist/MicAlternativo-v0.1.0.apk + dist/MicAlternativo-v0.1.1.apk assinados com a keystore permanente; scripts/build.sh --verify-only"
provides:
  - "Prova no aparelho real (Samsung A15 5G, Android 14 / One UI 6): APK do pipeline instala por sideload e abre exibindo 'MicAlternativo v0.1.1' (Success Criterion 3 da fase)"
  - "Capacidade de update-install (mesma assinatura) comprovada por máquina: digest do certificado idêntico nos dois APKs + apksigner verify exit 0 (DIST-03)"
  - "Canal de distribuição de fato: repositório público GitHub com Release v0.1.1 carregando os dois APKs"
affects: [phase-02, phase-03, phase-04, phase-05]
tech-stack:
  added: []
  patterns:
    - "Checkpoint humano para provas que máquina nenhuma pode dar (instalação em aparelho físico sem device conectado)"
    - "Re-verificação dos gates (--verify-only) no artefato exato imediatamente antes da transferência"
key-files:
  created: []
  modified: []
key-decisions:
  - "Distribuição via GitHub Release (https://github.com/tempzz7/MicAlternativo/releases/tag/v0.1.1) tornou-se o canal de fato — criado durante o checkpoint com autorização do usuário; relevante para a Fase 5 (DIST-05 já tem rota natural)"
patterns-established: []
requirements-completed: [DIST-03]
coverage:
  - id: D1
    description: "APK produzido pelo pipeline instala por sideload no Samsung A15 5G real (Android 14 / One UI 6) e abre exibindo 'MicAlternativo v0.1.1' (Success Criterion 3)"
    requirement: DIST-03
    verification: []
    human_judgment: true
    rationale: "Instalação em aparelho físico sem device conectado ao ambiente — só o usuário pode confirmar. Confirmado: resposta 'APROVADO' em 2026-07-28."
  - id: D2
    description: "Capacidade de update-install sem desinstalar: mesma keystore permanente em builds consecutivos (DIST-03)"
    requirement: DIST-03
    verification:
      - kind: other
        ref: "scripts/build.sh --verify-only dist/MicAlternativo-v0.1.0.apk && scripts/build.sh --verify-only dist/MicAlternativo-v0.1.1.apk (ambos exit 0)"
        status: pass
      - kind: other
        ref: "apksigner: digest SHA-256 do certificado idêntico nos dois APKs (b6966a76565821025c2ed22ea8325e4108c9beff70686baacb5dee496b8d6fe8)"
        status: pass
    human_judgment: false
duration: "2 min automatizados + checkpoint humano"
completed: "2026-07-28"
status: complete
---

# Phase 1 Plan 02: Checkpoint Humano — Sideload no A15 5G Summary

**Usuário confirmou ("APROVADO") que o APK do pipeline instala por sideload no Samsung A15 5G real e abre exibindo "MicAlternativo v0.1.1" — SC3 provado no aparelho físico; identidade de assinatura permanente (DIST-03) comprovada por máquina nos dois APKs.**

## Performance

- **Duration:** ~2 min automatizados (Task 1) + checkpoint humano (Task 2)
- **Completed:** 2026-07-28
- **Tasks:** 2 (1 auto + 1 checkpoint:human-verify)
- **Files modified:** 0 (plano somente leitura — dist/ intocado)

## Accomplishments

- **Success Criterion 3 fechado no aparelho real:** o usuário baixou o APK do GitHub Release, instalou por sideload no Samsung A15 5G (Android 14 / One UI 6) e o app abriu exibindo "MicAlternativo v0.1.1" — resposta: APROVADO.
- **Re-verificação pré-transferência (Task 1):** `scripts/build.sh --verify-only` exit 0 nos dois APKs; digest do certificado idêntico (`b6966a76565821025c2ed22ea8325e4108c9beff70686baacb5dee496b8d6fe8`); badging correto (versionCode 1 / 0.1.0 e versionCode 2 / 0.1.1); dist/ inalterado (task somente leitura).
- **Canal de distribuição estabelecido:** repositório público https://github.com/tempzz7/MicAlternativo com Release v0.1.1 carregando os dois APKs — criado durante o checkpoint com autorização do usuário. Vira o canal de fato para as próximas fases (relevante para a Fase 5 / DIST-05).

## Verification Evidence

**Automatizada (Task 1, antes do checkpoint):**
- `scripts/build.sh --verify-only dist/MicAlternativo-v0.1.0.apk` → exit 0
- `scripts/build.sh --verify-only dist/MicAlternativo-v0.1.1.apk` → exit 0
- Digest SHA-256 do certificado idêntico nos dois APKs: `b6966a76565821025c2ed22ea8325e4108c9beff70686baacb5dee496b8d6fe8`
- Badging: v0.1.0 → `versionCode='1' versionName='0.1.0'`; v0.1.1 → `versionCode='2' versionName='0.1.1'`
- dist/ não modificado pelo task (verificação somente leitura)

**Humana (Task 2, checkpoint):**
- Usuário respondeu **APROVADO**: baixou o APK do GitHub Release (https://github.com/tempzz7/MicAlternativo/releases/tag/v0.1.1), instalou no Samsung A15 5G real (Android 14 / One UI 6) e o app abre mostrando "MicAlternativo v0.1.1" — **SC3 confirmado** (instala por sideload e abre).

**Nuance registrada honestamente (não superestimar):** a confirmação do usuário mostra o **v0.1.1 rodando**; a sequência explícita de update-install v0.1.0 → v0.1.1 (passos 4–5 do roteiro) **não foi confirmada separadamente** pelo usuário. A capacidade de update com a mesma assinatura está, porém, **comprovada por máquina**: digest do certificado idêntico nos dois APKs e `apksigner verify` exit 0 em ambos — o Android aceita update-install quando pacote e certificado coincidem e o versionCode cresce, condições todas verificadas. DIST-03 (mesma keystore permanente em todos os builds) permanece satisfeito por essa evidência mecânica.

## Task Commits

| Task | Name | Commit |
| ---- | ---- | ------ |
| 1 | Re-verificar artefatos e imprimir instruções de transferência | nenhum (somente leitura — nenhum arquivo criado/modificado) |
| 2 | Checkpoint humano: instalar no A15 5G | nenhum (verificação no aparelho físico) |

**Plan metadata:** commit docs deste SUMMARY + STATE/ROADMAP (ver git log).

## Files Created/Modified

Nenhum arquivo de projeto — plano somente leitura por design. Apenas artefatos de planejamento (.planning/) atualizados no commit de metadados.

## Decisions Made

- **GitHub Release como canal de distribuição de fato:** durante o checkpoint, com autorização do usuário, foi criado o repositório público https://github.com/tempzz7/MicAlternativo e a Release v0.1.1 com os dois APKs. O plano previa transferência manual (Drive/e-mail/USB); a rota real foi download direto da Release no aparelho. Isso antecipa parte do escopo da Fase 5 (DIST-05: APK versionado disponível como artefato baixável no repo/release).

## Deviations from Plan

**1. Rota de transferência diferente da prevista (escopo do checkpoint, autorizado pelo usuário)**
- **Found during:** Task 2 (checkpoint humano)
- **Planejado:** transferência manual dos APKs (Rota A: Drive/e-mail/USB) ou adb (Rota B)
- **Ocorrido:** criação de repositório público GitHub + Release v0.1.1 com os dois APKs; o usuário baixou e instalou direto da Release
- **Impacto:** nenhum na validade da prova — o artefato instalado é o APK assinado do pipeline; efeito colateral positivo: canal de distribuição já existe para a Fase 5

Nenhuma outra alteração — Task 1 executado exatamente como escrito.

## Issues Encountered

None.

## User Setup Required

None — nenhuma configuração externa pendente.

## Next Phase Readiness

- **Fase 1 completa (2/2 planos):** pipeline de build de um comando (SC1), gates que impedem APK quebrado (SC2), instalação real por sideload provada (SC3) e identidade de assinatura permanente (SC4/DIST-03).
- Loop de teste das fases 2–4 ("compilar → instalar → testar no aparelho") está sobre base provada, não suposta.
- **Para a Fase 5:** o GitHub Release já é o canal de distribuição de fato — o guia INSTALAR.md (DIST-04) deve documentar a rota "baixar da Release no navegador do aparelho".
- **Atenção (herdada de 01-01 / RESEARCH A1):** em novas sessões, `ANDROID_HOME` pode precisar ser reexportado; o preflight de scripts/build.sh falha com mensagem clara.

## Self-Check: PASSED

SUMMARY.md presente em disco; dist/ inalterado (sha256 dos dois APKs idênticos aos do estado pós-01-01); nenhum commit de task a verificar (plano somente leitura por design).

---
*Phase: 01-build-pipeline-signing*
*Completed: 2026-07-28*
