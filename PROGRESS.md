# PROGRESS — GHBook (clone do Bookey para Android)

Estado inicial (2026-10-13): pasta do projeto vazia; nenhum PDF anexado encontrado no ambiente.
JDK 17 disponível; Android SDK e Gradle ausentes (serão instalados em `C:\Users\Windows\Android\Sdk` e via wrapper).

## Decisões
- Kotlin + Jetpack Compose (Material 3, dark mode), minSdk 26, target/compile 35.
- PDF: PdfBox-Android (Apache 2.0) para texto com posição/fonte; PdfRenderer + ML Kit Text Recognition (modelo embarcado, offline) para OCR de páginas digitalizadas.
- Persistência: arquivos JSON (kotlinx.serialization) em `filesDir/books/<id>/` + SharedPreferences.
- Narração: Android TextToSpeech dentro de um `SimpleBasePlayer` (Media3) num `MediaSessionService` (notificação/lockscreen).
- Áudio offline: `synthesizeToFile` (WAV) por segmento → transcodificação AAC/M4A (MediaCodec+MediaMuxer) por capítulo, via WorkManager em primeiro plano; mapa de sincronização parágrafo→ms.

## Checklist
- [x] SDK/Gradle instalados
- [x] Projeto Gradle criado
- [x] Extração de PDF + heurísticas (cabeçalho/rodapé/nº página/parágrafos/capítulos)
- [x] OCR
- [x] Biblioteca/import
- [x] Leitor
- [x] Player TTS + MediaSession
- [x] Geração de áudio offline + player de arquivos
- [x] Configurações (voz/velocidade/leitura)
- [x] Testes unitários
- [x] Emulador + testes instrumentados / fluxo manual via adb
- [x] APK release em dist/clone.apk
- [x] README com instruções e limitações

## Log
- 2026-10-13: código completo escrito; `assembleDebug` OK. Emulador `ghbook34` (Android 14, Google TTS) criado e iniciado. Próximo: testes unitários, PDF de teste, testes no emulador, release.
- 2026-10-13: 13 testes unitários + 9 instrumentados OK (modo avião); testes manuais do APK de release via adb (importação, leitor, player, notificação, tela bloqueada, teclas de fone, geração/cancelar/retomar/matar app, remoção, reinício offline). `dist/clone.apk` gerado e verificado. CONCLUÍDO.
- 2026-10-14: voz neural offline (Kokoro + Piper rápida, modo híbrido automático) integrada via sherpa-onnx; bateria completa de testes clicando no APK de release; 13 unitários + 11 instrumentados OK; dist/clone.apk (237 MB) atualizado.
- 2026-10-14: QA/code review (13 bugs de lógica + 7 de interface corrigidos) e redesign com a identidade GHBook (preto/branco/azul-escuro #0B2A6B, logo em vetor no launcher/notificação/app, tema claro/escuro do sistema). 13 unitários + 11 instrumentados OK; fluxos validados no APK de release; dist/clone.apk atualizado.
