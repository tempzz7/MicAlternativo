# Requirements — MicAlternativo v1

Derivados de `.planning/PROJECT.md` + `.planning/research/FEATURES.md` (table stakes P1 + pedidos explícitos do usuário). Modo auto: table stakes incluídos, diferenciadores P2 diferidos para v1.x.

## v1 Requirements

### Gravação (GRAV)

- [ ] **GRAV-01**: Usuário pode gravar e parar uma gravação de áudio com um botão único e estado visível (gravando/parado), usando `MediaRecorder` parametrizado por fonte de áudio (AAC/M4A mono, preset único)
- [ ] **GRAV-02**: A fonte padrão de gravação é `CAMCORDER` (mic secundário — o que funciona no aparelho de referência)
- [ ] **GRAV-03**: Durante a gravação, usuário vê cronômetro e um indicador simples de nível (amplitude) para confirmar que a fonte escolhida está captando som
- [ ] **GRAV-04**: Falhas do MediaRecorder (mic ocupado, fonte não suportada, erro nativo -19) não travam o app — erro é capturado, recorder é recriado e usuário vê mensagem clara em PT-BR

### Fontes de áudio (FONTE)

- [ ] **FONTE-01**: Usuário pode alternar entre as fontes MIC, CAMCORDER, VOICE_RECOGNITION, VOICE_COMMUNICATION e DEFAULT em um seletor destacado na tela principal (não escondido em configurações)
- [ ] **FONTE-02**: Cada fonte tem rótulo e descrição em PT-BR simples (ex.: "Microfone da câmera (o de trás/cima)" em vez de "CAMCORDER")
- [ ] **FONTE-03**: A fonte escolhida pelo usuário fica salva (SharedPreferences) e vira o padrão nas próximas aberturas — "definir o mic que funciona como principal"

### Permissão (PERM)

- [ ] **PERM-01**: App pede RECORD_AUDIO em tempo de execução no primeiro toque em Gravar, com explicação prévia em PT-BR quando aplicável
- [ ] **PERM-02**: Negação permanente é tratada: app explica e oferece atalho (deep-link) para as configurações do app

### Reprodução (PLAY)

- [ ] **PLAY-01**: Usuário pode ouvir a gravação recém-feita (play/pause) antes de compartilhar

### Armazenamento (STOR)

- [ ] **STOR-01**: Gravações são salvas via MediaStore (fluxo IS_PENDING=1 → gravar → IS_PENDING=0; RELATIVE_PATH `Music/MicAlternativo`), visíveis no app de Arquivos e em players
- [ ] **STOR-02**: Gravação que falha no meio não deixa arquivo órfão/invisível (linha pendente é deletada no erro)
- [ ] **STOR-03**: Usuário vê lista das gravações anteriores (query no MediaStore) e pode reproduzir e recompartilhar cada uma

### Compartilhamento (SHARE)

- [ ] **SHARE-01**: Usuário pode compartilhar qualquer gravação via share sheet (`ACTION_SEND`, URI `content://` do MediaStore com `FLAG_GRANT_READ_URI_PERMISSION`), chegando no WhatsApp como áudio tocável

### Onboarding (ONBD)

- [ ] **ONBD-01**: Tela/seção de ajuda em PT-BR explica o defeito do mic principal, o que o app faz, e os limites: não conserta ligações (indicar viva-voz/fone) nem o gravador interno do WhatsApp (fluxo é gravar aqui → compartilhar lá)

### Build & Distribuição (DIST)

- [x] **DIST-01**: `scripts/build.sh` produz o APK de forma reproduzível sem Gradle (aapt2 → javac → d8 → empacotar → zipalign → apksigner), com um comando
- [x] **DIST-02**: O build tem gates automáticos: `apksigner verify` (v2+ presente) e `zipalign -c` passam ou o build falha — nunca gerar APK que "não instala" no Android 11+
- [x] **DIST-03**: APK é assinado com a keystore permanente do projeto (mesma assinatura em todos os releases, permitindo updates por cima)
- [ ] **DIST-04**: Guia de instalação em PT-BR (`INSTALAR.md`) cobre sideload no One UI 6: fontes desconhecidas por app, Auto Blocker da Samsung e aviso do Play Protect
- [ ] **DIST-05**: APK final fica disponível para o usuário baixar (artefato no repo/Release), com nome versionado (ex.: `MicAlternativo-v1.0.0.apk`)

## v2 Requirements (deferred — v1.x)

- **AUTO-01**: Auto-detecção do mic funcional — testa cada fonte por ~2s, mede amplitude, ranqueia e recomenda a melhor (diferencial; validar fontes reais no aparelho antes)
- **SHARE-02**: Botão dedicado "Enviar no WhatsApp" (detecção de `com.whatsapp`/`com.whatsapp.w4b`), além do share sheet genérico
- **STOR-04**: Renomear e excluir gravações na lista
- **DIST-06**: Publicação pública (GitHub Releases com changelog; avaliar F-Droid)

## Out of Scope

- Trocar mic de ligações telefônicas — impossível sem root (roteado pelo modem/HAL); alternativa documentada no ONBD-01
- Trocar o mic interno do WhatsApp — Android proíbe interferir na captura de outro app
- Cloud/contas/sync — app 100% offline por decisão de projeto
- Edição de áudio (cortar/unir/efeitos) — fora do propósito "gravar → enviar"
- Gravação em background/agendada/de chamadas — fora do problema-alvo; sensível legalmente
- Seleção de formato/bitrate — um preset único que o WhatsApp aceita bem
- Mic Bluetooth — problema já resolvido por qualquer gravador; o alvo é mic interno
- Escolher contato específico do WhatsApp — sem API pública; o picker do próprio WhatsApp assume após o ACTION_SEND

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| GRAV-01 | Phase 2 | Pending |
| GRAV-02 | Phase 2 | Pending |
| GRAV-03 | Phase 3 | Pending |
| GRAV-04 | Phase 2 | Pending |
| FONTE-01 | Phase 3 | Pending |
| FONTE-02 | Phase 3 | Pending |
| FONTE-03 | Phase 3 | Pending |
| PERM-01 | Phase 2 | Pending |
| PERM-02 | Phase 2 | Pending |
| PLAY-01 | Phase 4 | Pending |
| STOR-01 | Phase 2 | Pending |
| STOR-02 | Phase 2 | Pending |
| STOR-03 | Phase 4 | Pending |
| SHARE-01 | Phase 4 | Pending |
| ONBD-01 | Phase 3 | Pending |
| DIST-01 | Phase 1 | Complete |
| DIST-02 | Phase 1 | Complete |
| DIST-03 | Phase 1 | Complete |
| DIST-04 | Phase 5 | Pending |
| DIST-05 | Phase 5 | Pending |

**Coverage:** 20/20 v1 requirements mapped ✓
