package br.com.ghbook.neural

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams

/** Toca uma frase de exemplo com a voz neural (botão "Testar" das Configurações). */
object NeuralPreview {
    @Volatile private var track: AudioTrack? = null
    @Volatile private var token = 0

    /** Executa em segundo plano; [onDone] recebe null em caso de sucesso ou a mensagem de erro. */
    fun play(context: Context, voice: NeuralVoice, model: NeuralModel, text: String, speed: Float, onDone: (String?) -> Unit) {
        stop()
        val my = synchronized(this) { ++token }
        Thread({
            // Exceção não tratada numa Thread comum encerraria o app: qualquer falha vira mensagem de erro.
            try {
                val chunks = ArrayList<FloatArray>()
                NeuralEngine.load(context, model, voice)
                val t0 = System.nanoTime()
                val ok = NeuralEngine.synthesize(context, text, voice, model) { s -> chunks += s; my == token }
                if (!ok) { onDone(NeuralEngine.lastError ?: "A voz neural não pôde ser carregada."); return@Thread }
                if (my != token) { onDone(null); return@Thread }
                val total = chunks.sumOf { it.size }
                val rate = NeuralEngine.sampleRate(model)
                if (model == NeuralModel.KOKORO && total > 0) {
                    NeuralEngine.recordKokoroRtf(context, ((System.nanoTime() - t0) / 1e9f) / (total.toFloat() / rate))
                }
                val all = FloatArray(total).also { a -> var o = 0; chunks.forEach { c -> c.copyInto(a, o); o += c.size } }
                val t = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(maxOf(total * 4, 4096))
                    .build()
                t.write(all, 0, total, AudioTrack.WRITE_BLOCKING)
                runCatching { t.playbackParams = PlaybackParams().setSpeed(speed) }
                synchronized(this) {
                    // Outro teste (ou stop) começou enquanto este sintetizava: não toca por cima nem vaza o AudioTrack.
                    if (my != token) {
                        runCatching { t.release() }
                    } else {
                        track = t
                        t.play()
                    }
                }
                onDone(null)
            } catch (t: Throwable) {
                onDone("Falha ao testar a voz neural: ${t.message ?: t.javaClass.simpleName}")
            }
        }, "neural-preview").start()
    }

    @Synchronized
    fun stop() {
        // Invalida também um teste ainda em síntese (ex.: tela de Configurações fechada antes de tocar).
        token++
        track?.let { runCatching { it.stop() }; runCatching { it.release() } }
        track = null
    }
}
