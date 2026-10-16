package br.com.ghbook.audio

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import br.com.ghbook.AppGraph
import br.com.ghbook.GhBookApp
import br.com.ghbook.R
import br.com.ghbook.data.ChapterAudio
import br.com.ghbook.neural.NeuralEngine
import br.com.ghbook.neural.NeuralModel
import br.com.ghbook.tts.SpeechText
import br.com.ghbook.tts.TtsVoices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException

/** Fila de geração de áudio (WorkManager): sobrevive ao fechamento do app e é retomada após interrupções. */
class AudioGenerationManager(private val context: Context) {
    private val wm get() = WorkManager.getInstance(context)

    fun uniqueName(bookId: String) = "audio-$bookId"

    fun start(bookId: String) {
        val req = OneTimeWorkRequestBuilder<AudioGenerationWorker>()
            .setInputData(workDataOf(AudioGenerationWorker.KEY_BOOK to bookId))
            .addTag("audio")
            .build()
        wm.enqueueUniqueWork(uniqueName(bookId), ExistingWorkPolicy.KEEP, req)
    }

    fun cancel(bookId: String) {
        wm.cancelUniqueWork(uniqueName(bookId))
    }

    fun observe(bookId: String): Flow<WorkInfo?> =
        wm.getWorkInfosForUniqueWorkFlow(uniqueName(bookId)).map { list -> list.lastOrNull() }
}

/**
 * Gera um arquivo .m4a por capítulo com a voz do TextToSpeech do aparelho:
 * sintetiza cada trecho em WAV temporário, codifica em AAC e grava o mapa parágrafo→tempo.
 * Capítulos concluídos são preservados; ao retomar, só os capítulos faltantes são gerados.
 */
class AudioGenerationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_BOOK = "book"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_CHAPTER = "chapter"
        const val KEY_CHAPTER_FRACTION = "chapterFraction"
        const val KEY_MESSAGE = "message"
        const val KEY_ERROR = "error"
        private const val MAX_SEGMENT = 1500
        /** AAC 64 kbps ≈ 0,5 MB por minuto. */
        private const val BYTES_PER_MS = 8L
    }

    private val bookId = inputData.getString(KEY_BOOK) ?: ""
    private val notifId = 2000 + (bookId.hashCode() and 0xFFF)

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Preparando geração de áudio…", 0, 0)

    private fun foregroundInfo(text: String, done: Int, total: Int): ForegroundInfo {
        val title = AppGraph.books.book(bookId)?.title ?: "Livro"
        val n: Notification = NotificationCompat.Builder(applicationContext, GhBookApp.CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Gerando áudio: $title")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, done, total == 0)
            .addAction(0, "Cancelar", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notifId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(notifId, n)
    }

    override suspend fun doWork(): Result {
        AppGraph.init(applicationContext)
        val books = AppGraph.books
        val store = AppGraph.audio
        val meta = books.book(bookId) ?: return Result.failure(workDataOf(KEY_ERROR to "Livro não encontrado"))
        val toc = books.toc(bookId)
        if (toc.isEmpty()) return fail("O livro não tem texto extraído.")

        runCatching { setForeground(foregroundInfo("Iniciando…", 0, toc.size)) }

        // Voz neural embutida (Kokoro) ou voz do sistema (TextToSpeech), conforme as Configurações.
        val preferred = AppGraph.settings.current.voiceName
        val neural = NeuralEngine.isNeural(preferred)
        val neuralVoice = NeuralEngine.voiceFor(preferred)
        var tts: TextToSpeech? = null
        try {
            val voiceName: String
            val maxLen: Int
            val msPerChar: Long
            if (neural) {
                NeuralEngine.acquireKokoro()
                runCatching { setForeground(foregroundInfo("Carregando a voz neural…", 0, toc.size)) }
                if (!NeuralEngine.load(applicationContext, NeuralModel.KOKORO, neuralVoice)) {
                    return fail(NeuralEngine.lastError ?: "A voz neural não pôde ser carregada.")
                }
                voiceName = neuralVoice.key
                maxLen = 600
                msPerChar = NeuralEngine.MS_PER_CHAR
            } else {
                val engine = TtsVoices.create(applicationContext) ?: return fail("Nenhum mecanismo de síntese de voz (TTS) disponível no aparelho.")
                tts = engine
                val selection = withContext(Dispatchers.Main) {
                    TtsVoices.select(engine, preferred).also { TtsVoices.apply(engine, it) }
                }
                engine.setSpeechRate(1.0f)
                engine.setPitch(AppGraph.settings.current.pitch)
                voiceName = selection.info?.name ?: ""
                maxLen = runCatching { TextToSpeech.getMaxSpeechInputLength() - 100 }.getOrDefault(3900).coerceAtMost(MAX_SEGMENT)
                msPerChar = SpeechText.MS_PER_CHAR
            }

            val tmpDir = File(applicationContext.cacheDir, "tts-$bookId").apply { deleteRecursively(); mkdirs() }
            val outDir = store.dir(bookId).apply { mkdirs() }

            var done = store.manifest(bookId).chapters.count { c -> toc.any { it.index == c.chapter } }
            for (entry in toc) {
                if (isStopped || books.book(bookId) == null) return Result.failure()
                if (store.manifest(bookId).forChapter(entry.index) != null) continue
                val chapter = books.chapterBlocking(bookId, entry.index) ?: continue
                val segments = SpeechText.segments(chapter, maxLen)
                if (segments.isEmpty()) continue

                // Espaço livre: estimativa do capítulo + margem de 50 MB.
                val estimate = SpeechText.totalChars(segments) * msPerChar * BYTES_PER_MS
                if (outDir.usableSpace < estimate + 50L * 1024 * 1024) {
                    return fail("Espaço de armazenamento insuficiente para gerar o capítulo ${entry.index + 1}. Libere espaço e toque em Retomar.")
                }

                val partial = File(outDir, store.chapterFileName(entry.index) + ".part")
                partial.delete()
                var encoder: AacEncoder? = null
                val starts = LongArray(chapter.paragraphs.size) { -1L }
                try {
                    segments.forEachIndexed { si, seg ->
                        if (isStopped || books.book(bookId) == null) throw CancelledException()
                        val next = segments.getOrNull(si + 1)
                        if (neural) {
                            val enc = encoder ?: AacEncoder(partial, NeuralEngine.sampleRate(NeuralModel.KOKORO), 1).also { encoder = it }
                            if (starts[seg.paragraph] < 0) starts[seg.paragraph] = enc.writtenMs
                            var stopped = false
                            val beforeMs = enc.writtenMs
                            val t0 = System.nanoTime()
                            val ok = NeuralEngine.synthesize(applicationContext, seg.text, neuralVoice, NeuralModel.KOKORO) { samples ->
                                if (isStopped || books.book(bookId) == null) {
                                    stopped = true
                                    false
                                } else {
                                    enc.appendFloat(samples)
                                    true
                                }
                            }
                            if (stopped) throw CancelledException()
                            if (!ok) throw SynthesisException(NeuralEngine.lastError ?: "A voz neural falhou.")
                            val audioMs = enc.writtenMs - beforeMs
                            if (audioMs > 2000) NeuralEngine.recordKokoroRtf(applicationContext, ((System.nanoTime() - t0) / 1e6f) / audioMs)
                            enc.appendSilence(
                                when {
                                    seg.isHeading -> 700
                                    next != null && next.paragraph != seg.paragraph -> 300
                                    else -> 80
                                },
                            )
                        } else {
                            val wav = File(tmpDir, "seg_$si.wav")
                            synthesize(tts!!, seg.text, wav, "g-${entry.index}-$si")
                            val enc = encoder ?: Wav.read(wav).let { info ->
                                if (info.bitsPerSample != 16) throw IOException("O mecanismo de voz gerou áudio em formato não suportado (${info.bitsPerSample} bits).")
                                AacEncoder(partial, info.sampleRate, info.channels.coerceIn(1, 2))
                            }.also { encoder = it }
                            if (starts[seg.paragraph] < 0) starts[seg.paragraph] = enc.writtenMs
                            enc.appendWav(wav)
                            enc.appendSilence(if (seg.isHeading) 700 else 250)
                            wav.delete()
                        }
                        val fraction = (si + 1).toFloat() / segments.size
                        setProgress(progressData(done, toc.size, entry.index, fraction, "Capítulo ${entry.index + 1} de ${toc.size}: ${(fraction * 100).toInt()}%"))
                        if (si % 5 == 0) runCatching { setForeground(foregroundInfo("Capítulo ${entry.index + 1} de ${toc.size} · ${(fraction * 100).toInt()}%", done, toc.size)) }
                    }
                    val enc = encoder ?: continue
                    val durationMs = enc.writtenMs
                    enc.finish()
                    encoder = null
                    val finalFile = store.chapterFile(bookId, entry.index)
                    finalFile.delete()
                    if (!partial.renameTo(finalFile)) throw IOException("Falha ao salvar o arquivo de áudio.")
                    // Parágrafos sem fala (ex.: só símbolos) herdam o início do anterior.
                    var last = 0L
                    val filled = starts.map { s -> if (s >= 0) { last = s; s } else last }
                    store.putChapter(
                        bookId,
                        ChapterAudio(entry.index, finalFile.name, durationMs, finalFile.length(), filled, voiceName),
                        voiceName,
                    )
                    done++
                    setProgress(progressData(done, toc.size, entry.index, 1f, "Capítulo ${entry.index + 1} concluído"))
                } catch (e: CancelledException) {
                    encoder?.release()
                    partial.delete()
                    return Result.failure()
                } catch (e: IOException) {
                    encoder?.release()
                    partial.delete()
                    val msg = if (e.message?.contains("ENOSPC") == true || e.message?.contains("No space") == true) {
                        "Espaço de armazenamento insuficiente. Libere espaço e toque em Retomar."
                    } else "Erro ao gerar o capítulo ${entry.index + 1}: ${e.message}"
                    return fail(msg)
                } catch (e: SynthesisException) {
                    encoder?.release()
                    partial.delete()
                    return fail(e.message ?: "Falha na síntese de voz.")
                } catch (e: CancellationException) {
                    // Worker cancelado durante setProgress/suspensão: libera o MediaCodec/MediaMuxer antes de propagar.
                    encoder?.release()
                    partial.delete()
                    throw e
                } catch (e: Exception) {
                    // Erros do MediaCodec/MediaMuxer (IllegalStateException, CodecException…) não podem vazar o codificador.
                    encoder?.release()
                    partial.delete()
                    return fail("Erro ao gerar o capítulo ${entry.index + 1}: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            tmpDir.deleteRecursively()
            store.setError(bookId, null)
            AppGraph.playback.onAudioChanged(bookId)
            return Result.success(progressData(done, toc.size, -1, 1f, "Áudio de ${meta.title} gerado."))
        } finally {
            runCatching { tts?.shutdown() }
            if (neural) {
                NeuralEngine.releaseKokoroUser()
                NeuralEngine.releaseKokoroIfIdle()
            }
        }
    }

    private fun fail(message: String): Result {
        AppGraph.audio.setError(bookId, message)
        return Result.failure(workDataOf(KEY_ERROR to message))
    }

    private fun progressData(done: Int, total: Int, chapter: Int, fraction: Float, message: String): Data = workDataOf(
        KEY_DONE to done, KEY_TOTAL to total, KEY_CHAPTER to chapter, KEY_CHAPTER_FRACTION to fraction, KEY_MESSAGE to message,
    )

    private class CancelledException : Exception()
    private class SynthesisException(message: String) : Exception(message)

    private suspend fun synthesize(tts: TextToSpeech, text: String, out: File, utteranceId: String) {
        val result = CompletableDeferred<Int>()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { if (id == utteranceId) result.complete(TextToSpeech.SUCCESS) }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { if (id == utteranceId) result.complete(TextToSpeech.ERROR) }
            override fun onError(id: String?, errorCode: Int) { if (id == utteranceId) result.complete(errorCode) }
        })
        out.delete()
        val queued = tts.synthesizeToFile(text, Bundle(), out, utteranceId)
        if (queued != TextToSpeech.SUCCESS) throw SynthesisException("O mecanismo de voz recusou o texto para síntese.")
        val code = try {
            withTimeout(60_000L + text.length * 150L) { result.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw SynthesisException("A síntese de voz parou de responder. Toque em Retomar para continuar de onde parou.")
        }
        if (code != TextToSpeech.SUCCESS) {
            throw SynthesisException(
                when (code) {
                    TextToSpeech.ERROR_NETWORK, TextToSpeech.ERROR_NETWORK_TIMEOUT -> "A voz escolhida precisa de internet e a conexão falhou. Escolha uma voz offline."
                    TextToSpeech.ERROR_NOT_INSTALLED_YET -> "Os dados da voz não estão instalados."
                    else -> "Falha na síntese de voz (código $code)."
                },
            )
        }
        if (!out.exists() || out.length() < 44) throw SynthesisException("O mecanismo de voz não gerou o arquivo de áudio.")
    }
}
