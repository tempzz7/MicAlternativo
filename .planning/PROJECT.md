# MicAlternativo

## What This Is

MicAlternativo é um app Android (APK instalável, futuramente distribuído publicamente) que contorna o defeito do microfone principal em celulares — caso concreto: Samsung Galaxy A15 5G com o microfone inferior morto/obstruído. Ele permite gravar áudio usando o microfone secundário que ainda funciona (o mesmo usado pelo viva-voz e pela gravação de vídeo), via seleção da fonte de áudio (`AudioSource.CAMCORDER` e afins), e compartilhar as gravações diretamente no WhatsApp como áudio tocável.

## Core Value

Uma pessoa com o microfone principal quebrado consegue gravar e enviar áudios pelo WhatsApp usando o microfone que ainda funciona — sem root, sem trocar de aparelho.

## Requirements

### Validated

(None yet — ship to validate)

### Active

- [ ] Usuário pode gravar áudio usando uma fonte de áudio alternativa (CAMCORDER — mic secundário)
- [ ] Usuário pode testar/alternar entre fontes de áudio (MIC, CAMCORDER, VOICE_RECOGNITION, UNPROCESSED, DEFAULT) para descobrir qual funciona no seu aparelho
- [ ] Usuário pode ouvir a gravação antes de enviar (playback)
- [ ] Gravações são salvas no MediaStore (visíveis no app de Arquivos / player de música)
- [ ] Usuário pode compartilhar a gravação direto para o WhatsApp (ACTION_SEND, chega como áudio tocável)
- [ ] App pede a permissão RECORD_AUDIO em tempo de execução com explicação clara
- [ ] APK assinado, instalável por sideload no A15 5G (Android 14)
- [ ] Distribuição: build reproduzível + release no GitHub (Releases) com instruções de instalação

### Out of Scope

- Trocar o microfone usado em ligações telefônicas — impossível sem root; o áudio de chamada é roteado pelo modem/HAL do sistema. Alternativas documentadas: viva-voz, fone com microfone, ou reparo do mic (peça barata).
- Trocar o microfone que o WhatsApp usa internamente — o Android não permite que um app interfira na captura de áudio de outro app sem root.
- Soluções com root (edição de mixer_paths.xml) — risco de brick/Knox; fora do escopo do app, pode virar documentação futura.
- Gradle/Android Studio como toolchain — build direto com aapt2+javac+d8+apksigner já validado no ambiente; mantém o projeto leve e reproduzível via script.

## Context

- Diagnóstico do aparelho do usuário: A15 5G com mic inferior (principal) morto — ligações só via viva-voz, áudios do WhatsApp e gravador não captam; vídeos captam normalmente → mic secundário OK. É um defeito comum (sujeira/flat do mic).
- Toolchain já instalada no ambiente de build: Android SDK command-line tools, platform android-34, build-tools 34.0.0, platform-tools, OpenJDK 21. Caminho: scratchpad da sessão (`android-sdk/`).
- Build sem Gradle: `aapt2 link` (manifest + recursos mínimos) → `javac` contra android.jar → `d8` → empacotar → `zipalign` → `apksigner` (keystore própria do projeto).
- Alvo: minSdk 29 (Android 10, permite salvar via MediaStore sem permissão de storage), targetSdk 34. A15 5G roda Android 14+ (One UI 6).
- Entrega ao usuário: APK disponibilizado para download (GitHub Release ou arquivo servido), instalado por sideload ("fontes desconhecidas").
- Idioma da interface: português (Brasil).

## Constraints

- **Tech stack**: Java puro + Android SDK, sem Gradle, sem dependências externas (AndroidX ausente — UI construída programaticamente) — mantém o build reproduzível com as ferramentas já instaladas.
- **Plataforma**: minSdk 29 / targetSdk 34 — MediaStore scoped storage sem permissões extras; compatível com o A15 5G.
- **Sem root**: a solução deve funcionar em aparelho comum, sem desbloqueio.
- **Distribuição**: APK assinado com keystore do projeto (a mesma sempre, para permitir updates); instruções de sideload em PT-BR.
- **Sem serviços externos**: app 100% offline; nenhum dado sai do aparelho.

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| App gravador próprio em vez de tentar redirecionar mic do sistema | Android não permite reroteamento global sem root | — Pending |
| Fonte CAMCORDER como padrão | É a fonte que usa o mic secundário (o que funciona no A15) | — Pending |
| Build sem Gradle (aapt2+javac+d8+apksigner) | Toolchain leve, já validada no ambiente, reproduzível por script | — Pending |
| MediaStore em vez de FileProvider | URI `content://` compartilhável sem XML extra de provider | — Pending |
| UI programática sem AndroidX | Zero dependências, APK minúsculo, build simples | — Pending |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-07-28 after initialization*
