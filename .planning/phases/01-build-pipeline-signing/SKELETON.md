# Walking Skeleton — MicAlternativo

**Phase:** 1
**Generated:** 2026-07-28

## Capability Proven End-to-End

Um usuário instala (sideload) e abre um APK MicAlternativo assinado no Samsung A15 5G (Android 14), e recebe uma atualização por cima sem desinstalar — tudo produzido por um único comando (`scripts/build.sh`).

## Architectural Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Sistema de build | `scripts/build.sh` em bash (`set -euo pipefail`) — pipeline aapt2 link → javac → d8 → zip -j → zipalign → apksigner; SEM Gradle/AGP, sem Makefile (D-03, D-07) | Constraint do projeto (PROJECT.md); pipeline inteiro verificado empiricamente na pesquisa da fase; o script É o build system |
| Linguagem / UI | Java puro nível 8 (`-source 8 -target 8 -bootclasspath android.jar`), UI 100% programática, ZERO dependências externas e ZERO Jetpack/compat (D-05) | Sem resolução de dependências = build reproduzível só com SDK + JDK; bootclasspath faz API ausente no Android falhar em compile-time |
| Plataforma | minSdk 29 / targetSdk 34; platform android-34 + build-tools 34.0.0 | minSdk 29 = MediaStore scoped storage sem permissões extras; targetSdk 34 = exigência do aparelho-alvo Android 14 |
| Assinatura | Keystore permanente `/home/temp/.micalternativo-keys/release.jks` (alias `micalternativo`, RSA 2048, válida ~30 anos); senha via `--ks-pass env:STOREPASS` de `keystore.env` (600); cert SHA-256 `b6966a76…8d6fe8` (D-01, D-02) | Mesma assinatura em todos os releases ⇒ update-install sempre possível; segredos fora do repo e fora do argv |
| Esquema de assinatura | v3-only (emitido pelo apksigner para minSdk 29) aceito como "v2+"; gate = `apksigner verify` exit 0 E (v2 true OU v3 true) | Comportamento by-design do apksigner (verificado); gate só-v2 reprovaria todo build válido |
| Gates de qualidade | `apksigner verify --min-sdk-version 29` + `zipalign -c 4` no artefato FINAL; falha ⇒ exit != 0; harness independente `scripts/test_build.sh` (D-08) | DIST-02: nunca produzir silenciosamente APK que não instala no Android 11+ |
| Versionamento | `version.properties` (versionCode/versionName) injetado via `aapt2 link --version-code/--version-name`; artefato `dist/MicAlternativo-v{versionName}.apk` (D-04) | Fonte única de versão; manifest nunca editado à mão para versão |
| Layout de diretórios | `app/src/main/AndroidManifest.xml`, `app/src/main/java/br/com/micalternativo/`, `scripts/`, `build/` (intermediários, gitignored), `dist/` (artefato, gitignored) | Proposta da pesquisa adotada em CONTEXT (discricionariedade exercida); espelha layout Android padrão sem exigir Gradle |
| Distribuição / "deploy" | Sideload manual no aparelho físico (sem device neste ambiente ⇒ checkpoint humano); `adb install -r` quando houver device | App 100% offline, sem loja na v1; o aparelho real é o ambiente de produção |
| Toolchain host | ANDROID_HOME via env com default no SDK da sessão; preflight falha nomeando ferramenta ausente (D-03) | O caminho default é scratchpad de sessão — risco A1 da pesquisa mitigado por override + mensagem clara |

## Stack Touched in Phase 1

- [ ] Scaffold do projeto (layout de diretórios, `scripts/build.sh`, `scripts/test_build.sh`, `version.properties`, `.gitignore` já cobrindo segredos/artefatos)
- [ ] "Routing" → `MainActivity` launchable (intent-filter MAIN/LAUNCHER, `exported="true"`) — o único ponto de entrada do app
- [ ] Camada de dados → N/A nesta fase por design (app offline; MediaStore entra na Fase 2 como primeira escrita real)
- [ ] UI → TextView programática exibindo o versionName REAL lido de `PackageManager` (dado dinâmico, não hardcoded)
- [ ] Deployment → comando documentado de instalação (`adb install -r` / sideload) exercitado no aparelho físico via checkpoint humano

## Out of Scope (Deferred to Later Slices)

- Gravação de áudio, permissão RECORD_AUDIO em runtime, MediaStore (Fase 2)
- Seletor de fontes de áudio, medidor de nível, onboarding PT-BR (Fase 3)
- Playback, histórico, compartilhar no WhatsApp (Fase 4)
- `INSTALAR.md` (guia sideload One UI 6: Auto Blocker, Play Protect), APK como artefato de release (Fase 5)
- Ícone/adaptive icon próprio; CI (GitHub Actions); F-Droid/publicação (deferidos em CONTEXT)

## Subsequent Slice Plan

Cada fase seguinte adiciona uma fatia vertical sobre este esqueleto sem renegociar as decisões acima (manifest e pacote `br.com.micalternativo` já são definitivos):

- Phase 2: usuário concede permissão de mic e grava/salva áudio que aparece no app Arquivos (CAMCORDER hardcoded)
- Phase 3: usuário escolhe a fonte de mic com rótulos PT-BR, vê medidor de nível e entende o que o app conserta (e o que não)
- Phase 4: usuário ouve gravações, navega no histórico e envia ao WhatsApp como áudio tocável
- Phase 5: usuário baixa APK versionado e instala seguindo guia PT-BR de sideload
