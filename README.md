# GHBook: leitor e narrador de livros em PDF para Android

App Android inspirado na experiência do Bookey. Você importa um livro em PDF, lê o texto extraído numa interface escura e confortável e **ouve a narração em português do Brasil com voz neural**, inclusive sem internet.

**APK instalável:** [`dist/clone.apk`](dist/clone.apk) (≈ 237 MB, Android 8.0+, assinado; celulares ARM e emuladores x86_64). O tamanho vem quase todo das vozes neurais embutidas.

---

## Como instalar, importar e ouvir

1. Copie `dist/clone.apk` para o celular e abra o arquivo. Se o Android pedir, permita "instalar apps desconhecidos" para o gerenciador de arquivos.
2. Abra o **GHBook** e permita as notificações (elas mostram os controles de mídia e o progresso da geração de áudio).
3. Toque em **Importar PDF** → **Selecionar PDF** e escolha o livro (por exemplo, na pasta *Download*). Também dá para usar *Abrir com* / *Compartilhar* → GHBook a partir de outro app.
4. Quando aparecer **Pronto**, toque em **Ler** ou **Ouvir**.
   - No leitor: toque em um parágrafo → **Ouvir a partir daqui**. O fone no topo abre o player.
   - No player: reproduzir/pausar, voltar 10 s, avançar 30 s, capítulo anterior/próximo, velocidade (0,5× a 3×), parar, barra de progresso do capítulo, indicador do livro e escolha da fonte (**Ao vivo** ou **Áudio gerado**).
5. **Para a melhor voz (Kokoro):** Biblioteca → ícone de download (**Áudios offline**) → **Gerar áudio**. Depois de gerado, o livro toca com a voz Kokoro, sem pausas e sem internet.

> **Onde colocar o seu PDF:** o livro anexado ao pedido não estava acessível no ambiente de desenvolvimento. Coloque o PDF no celular (por exemplo, em *Download*) e importe pelo app. No emulador, arraste o arquivo para a janela. Para os testes automatizados, coloque uma cópia em `samples/` e use `TestUtil.importAndWait("seu-arquivo.pdf")`.

---

## Vozes

### Vozes neurais embutidas (padrão, 100% offline)

Rodam no próprio aparelho com [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) / ONNX Runtime, sem depender do mecanismo de voz do Android:

| Voz | Modelo de alta qualidade (Kokoro-82M) | Voz rápida do mesmo gênero (Piper "high") |
|---|---|---|
| **Dora** (feminina, padrão) | Kokoro `pf_dora` | Dii |
| **Alex** (masculina) | Kokoro `pm_alex` | Miro |
| **Santa** (masculina, grave) | Kokoro `pm_santa` | Miro |

A Kokoro é a voz mais natural, mas é pesada:

| | Síntese (tempo gasto ÷ duração do áudio) |
|---|---|
| Kokoro no PC (4 threads) | ≈ 1,0× |
| Kokoro no emulador | ≈ 1,4–1,7× |
| Piper no PC | ≈ 0,2–0,4× |
| Piper no emulador | ≈ 0,6× |

Por isso o app usa um **modo híbrido automático**:

- **Áudio gerado** (Áudios offline → Gerar áudio): **sempre Kokoro**. Funciona em qualquer aparelho, só leva mais ou menos o tempo do próprio áudio (ou mais) para gerar.
- **Narração ao vivo:** na primeira vez, o app mede a velocidade do aparelho.
  - Se a Kokoro acompanhar a fala (≤ 0,85×), ao vivo usa a Kokoro.
  - Senão, usa a voz rápida do mesmo gênero e mostra um aviso sugerindo gerar o áudio.
  - Se, durante a narração, a Kokoro não acompanhar, o app troca sozinho para a voz rápida a partir do mesmo trecho.
- Em **Configurações** dá para forçar "Sempre Kokoro" ou "Sempre rápida", testar cada voz e ver a velocidade medida neste aparelho.
- A Kokoro ocupa centenas de MB de memória quando carregada; o app a libera quando não está em uso.

### Vozes do sistema (opcional)

Em **Configurações → Vozes do sistema**, dá para usar o mecanismo de voz (TTS) do Android, normalmente o *Speech Services by Google*. Cada voz aparece como **offline**, **requer internet** ou **não instalada**, e o app avisa quando a voz escolhida depende de internet.

---

## O que funciona sem internet

O APK **não tem permissão de internet** (removida no manifesto). Nenhum PDF ou texto sai do aparelho.

| Recurso | Offline? |
|---|---|
| Biblioteca, importação, extração de texto, OCR de páginas digitalizadas (ML Kit embarcado) | ✅ |
| Leitura, capítulos, páginas, ajustes de fonte/tema, progresso salvo | ✅ |
| **Narração ao vivo com voz neural** (Kokoro ou rápida) | ✅ sempre |
| **Áudio gerado** com Kokoro (.m4a por capítulo): gravação e reprodução | ✅ |
| Narração com voz do sistema | ✅ só se a voz estiver marcada como *offline* |

**As três formas de conteúdo:**
- **Texto extraído:** o texto do PDF, guardado no aparelho e usado na leitura.
- **Narração ao vivo:** a voz lê o texto em tempo real. A posição é estimada pelo número de caracteres e a sincronização com o texto é por parágrafo.
- **Áudio gerado:** arquivos AAC/M4A (~0,5 MB por minuto) gravados uma vez com a Kokoro. Tocam sem pausas, com tempos exatos e sincronização por parágrafo.

---

## O que foi implementado

**Importação e extração** (`pdf/`, `importer/`)
- Seletor de documentos do Android (sem permissão de armazenamento), além de *Abrir com* e *Compartilhar*.
- PdfBox-Android extrai o texto com a posição e o tamanho da fonte de cada linha, em lotes de 16 páginas.
- Limpeza:
  - remove cabeçalhos e rodapés repetidos, números de página, notas de rodapé e páginas de sumário;
  - junta as linhas em parágrafos, inclusive parágrafos que atravessam páginas;
  - desfaz a hifenização e une letras capitulares.
- Capítulos: vêm do sumário embutido (bookmarks) do PDF; sem ele, dos títulos detectados por tamanho de fonte, negrito ou padrões como "Capítulo", "Parte" ou "Prólogo"; sem nenhum dos dois, de blocos de 10 páginas.
- **OCR local:** páginas sem texto mas com imagem são reconhecidas com o ML Kit (modelo embutido). Páginas que nem o OCR consegue ler são listadas ao usuário, e não tratadas como páginas vazias.
- Erros tratados:
  - PDF inválido, com senha, sem texto ou que não é PDF;
  - falta de espaço e falta de memória;
  - PDF duplicado (detectado por SHA-256);
  - importação interrompida, que é retomada ao reabrir o app;
  - cancelamento pelo usuário.

**Leitura** (`ui/ReaderScreen.kt`)
- Texto em parágrafos, com títulos destacados.
- Navegação por página (anterior/próxima e *ir para página*) e por capítulo.
- Ajustes: tamanho da fonte, espaçamento entre linhas e entre parágrafos, quatro temas (Escuro, Preto, Sépia, Claro) e fonte serifada ou sem serifa. Os ajustes são salvos.
- O parágrafo narrado fica destacado e o texto rola junto com a narração.
- O progresso é salvo automaticamente.

**Narração** (`playback/`, `neural/`, `tts/`)
- `NeuralPlayer`: um player do Media3 (`SimpleBasePlayer`) com voz neural.
  - Uma thread sintetiza frase a frase à frente (no máximo ~24 frases) e outra toca o áudio num `AudioTrack` do próprio app.
  - A velocidade é aplicada no `AudioTrack`, sem alterar o tom.
  - O modelo Kokoro ou rápido é escolhido pela velocidade medida do aparelho.
- `TtsPlayer`: o mesmo modelo de player para as vozes do sistema (`TextToSpeech`).
- `PlaybackService` (`MediaSessionService`): serviço em primeiro plano.
  - A narração continua com a tela bloqueada.
  - Há controles na notificação e na tela de bloqueio.
  - Teclas de mídia de fones (com fio ou Bluetooth) funcionam via `MediaButtonReceiver`.
- Alterna entre narração ao vivo e áudio gerado no mesmo trecho. Se o próximo capítulo ainda não tem áudio gerado, segue ao vivo.
- Texto preparado para a fala:
  - expande abreviações (Sr., Dra., nº, pág., etc.);
  - converte numerais romanos de títulos ("Capítulo IV" → "Capítulo 4");
  - remove marcadores de nota;
  - insere pausas após títulos e entre parágrafos;
  - divide o texto em frases.

**Áudio offline** (`audio/`)
- Um WorkManager em primeiro plano sintetiza cada trecho com a Kokoro, codifica em AAC com MediaCodec e MediaMuxer e grava um `.m4a` por capítulo (`cap_001.m4a`…), mais o mapa parágrafo→tempo.
- Há notificação de progresso com o botão **Cancelar**.
- **Retomada:** capítulos prontos são mantidos. Se o app for encerrado ou atualizado, o WorkManager retoma sozinho.
- Antes de cada capítulo, o espaço livre é verificado.

**Interface** (`ui/`): Jetpack Compose com Material 3, ajustada para telas pequenas. Telas: Biblioteca, Importar PDF, Leitor, Player, Capítulos e progresso, Áudios offline e Configurações.
- **Identidade visual GHBook:** preto `#0A0A0A`, branco e azul-escuro `#0B2A6B` (cores exatas do logo). O app segue o tema claro/escuro do sistema; no escuro, o azul é clareado (`#B3C5FF`) para manter o contraste.
- **Logo em vetor** (`res/drawable/ic_brand_mark.xml`), reconstruído a partir da geometria do logo original: ícone adaptativo do launcher, ícone temático (Android 13+), ícone das notificações e marca/wordmark "GHBOOK" dentro do app.
- Design minimalista: cartão "Continuar" em preto, botão de reproduzir no preto da marca (branco no modo escuro), barras de progresso e sliders finos, cartões com borda sutil, velocidade em painel inferior.
- O leitor ajusta os ícones da barra de status ao tema de leitura (Claro/Sépia/Escuro/Preto).
- Componentes compartilhados em `ui/Components.kt` (`BrandMark`, `Wordmark`, `BackTopBar`, `PlayPauseButton`, `BrandSlider`, `ProgressLine`, `OutlinedCard`…) e paleta em `ui/theme/Theme.kt`.

---

## Testes executados

Ambiente: emulador Android 14 (API 34, x86_64, 4 núcleos) **em modo avião**.

**Automatizados**

| Teste | Resultado |
|---|---|
| Unitários (13): limpeza de texto, parágrafos, hifenização, capítulos, sumário, abreviações, segmentação | ✅ 13/13 |
| Instrumentados (11), todos ✅. Cobrem: | ✅ 11/11 |
| ‣ extração, limpeza, capítulos e **OCR** do PDF de teste | |
| ‣ sumário embutido define os capítulos | |
| ‣ PDF com senha / arquivo que não é PDF | |
| ‣ importação ponta a ponta, deduplicação e progresso persistido | |
| ‣ **vozes neurais Kokoro e rápida sintetizando pt-BR offline**, com cancelamento no meio | |
| ‣ voz do sistema como alternativa | |
| ‣ narração ao vivo neural: pausar, retomar, velocidade, +30 s, próximo capítulo, parar, progresso | |
| ‣ **geração Kokoro dos 5 capítulos** (AAC, durações conferidas, voz registrada) + retomada sem refazer capítulos | |
| ‣ reprodução do áudio gerado, seek, sincronização de parágrafo e retomada da posição | |

**Manuais no APK de release, clicando na interface**

| Teste | Resultado |
|---|---|
| Primeiro uso: permissão de notificação, biblioteca vazia | ✅ |
| Erros de importação: "não é um PDF válido", "protegido por senha", descartar | ✅ |
| Importação pelo seletor do Android (3 s, OCR de 1 página) e detecção de PDF duplicado | ✅ |
| Leitor: rótulo do capítulo, "Ouvir a partir daqui", destaque e rolagem acompanhando a narração | ✅ (corrigido nesta rodada) |
| Player com áudio Kokoro: pausar, retomar, +30 s, −10 s, 1,5×, próximo/anterior, parar → retomar | ✅ |
| Troca "Ao vivo" ↔ "Áudio gerado" no mesmo trecho | ✅ |
| Teste de voz Kokoro nas Configurações; medição do aparelho → escolha automática da voz rápida | ✅ |
| Notificação de mídia; narração neural com a **tela bloqueada**; teclas de fone (pausar/tocar/voltar) | ✅ |
| Ajustes de leitura salvos após fechar o app à força | ✅ |
| Excluir áudios gerados; remover livro | ✅ |
| Tela pequena (720×1280) | ✅ (layout do player ajustado) |
| Build de release com R8, assinado e verificado com `apksigner`; APK sem permissão de internet | ✅ |

**Problemas encontrados e corrigidos durante os testes:**
- travamento nativo do callback JNI do sherpa-onnx (R8 + lambdas do Kotlin 2);
- rolagem que cortava o parágrafo narrado;
- rótulo errado da voz enquanto medindo o aparelho;
- botão de play sem rótulo acessível enquanto carrega;
- indicador de carregamento aparecendo pausado;
- capa grande demais em telas pequenas;
- liberação da memória da Kokoro com contador de uso (evita liberar no meio da geração).

**Rodada de QA + redesign (2026-10-14), no APK de release:**
- Revisão de código da lógica: 13 bugs corrigidos. Os principais:
  - reimportação do PDF de "Abrir com" ao recriar a activity;
  - player acessado fora da thread principal pelo worker de geração;
  - vazamento de MediaCodec ao cancelar a geração;
  - páginas duplicadas quando um lote de extração falhava;
  - OCR abortado após erro de renderização;
  - narração travada após erro no último trecho;
  - áudio órfão depois do release (TTS e Kokoro);
  - crash no botão "Testar" voz;
  - parágrafo zerado ao iniciar a narração;
  - pasta órfã ao remover livro durante a importação;
  - corrida que sobrescrevia o status do livro.
- Interface: "Reproduzir" no fim do livro não fazia nada (agora recomeça do início, com o botão "Ouvir do início"); ícones da barra de status invisíveis nos temas de leitura claros; números em formato brasileiro (1,6 · 1,3×); botão quebrando linha em Configurações; controles do player fora da tela em 720×1280; leitura de manifesto de áudio na thread principal.
- Validado clicando: biblioteca vazia, importação (válida e "não é PDF"), leitor (temas, destaque, "Ouvir a partir daqui"), player (velocidade, fonte do áudio, fim do livro), capítulos, áudios offline, configurações, remoção, notificação de mídia, modo claro/escuro do sistema, rotação e tela pequena. 13/13 unitários e 11/11 instrumentados ✅.

**Não testado:**
- aparelho físico (o desempenho da Kokoro em celulares ARM não foi medido);
- fone Bluetooth real (simulado com `cmd media_session dispatch`);
- pausa ao desconectar o fone e retomada pela tecla de fone com o app fechado;
- mecanismos de TTS de outros fabricantes;
- PDFs reais de editoras (só os PDFs gerados em `samples/`).

---

## Limitações conhecidas

- **Tamanho do APK (~237 MB)** por causa dos modelos neurais: Kokoro 137 MB e duas vozes Piper com 37 MB.
- **Kokoro ao vivo** depende do processador. Em aparelhos que não acompanham a fala, ao vivo usa a voz rápida, e a Kokoro fica para o áudio gerado.
- **Geração com Kokoro é demorada:** aproximadamente o tempo do próprio áudio, ou mais. Um livro de 8 h pode levar a noite toda. O Android 15 limita serviços `dataSync` a 6 h por dia; se pausada, a geração é retomada depois.
- **Memória:** a Kokoro carregada usa várias centenas de MB de memória nativa. Em aparelhos com pouca RAM, use a voz rápida.
- **Posição na narração ao vivo:** é estimada. No áudio gerado, os tempos são exatos.
- **Layout:** PDFs com várias colunas, tabelas ou layout complexo podem ter a ordem do texto imperfeita.
- **OCR:** reconhece apenas o alfabeto latino. Pode errar letras (no teste, "OCR" foi lido como "CR").
- **Geração de áudio online** (Azure, Google Cloud, ElevenLabs): não implementada, por escolha. Toda a geração é local.

---

## Compilar

Requisitos: JDK 17 e Android SDK (platform 35, build-tools 35). O `local.properties` aponta para `C:/Users/Windows/Android/Sdk`.

```bash
./gradlew testDebugUnitTest          # testes unitários
./gradlew connectedDebugAndroidTest  # testes instrumentados (emulador/aparelho conectado)
./gradlew assembleRelease            # APK em app/build/outputs/apk/release/
```

- **Chave de assinatura:** fica em `signing/` (`ghbook-release.jks` + `keystore.properties`) e não faz parte do código-fonte. **Faça backup dessa pasta:** sem ela não é possível publicar atualizações que instalem por cima da versão atual.
- **Modelos neurais:** ficam em `app/src/main/assets/kokoro` e `app/src/main/assets/piper`. O módulo nativo é `app/libs/sherpa-onnx-1.13.8.aar`, baixado do GitHub oficial.
- **PDFs de teste:** `python tools/make_test_pdf.py` gera os arquivos em `samples/`.
- **Amostras das vozes:** ficam em `amostras-voz/`.

## Estrutura

```
app/src/main/java/br/com/ghbook/
  data/       modelos, repositório de livros (JSON em filesDir/books/<id>/), configurações
  pdf/        PdfExtractor (PdfBox + OCR ML Kit), TextCleaner (limpeza/parágrafos/capítulos)
  importer/   ImportManager (cópia, processamento em segundo plano, erros, retomada)
  neural/     NeuralEngine (Kokoro + Piper via sherpa-onnx, medição do aparelho), NeuralPreview
  tts/        SpeechText (normalização pt-BR, segmentação), TtsVoices (vozes do sistema)
  playback/   NeuralPlayer, TtsPlayer (Media3), PlaybackService (MediaSession), PlaybackHub
  audio/      AudioGenerationWorker (WorkManager), AacEncoder (PCM→AAC/M4A), AudioStore
  ui/         telas em Compose
```

## Licenças de terceiros

- PdfBox-Android, AndroidX/Jetpack Compose/Media3/WorkManager e kotlinx: Apache 2.0.
- ML Kit Text Recognition: Termos do Google ML Kit.
- **sherpa-onnx**: Apache 2.0. **ONNX Runtime**: MIT.
- **Kokoro-82M**: Apache 2.0.
- **eSpeak NG** (fonética, embutido no sherpa-onnx): **GPL-3.0**. Para uso pessoal não há problema; para distribuição comercial, avalie as obrigações da GPL.
- **Vozes Piper pt-BR "dii" e "miro"** (OpenVoiceOS, via sherpa-onnx): os arquivos do modelo não declaram licença explicitamente; confira em huggingface.co/OpenVoiceOS antes de distribuir.

Nenhum código, logotipo ou elemento proprietário do Bookey foi usado.
