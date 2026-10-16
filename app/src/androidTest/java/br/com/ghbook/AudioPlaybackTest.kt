package br.com.ghbook

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import br.com.ghbook.audio.AudioGenerationWorker
import br.com.ghbook.data.PlaybackMode
import br.com.ghbook.neural.NeuralEngine
import br.com.ghbook.neural.NeuralModel
import br.com.ghbook.tts.TtsVoices
import org.junit.Assert.assertFalse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * Testes de narração: vozes, reprodução TTS (play/pausa/retomada/velocidade/progresso),
 * geração de áudio offline e reprodução do áudio gerado.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AudioPlaybackTest {

    private val tag = "AudioPlaybackTest"
    private val hub get() = AppGraph.playback

    @Test
    fun a_listsVoicesAndSelectsPortugueseOffline() {
        val tts = runBlocking { TtsVoices.create(TestUtil.app) }
        assumeTrue("Nenhum mecanismo TTS no aparelho", tts != null)
        val voices = TtsVoices.listVoices(tts!!)
        voices.filter { it.isPortuguese }.forEach { Log.i(tag, "VOZ ${it.name} ${it.locale} offline=${it.isOffline} net=${it.requiresNetwork} notInstalled=${it.notInstalled}") }
        val sel = TestUtil.instrumentation.let { var s: br.com.ghbook.tts.VoiceSelection? = null; TestUtil.main { s = TtsVoices.select(tts, "") }; s!! }
        Log.i(tag, "SELECIONADA ${sel.info?.name} ${sel.info?.locale} offline=${sel.info?.isOffline} aviso=${sel.warning}")
        tts.shutdown()
        assertTrue("Esperava voz em português", sel.info?.isPortuguese == true)
        assertTrue("Esperava voz offline", sel.info?.isOffline == true)
        // Voz neural padrão não é confundida com voz do sistema.
        assertTrue(NeuralEngine.isNeural(""))
        assertTrue(NeuralEngine.isNeural("neural:pm_alex"))
        assertFalse(NeuralEngine.isNeural(TtsVoices.SYSTEM_AUTO))
    }

    @Test
    fun a2_neuralVoicesSynthesizePortugueseOffline() {
        val ctx = TestUtil.app
        for (model in listOf(NeuralModel.FAST, NeuralModel.KOKORO)) {
            var frames = 0L
            val t0 = System.currentTimeMillis()
            val ok = NeuralEngine.synthesize(ctx, "Capítulo 4. Em 1987, o Doutor Antônio subiu 96 degraus.", NeuralEngine.defaultVoice, model) {
                frames += it.size
                true
            }
            val sec = frames.toFloat() / NeuralEngine.sampleRate(model)
            Log.i(tag, "NEURAL $model: ${"%.1f".format(sec)} s de áudio em ${System.currentTimeMillis() - t0} ms")
            assertTrue("síntese $model falhou: ${NeuralEngine.lastError}", ok)
            assertTrue("áudio curto demais ($model)", sec > 2f)
        }
        // Cancelamento no meio da frase (callback retorna false).
        var chunks = 0
        NeuralEngine.synthesize(ctx, "Primeira frase. Segunda frase. Terceira frase. Quarta frase.", NeuralEngine.defaultVoice, NeuralModel.FAST) {
            chunks++
            false
        }
        assertEquals(1, chunks)
    }

    @Test
    fun b0_systemVoicePlaybackStillWorks() {
        val id = TestUtil.importAndWait("livro-teste.pdf")
        TestUtil.main { AppGraph.settings.update { it.copy(voiceName = TtsVoices.SYSTEM_AUTO) } }
        try {
            TestUtil.main { hub.play(id, 0, 1, PlaybackMode.TTS) }
            assertTrue(TestUtil.waitFor(30_000) { hub.state.value.isPlaying })
            assertTrue(hub.state.value.sourceLabel, hub.state.value.sourceLabel.contains("Voz do sistema"))
            assertTrue(TestUtil.waitFor(60_000) { hub.state.value.paragraphIndex > 1 })
            TestUtil.main { hub.stop() }
        } finally {
            TestUtil.main { AppGraph.settings.update { it.copy(voiceName = "") } }
        }
    }

    @Test
    fun b_ttsPlaybackPauseResumeSpeedAndProgress() {
        val id = TestUtil.importAndWait("livro-teste.pdf")
        AppGraph.books.saveProgress(id, AppGraph.books.progressOf(id).copy(chapter = 0, paragraph = 0, audioPositionMs = 0, lastMode = "TTS"))
        TestUtil.main { hub.play(id, 0, 1, PlaybackMode.TTS) }
        assertTrue("narração deveria começar", TestUtil.waitFor(60_000) { hub.state.value.isPlaying && !hub.state.value.isBuffering && hub.state.value.bookId == id })
        val s0 = hub.state.value
        Log.i(tag, "TOCANDO ${s0.sourceLabel} cap=${s0.chapterIndex} par=${s0.paragraphIndex} dur=${s0.durationMs}")
        assertEquals(PlaybackMode.TTS, s0.mode)
        assertTrue(s0.durationMs > 0)
        // O trecho narrado avança (sincronização texto/áudio).
        assertTrue(s0.sourceLabel, s0.sourceLabel.contains("Voz neural"))
        assertTrue("parágrafo deveria avançar", TestUtil.waitFor(90_000) { hub.state.value.paragraphIndex > 1 })
        // Pausa
        TestUtil.main { hub.togglePlayPause() }
        assertTrue(TestUtil.waitFor(5_000) { !hub.state.value.isPlaying })
        val pausedAt = hub.state.value.paragraphIndex
        Thread.sleep(1500)
        assertEquals("pausado não avança", pausedAt, hub.state.value.paragraphIndex)
        // Progresso salvo no ponto pausado
        assertEquals(pausedAt, AppGraph.books.progressOf(id).paragraph)
        // Retomada do mesmo ponto
        TestUtil.main { hub.togglePlayPause() }
        assertTrue(TestUtil.waitFor(10_000) { hub.state.value.isPlaying })
        assertTrue(hub.state.value.paragraphIndex >= pausedAt)
        // Velocidade
        TestUtil.main { hub.setSpeed(1.5f) }
        assertTrue(TestUtil.waitFor(5_000) { kotlin.math.abs(hub.state.value.speed - 1.5f) < 0.01f })
        assertEquals(1.5f, AppGraph.settings.current.speechRate, 0.01f)
        // Avançar 30 s e próximo capítulo
        val before = hub.state.value.positionMs
        TestUtil.main { hub.seekBy(30_000) }
        assertTrue(TestUtil.waitFor(5_000) { hub.state.value.positionMs >= before + 20_000 || hub.state.value.chapterIndex > 0 })
        TestUtil.main { hub.nextChapter() }
        assertTrue(TestUtil.waitFor(10_000) { hub.state.value.chapterIndex == 1 })
        assertTrue(TestUtil.waitFor(15_000) { hub.state.value.isPlaying })
        TestUtil.main { hub.stop() }
        assertTrue(TestUtil.waitFor(5_000) { !hub.state.value.isPlaying })
        assertEquals(1, AppGraph.books.progressOf(id).chapter)
        TestUtil.main { hub.setSpeed(1.0f) }
    }

    @Test
    fun c_generatesOfflineAudioPerChapter() {
        val id = TestUtil.importAndWait("livro-teste.pdf")
        AppGraph.audio.deleteAll(id)
        val worker = TestListenableWorkerBuilder<AudioGenerationWorker>(TestUtil.app)
            .setInputData(workDataOf(AudioGenerationWorker.KEY_BOOK to id))
            .build()
        val start = System.currentTimeMillis()
        val result = runBlocking { worker.doWork() }
        Log.i(tag, "GERACAO ${result} em ${(System.currentTimeMillis() - start) / 1000}s erro=${AppGraph.audio.manifest(id).lastError}")
        assertTrue("geração falhou: ${AppGraph.audio.manifest(id).lastError}", result is ListenableWorker.Result.Success)
        val toc = AppGraph.books.toc(id)
        val manifest = AppGraph.audio.manifest(id)
        assertEquals(toc.size, manifest.chapters.size)
        manifest.chapters.forEach { a ->
            val f = File(AppGraph.audio.dir(id), a.file)
            assertTrue(f.exists() && f.length() > 1000)
            assertEquals("áudio gerado deve usar a Kokoro", NeuralEngine.defaultVoice.key, a.voiceName)
            val ex = MediaExtractor()
            ex.setDataSource(f.absolutePath)
            val fmt = ex.getTrackFormat(0)
            val durUs = fmt.getLong(MediaFormat.KEY_DURATION)
            ex.release()
            Log.i(tag, "AUDIO ${a.file} ${f.length()} bytes, ${durUs / 1000} ms, mime=${fmt.getString(MediaFormat.KEY_MIME)}, paragrafos=${a.paragraphStartMs.size}")
            assertEquals("audio/mp4a-latm", fmt.getString(MediaFormat.KEY_MIME))
            assertTrue(kotlin.math.abs(durUs / 1000 - a.durationMs) < 500)
            assertTrue(a.paragraphStartMs.zipWithNext().all { (x, y) -> y >= x })
        }
        // Retomada: rodar de novo não regenera capítulos prontos.
        val sizes = manifest.chapters.map { it.bytes }
        val again = runBlocking {
            TestListenableWorkerBuilder<AudioGenerationWorker>(TestUtil.app).setInputData(workDataOf(AudioGenerationWorker.KEY_BOOK to id)).build().doWork()
        }
        assertTrue(again is ListenableWorker.Result.Success)
        assertEquals(sizes, AppGraph.audio.manifest(id).chapters.map { it.bytes })
    }

    @Test
    fun d_playsGeneratedAudioAndResumesPosition() {
        val id = TestUtil.importAndWait("livro-teste.pdf")
        assumeTrue("Rode c_ antes", AppGraph.audio.manifest(id).chapters.isNotEmpty())
        TestUtil.main { hub.play(id, 0, 0, PlaybackMode.AUDIO) }
        assertTrue(TestUtil.waitFor(15_000) { hub.state.value.isPlaying && hub.state.value.mode == PlaybackMode.AUDIO })
        assertTrue(TestUtil.waitFor(10_000) { hub.state.value.positionMs > 2000 })
        // Seek dentro do arquivo e sincronização do parágrafo.
        TestUtil.main { hub.seekTo(20_000) }
        assertTrue(TestUtil.waitFor(5_000) { hub.state.value.positionMs >= 19_000 && hub.state.value.paragraphIndex > 0 })
        TestUtil.main { hub.pause() }
        assertTrue(TestUtil.waitFor(5_000) { !hub.state.value.isPlaying })
        val saved = AppGraph.books.progressOf(id)
        Log.i(tag, "SALVO cap=${saved.chapter} par=${saved.paragraph} pos=${saved.audioPositionMs} modo=${saved.lastMode}")
        assertEquals("AUDIO", saved.lastMode)
        assertTrue(saved.audioPositionMs >= 19_000)
        // Retomar a partir do progresso salvo (sem capítulo/parágrafo explícitos).
        TestUtil.main { hub.play(id) }
        assertTrue(TestUtil.waitFor(10_000) { hub.state.value.isPlaying && hub.state.value.mode == PlaybackMode.AUDIO })
        assertTrue(hub.state.value.positionMs >= 18_000)
        TestUtil.main { hub.stop() }
    }
}
