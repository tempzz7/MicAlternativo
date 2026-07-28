# MicAlternativo 🎙️

App Android que contorna **microfone principal defeituoso** — grava áudio usando o microfone secundário que ainda funciona (o mesmo do viva-voz e da câmera de vídeo) e compartilha direto no WhatsApp.

## O problema

Em muitos celulares (caso típico: Samsung Galaxy A15 5G), o microfone inferior (principal) morre ou entope:

- ❌ Áudios do WhatsApp saem mudos
- ❌ Gravador do aparelho não capta
- ❌ Ligações só funcionam no viva-voz
- ✅ Vídeos gravam som normalmente ← **o mic secundário funciona!**

## A solução

O Android permite que um app escolha a **fonte de áudio** das próprias gravações. A fonte `CAMCORDER` usa o microfone secundário. O MicAlternativo:

1. Grava áudio pela fonte que funciona (com teste de todas as fontes disponíveis)
2. Permite ouvir antes de enviar
3. Compartilha em um toque para o WhatsApp (chega como áudio tocável)

### O que ele NÃO faz (limite do Android, sem root)

- Não troca o microfone de **ligações** (use viva-voz ou fone com mic)
- Não troca o microfone que o **WhatsApp usa internamente** — o fluxo é: grave aqui → compartilhe lá

## Status

🚧 Em desenvolvimento — projeto orquestrado com [GSD](https://github.com/opengsd/gsd-core). Veja `.planning/ROADMAP.md`.

## Build

Sem Gradle: `aapt2` + `javac` + `d8` + `apksigner` via `scripts/build.sh` (documentação completa na fase de build).

## Licença

A definir (intenção: open source).
