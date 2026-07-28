---
phase: 01-build-pipeline-signing
plan: "01"
subsystem: build-infrastructure
tags: [android, apk, signing, aapt2, d8, apksigner, no-gradle, bash]
requires: []
provides:
  - "scripts/build.sh — pipeline completo aapt2 -> javac -> d8 -> zip -j -> zipalign -> apksigner com gates (DIST-01/02) e modo --verify-only"
  - "scripts/test_build.sh — harness E2E independente que re-verifica gates, badging e certificado"
  - "version.properties — fonte única de versionCode/versionName (D-04)"
  - "app/src/main/AndroidManifest.xml — manifest final (minSdk 29, targetSdk 34, RECORD_AUDIO, D-06)"
  - "app/src/main/java/br/com/micalternativo/MainActivity.java — esqueleto sem AndroidX (D-05)"
  - "dist/MicAlternativo-v0.1.0.apk + dist/MicAlternativo-v0.1.1.apk — par assinado com o mesmo certificado, pronto para o checkpoint humano do plano 01-02 (gitignored, regenerável via scripts/build.sh)"
affects: [01-02, phase-02, phase-03, phase-04, phase-05]
tech-stack:
  added: []
  patterns:
    - "Gates por exit code direto na captura, nunca em pipe (RESEARCH Pattern 1)"
    - "Segredos via set -a; source keystore.env + --ks-pass env:STOREPASS — senha jamais em argv (Pattern 2)"
    - "Preflight nomeia exatamente a ferramenta/arquivo ausente e como corrigir (Pattern 3)"
    - "Gate de assinatura aceita v2 OU v3 true — minSdk 29 emite v3-only por design (Pitfall 1)"
key-files:
  created:
    - version.properties
    - app/src/main/AndroidManifest.xml
    - app/src/main/java/br/com/micalternativo/MainActivity.java
    - scripts/test_build.sh
    - scripts/build.sh
  modified: []
decisions:
  - "aapt2 compile omitido — link direto do manifest basta (discricionariedade prevista em D-03/RESEARCH, verificado)"
  - "Gate de assinatura: exit 0 E (v2 true OU v3 true) — v3 satisfaz o 'v2+' do DIST-02 (Pitfall 1)"
  - "--verify-only só faz preflight de ferramentas (sem keystore) — verificação não exige segredos"
  - "Aviso (não falha) quando permissões de keystore/env > 600 (RESEARCH Open Q2)"
metrics:
  duration: "4 min"
  completed: "2026-07-28"
  tasks: 3
  files: 5
status: complete
---

# Phase 1 Plan 01: Build Pipeline & Signing (Walking Skeleton) Summary

**One-liner:** Pipeline no-Gradle completo (aapt2→javac→d8→zip→zipalign→apksigner) em um comando com gates que rejeitam APK quebrado, provado por harness E2E independente e dois builds consecutivos assinados com o certificado permanente do projeto.

## What Was Built

- **`scripts/build.sh` (196 linhas):** `preflight_tools`/`preflight_keystore` (mensagens PT-BR nomeando o que falta), `parse_version` CRLF-safe com validação ASVS V5, estágios na ordem fixa D-07, `run_gates` (apksigner verify capturado sem pipe + zipalign -c 4), `print_summary` (tamanho, sha256, esquemas, digest do certificado, badging) e modo `--verify-only <apk>`.
- **`scripts/test_build.sh` (63 linhas):** harness RED→GREEN que roda o build e re-verifica por fora: assinatura v2/v3, alinhamento, badging (package/sdk/permissão/activity) e digest do certificado permanente.
- **Esqueleto do app:** manifest final (D-06), MainActivity 100% programática sem AndroidX (D-05), version.properties como fonte única de versão (D-04).
- **Artefatos:** `dist/MicAlternativo-v0.1.0.apk` e `dist/MicAlternativo-v0.1.1.apk` (8619 bytes cada), ambos v3-signed com digest `b6966a76565821025c2ed22ea8325e4108c9beff70686baacb5dee496b8d6fe8`.

## Task Commits

| Task | Name | Commit |
| ---- | ---- | ------ |
| 1 | App skeleton + harness E2E (RED) | 08c32c2 |
| 2 | scripts/build.sh — pipeline completo com gates (GREEN) | 17630ce |
| 3 | Bump 0.1.1 — prova DIST-03 (mesmo certificado) | d489ce3 |

## Verification Evidence

- `bash scripts/test_build.sh` → exit 0, `TEST OK: dist/MicAlternativo-v0.1.1.apk` (DIST-01)
- APK adulterado (1 byte anexado pós-assinatura) → `--verify-only` exit != 0 com mensagem PT-BR (DIST-02, T-01-03)
- `KEYSTORE_ENV=/caminho/inexistente.env scripts/build.sh` → exit != 0 citando o arquivo ausente
- Digest SHA-256 do certificado idêntico em v0.1.0 e v0.1.1 e igual ao registrado na pesquisa (DIST-03)
- `aapt2 dump badging` v0.1.1: `versionCode='2' versionName='0.1.1'`, sdk 29/34, RECORD_AUDIO, MainActivity launchable
- `grep -c -- '--ks-pass pass:' scripts/build.sh` = 0; senha só via `env:STOREPASS` (T-01-01)
- `git check-ignore` confirma dist/ e build/ fora do repo (T-01-02)

## TDD Gate Compliance

Plano seguiu RED→GREEN por design do próprio plano: o RED vive no commit do Task 1 (`feat` 08c32c2 inclui o harness `scripts/test_build.sh` comprovadamente falhando sem build.sh — verificação automatizada do Task 1 exigiu exit != 0) e o GREEN no Task 2 (17630ce, harness passa). Não há commit com prefixo `test(...)` separado porque o Task 1 do plano agrupou fontes do esqueleto + harness em um único task `type="auto"`; a sequência RED→GREEN em si foi cumprida e está evidenciada nas verificações automatizadas.

## Deviations from Plan

None - plan executed exactly as written.

## Known Stubs

Nenhum stub bloqueante. `MainActivity` é intencionalmente um esqueleto (Walking Skeleton — objetivo declarado do plano); a funcionalidade de gravação chega nas fases 2–4.

## Next Steps

- Plano 01-02: checkpoint humano — instalar v0.1.0 no A15 5G, abrir o app, e update-install v0.1.1 por cima (prova SC3 + DIST-03 no aparelho real).
- Atenção futura (RESEARCH A1): o default de `ANDROID_HOME` aponta para scratchpad de sessão — em novas sessões, exportar `ANDROID_HOME` ou reinstalar o SDK; o preflight falha com mensagem clara.

## Self-Check: PASSED

Todos os 5 arquivos criados, os 2 APKs em dist/ e os 3 commits (08c32c2, 17630ce, d489ce3) verificados em disco/git.
