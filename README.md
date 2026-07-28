<div align="center">

# SIDEMIC

**O microfone reserva que seu telefone já tem.**

Quando o microfone principal do celular quebra ou entope, os vídeos continuam com som — porque a câmera usa outro microfone. O Sidemic grava por esse microfone reserva e envia o áudio ao WhatsApp, Instagram ou Telegram.

Sem root · sem internet · sem conta · sem anúncio

[**⬇ Baixar a última versão**](https://github.com/tempzz7/MicAlternativo/releases/latest)

</div>

---

## O problema

O microfone inferior (o furinho perto do conector) é o que o Android usa para chamadas, gravador e mensagens de voz. Quando ele falha:

- Áudios de mensagem saem mudos
- O gravador do sistema não capta
- Chamadas só funcionam no viva-voz
- **Mas vídeos gravam som normalmente** — prova de que o microfone secundário está intacto

## A solução

O Android permite que um aplicativo escolha por qual entrada ele grava. O Sidemic expõe essa escolha:

| Entrada | O que usa |
|---|---|
| **Câmera** *(padrão)* | microfone de trás/cima — o que costuma funcionar |
| Principal | microfone de baixo — o que costuma estar com defeito |
| Voz | ganho cru, sem tratamento |
| Chamada | com cancelamento de eco |
| Sistema | escolha automática do Android |

## Recursos

- Medidor de nível ao vivo — confirma na hora se a entrada está captando
- Reprodução antes de enviar
- Envio direto ao WhatsApp, Instagram e Telegram (apenas os instalados aparecem)
- Contato rápido: salve um número e ganhe um atalho de capturar-e-enviar
- Bloco nas configurações rápidas e atalho no ícone — capturar em um toque
- Biblioteca das capturas, salvas em `Música/Sidemic` e visíveis no gerenciador de arquivos

## Limites honestos

O Android **não permite**, sem root, que um aplicativo troque o microfone usado por outro aplicativo ou pelo sistema. Portanto:

- **Chamadas** continuam exigindo viva-voz ou fone com microfone
- O **botão de gravar dentro do WhatsApp** segue usando o microfone com defeito — o fluxo é capturar no Sidemic e enviar
- Um **fone com microfone** (com fio ou Bluetooth) redireciona tudo, inclusive chamadas — é a solução mais completa enquanto o microfone não é consertado

## Build

Sem Gradle. Pipeline em um comando:

```bash
scripts/build.sh          # aapt2 → javac → d8 → zipalign → apksigner + gates
scripts/test_build.sh     # harness end-to-end independente
```

Requer Android SDK (platform 34, build-tools 34/35) e um JDK. `ANDROID_HOME`, `KEYSTORE_PATH` e `KEYSTORE_ENV` são configuráveis por variável de ambiente.

## Licença

A definir.
