package br.com.ghbook.neural

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * Callback chamado pelo código nativo do sherpa-onnx via JNI, que procura exatamente o método
 * `invoke(float[]): Integer`. Lambdas do Kotlin 2 (invokedynamic) não geram esse método tipado,
 * por isso uma classe explícita (mantida no proguard-rules.pro).
 */
class ChunkCallback(private val onChunk: (FloatArray) -> Boolean) : (FloatArray) -> Int {
    override fun invoke(samples: FloatArray): Int = if (onChunk(samples)) 1 else 0
}

/** Modelo neural: Kokoro (alta qualidade, mais pesado) ou Piper (rápido, para narração ao vivo em aparelhos lentos). */
enum class NeuralModel { KOKORO, FAST }

/** Como a narração ao vivo escolhe o modelo. */
enum class LiveNeuralMode(val label: String) {
    AUTO("Automático"),
    KOKORO("Sempre Kokoro"),
    FAST("Sempre rápida"),
}

/**
 * Voz neural embutida no app. Cada voz Kokoro tem uma voz rápida (Piper) do mesmo gênero,
 * usada ao vivo quando o aparelho não sintetiza a Kokoro na velocidade da fala.
 */
data class NeuralVoice(
    val id: String,
    val name: String,
    val description: String,
    val speakerId: Int,
    val fastModel: String,
    val fastName: String,
) {
    val key get() = "neural:$id"
}

/**
 * Motor de vozes neurais offline executadas com sherpa-onnx/ONNX Runtime no próprio aparelho:
 *  - Kokoro-82M (int8): qualidade alta; usada na geração de áudio e ao vivo em aparelhos rápidos;
 *  - Piper pt-BR "high" (int8): ~5× mais rápida; usada ao vivo em aparelhos mais lentos.
 * Os modelos são lidos direto dos assets do APK; só os dados de fonética do eSpeak (≈2 MB, compartilhados)
 * são copiados para o armazenamento interno, porque o eSpeak precisa de um diretório real.
 */
object NeuralEngine {
    private const val TAG = "NeuralEngine"
    private const val DATA_VERSION = 1
    private const val PREFS = "neural"

    val voices = listOf(
        NeuralVoice("pf_dora", "Dora", "Feminina · português do Brasil", 42, "dii", "Dii"),
        NeuralVoice("pm_alex", "Alex", "Masculina · português do Brasil", 43, "miro", "Miro"),
        NeuralVoice("pm_santa", "Santa", "Masculina, grave · português do Brasil", 44, "miro", "Miro"),
    )
    val defaultVoice = voices.first()

    /** Velocidade média da fala em pt-BR (ms por caractere), para estimar duração/posição antes de sintetizar. */
    const val MS_PER_CHAR = 56L

    /** Abaixo deste fator de tempo real (tempo de síntese ÷ duração do áudio) a Kokoro é usada ao vivo. */
    const val KOKORO_LIVE_MAX_RTF = 0.85f

    fun isNeural(voiceName: String) = voiceName.isEmpty() || voiceName.startsWith("neural:")

    /** Voz neural correspondente à preferência ("" = padrão). */
    fun voiceFor(voiceName: String): NeuralVoice = voices.firstOrNull { it.key == voiceName } ?: defaultVoice

    private val kokoroLock = Any()
    private val fastLock = Any()
    @Volatile private var kokoro: OfflineTts? = null
    private val fast = HashMap<String, OfflineTts>()
    @Volatile var lastError: String? = null
        private set

    fun isLoaded(model: NeuralModel, voice: NeuralVoice) = when (model) {
        NeuralModel.KOKORO -> kokoro != null
        NeuralModel.FAST -> synchronized(fast) { fast.containsKey(voice.fastModel) }
    }

    fun sampleRate(model: NeuralModel) = if (model == NeuralModel.KOKORO) 24000 else 22050

    private fun threads() = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    /** Carrega o modelo (1–3 s na primeira vez). Retorna false se falhar (ex.: memória insuficiente). */
    fun load(context: Context, model: NeuralModel, voice: NeuralVoice): Boolean = try {
        when (model) {
            NeuralModel.KOKORO -> loadKokoro(context) != null
            NeuralModel.FAST -> loadFast(context, voice) != null
        }
    } catch (t: Throwable) {
        Log.e(TAG, "Falha ao carregar", t)
        lastError = if (t is OutOfMemoryError) "Memória insuficiente para a voz neural." else "Falha ao carregar a voz neural: ${t.message}"
        false
    }

    private fun loadKokoro(context: Context): OfflineTts? {
        kokoro?.let { return it }
        synchronized(kokoroLock) {
            kokoro?.let { return it }
            return create("Kokoro") {
                OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = "kokoro/model.int8.onnx",
                        voices = "kokoro/voices.bin",
                        tokens = "kokoro/tokens.txt",
                        dataDir = prepareEspeakData(context).absolutePath,
                        lang = "pt-br",
                    ),
                    numThreads = threads(),
                    debug = false,
                    provider = "cpu",
                )
            }?.let { tts -> OfflineTts(assetManager = context.applicationContext.assets, config = tts) }
                ?.also { kokoro = it }
        }
    }

    private fun loadFast(context: Context, voice: NeuralVoice): OfflineTts? {
        synchronized(fast) { fast[voice.fastModel]?.let { return it } }
        synchronized(fastLock) {
            synchronized(fast) { fast[voice.fastModel]?.let { return it } }
            val cfg = create("Piper ${voice.fastModel}") {
                OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = "piper/${voice.fastModel}/model.onnx",
                        tokens = "piper/${voice.fastModel}/tokens.txt",
                        dataDir = prepareEspeakData(context).absolutePath,
                    ),
                    numThreads = threads(),
                    debug = false,
                    provider = "cpu",
                )
            } ?: return null
            val tts = OfflineTts(assetManager = context.applicationContext.assets, config = cfg)
            synchronized(fast) { fast[voice.fastModel] = tts }
            return tts
        }
    }

    private fun create(name: String, model: () -> OfflineTtsModelConfig): OfflineTtsConfig? = try {
        val t0 = System.currentTimeMillis()
        OfflineTtsConfig(model = model(), maxNumSentences = 1).also {
            lastError = null
            Log.i(TAG, "$name configurado em ${System.currentTimeMillis() - t0} ms, ${threads()} threads")
        }
    } catch (t: Throwable) {
        Log.e(TAG, "Falha ao preparar $name", t)
        lastError = if (t is OutOfMemoryError) "Memória insuficiente para a voz neural." else "Falha ao carregar a voz neural: ${t.message}"
        null
    }

    /**
     * Sintetiza [text] entregando o áudio frase a frase em [onChunk] (amostras float −1..1).
     * [onChunk] retorna false para interromper. Retorna false se o motor não estiver disponível.
     */
    fun synthesize(context: Context, text: String, voice: NeuralVoice, model: NeuralModel, onChunk: (FloatArray) -> Boolean): Boolean {
        val sid = if (model == NeuralModel.KOKORO) voice.speakerId else 0
        // Carregar e sintetizar sob a mesma trava: o modelo não pode ser liberado no meio da síntese.
        synchronized(if (model == NeuralModel.KOKORO) kokoroLock else fastLock) {
            val engine = try {
                when (model) {
                    NeuralModel.KOKORO -> loadKokoro(context)
                    NeuralModel.FAST -> loadFast(context, voice)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Falha ao carregar", t)
                lastError = if (t is OutOfMemoryError) "Memória insuficiente para a voz neural." else "Falha ao carregar a voz neural: ${t.message}"
                null
            } ?: return false
            engine.generateWithCallback(text, sid, 1.0f, ChunkCallback(onChunk))
        }
        return true
    }

    // ---------------------------------------------------------------- desempenho do aparelho

    /** Fator de tempo real da Kokoro medido neste aparelho (null = ainda não medido). */
    fun kokoroRtf(context: Context): Float? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("kokoroRtf", -1f).takeIf { it > 0f }

    /** Registra uma medição (média móvel), feita na geração de áudio, no teste de voz ou ao vivo. */
    fun recordKokoroRtf(context: Context, rtf: Float) {
        if (rtf <= 0f || rtf.isNaN()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = prefs.getFloat("kokoroRtf", -1f)
        val v = if (old > 0f) old * 0.6f + rtf * 0.4f else rtf
        prefs.edit().putFloat("kokoroRtf", v).apply()
    }

    /** Mede a Kokoro com uma frase curta (≈3 s de áudio). Bloqueante; chamar fora da thread principal. */
    fun benchmarkKokoro(context: Context, voice: NeuralVoice): Float? {
        var frames = 0L
        val t0 = System.nanoTime()
        val ok = synthesize(context, "Este é um teste rápido para medir a velocidade da voz neural neste aparelho.", voice, NeuralModel.KOKORO) {
            frames += it.size
            true
        }
        if (!ok || frames == 0L) return null
        val rtf = ((System.nanoTime() - t0) / 1e9f) / (frames.toFloat() / sampleRate(NeuralModel.KOKORO))
        Log.i(TAG, "Kokoro RTF medido: $rtf")
        recordKokoroRtf(context, rtf)
        return rtf
    }

    /** Modelo para a narração ao vivo, conforme o modo escolhido e a velocidade medida do aparelho. */
    fun liveModel(context: Context, mode: LiveNeuralMode): NeuralModel = when (mode) {
        LiveNeuralMode.KOKORO -> NeuralModel.KOKORO
        LiveNeuralMode.FAST -> NeuralModel.FAST
        LiveNeuralMode.AUTO -> {
            val rtf = kokoroRtf(context)
            if (rtf != null && rtf <= KOKORO_LIVE_MAX_RTF) NeuralModel.KOKORO else NeuralModel.FAST
        }
    }

    /** A narração ao vivo está usando a Kokoro (não liberar a memória dela). */
    @Volatile var liveUsesKokoro = false
    private val kokoroUsers = java.util.concurrent.atomic.AtomicInteger(0)

    /** Marca uso prolongado da Kokoro (geração de áudio), impedindo que seja liberada entre trechos. */
    fun acquireKokoro() { kokoroUsers.incrementAndGet() }
    fun releaseKokoroUser() { kokoroUsers.decrementAndGet() }

    /** Libera a Kokoro (centenas de MB de memória nativa) quando ninguém a está usando. */
    fun releaseKokoroIfIdle() {
        if (liveUsesKokoro || kokoroUsers.get() > 0) return
        synchronized(kokoroLock) {
            if (liveUsesKokoro || kokoroUsers.get() > 0 || kokoro == null) return
            kokoro?.release()
            kokoro = null
            Log.i(TAG, "Kokoro liberada da memória")
        }
    }

    fun release() {
        synchronized(kokoroLock) { kokoro?.release(); kokoro = null }
        synchronized(fastLock) { synchronized(fast) { fast.values.forEach { it.release() }; fast.clear() } }
    }

    /** Copia os dados de fonética do eSpeak (pt/pt-BR) dos assets para o armazenamento interno. */
    @Synchronized
    private fun prepareEspeakData(context: Context): File {
        val root = File(context.filesDir, "kokoro")
        val dataDir = File(root, "espeak-ng-data")
        val marker = File(root, "version")
        if (dataDir.isDirectory && marker.exists() && marker.readText().trim() == DATA_VERSION.toString()) return dataDir
        root.deleteRecursively()
        copyAssetDir(context, "kokoro/espeak-ng-data", dataDir)
        marker.writeText(DATA_VERSION.toString())
        return dataDir
    }

    private fun copyAssetDir(context: Context, assetPath: String, target: File) {
        val am = context.assets
        val children = am.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            am.open(assetPath).use { input -> target.outputStream().use { input.copyTo(it) } }
            return
        }
        target.mkdirs()
        children.forEach { copyAssetDir(context, "$assetPath/$it", File(target, it)) }
    }
}
