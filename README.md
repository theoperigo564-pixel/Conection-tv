# Conection TV

Aplicativo Android para espelhar a tela de um celular Android em uma Android TV
pela mesma rede Wi-Fi. O APK inclui suporte a `armeabi-v7a` (ARM 32 bits) e
`arm64-v8a` (ARM 64 bits).

## Como usar

1. Instale o mesmo APK no celular e na Android TV.
2. Conecte os dois dispositivos à mesma rede Wi-Fi.
3. Abra o aplicativo na TV e mantenha-o aberto.
4. No celular, selecione a TV encontrada e autorize a captura de tela.

A TV recebe vídeo H.264 em tela cheia. A captura usa a API de projeção do
Android, portanto o sistema mostra uma confirmação e o usuário pode interromper
a transmissão pela notificação do Android.

## Requisitos para compilar

- JDK 17
- Android SDK com a plataforma 35 e Build Tools 35.0.0
- Gradle 8.9 ou compatível com Android Gradle Plugin 8.7.3

Compile com `gradle assembleDebug` na raiz do projeto. O APK será gerado em
`app/build/outputs/apk/debug/app-debug.apk`.

O espelhamento de áudio não está incluído nesta primeira versão; a transmissão
é de vídeo da tela.
