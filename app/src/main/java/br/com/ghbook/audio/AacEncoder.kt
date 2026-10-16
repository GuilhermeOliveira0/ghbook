package br.com.ghbook.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Cabeçalho de um WAV PCM gerado pelo TextToSpeech. */
data class WavInfo(val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val dataOffset: Long, val dataLength: Long)

object Wav {
    fun read(file: File): WavInfo {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(12)
            raf.readFully(header)
            if (String(header, 0, 4) != "RIFF" || String(header, 8, 4) != "WAVE") throw IOException("Arquivo WAV inválido")
            var sampleRate = 0
            var channels = 0
            var bits = 0
            while (raf.filePointer + 8 <= raf.length()) {
                val chunk = ByteArray(8)
                raf.readFully(chunk)
                val id = String(chunk, 0, 4)
                val size = ByteBuffer.wrap(chunk, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                if (id == "fmt ") {
                    val fmt = ByteArray(size.toInt().coerceAtMost(64))
                    raf.readFully(fmt)
                    val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                    val format = bb.getShort(0).toInt()
                    if (format != 1 && format != -2) throw IOException("Formato WAV não suportado ($format)")
                    channels = bb.getShort(2).toInt()
                    sampleRate = bb.getInt(4)
                    bits = bb.getShort(14).toInt()
                    raf.seek(raf.filePointer - fmt.size + size + (size and 1))
                } else if (id == "data") {
                    val start = raf.filePointer
                    // Alguns mecanismos gravam tamanho 0/incorreto: usa até o fim do arquivo.
                    val len = if (size == 0L || start + size > raf.length()) raf.length() - start else size
                    if (sampleRate == 0) throw IOException("WAV sem bloco de formato")
                    return WavInfo(sampleRate, channels, bits, start, len)
                } else {
                    raf.seek(raf.filePointer + size + (size and 1))
                }
            }
        }
        throw IOException("WAV sem dados de áudio")
    }
}

/**
 * Codifica PCM 16 bits em AAC-LC dentro de um contêiner MP4 (.m4a), usando MediaCodec/MediaMuxer do Android.
 * Os dados são processados em blocos, sem carregar o capítulo inteiro na memória.
 */
class AacEncoder(private val output: File, val sampleRate: Int, val channels: Int, bitrate: Int = 64_000) {

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var track = -1
    private var muxerStarted = false
    private var totalFrames = 0L
    private val bufferInfo = MediaCodec.BufferInfo()
    private val bytesPerFrame = 2 * channels

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /** Duração já escrita, em ms. */
    val writtenMs: Long get() = totalFrames * 1000 / sampleRate

    fun appendWav(wav: File) {
        val info = Wav.read(wav)
        if (info.bitsPerSample != 16) throw IOException("Áudio do TTS em ${info.bitsPerSample} bits não suportado")
        if (info.sampleRate != sampleRate || info.channels != channels) throw IOException("Formato de áudio do TTS mudou durante a geração")
        RandomAccessFile(wav, "r").use { raf ->
            raf.seek(info.dataOffset)
            var remaining = info.dataLength - (info.dataLength % bytesPerFrame)
            val chunk = ByteArray(8192 - (8192 % bytesPerFrame))
            while (remaining > 0) {
                val n = raf.read(chunk, 0, minOf(chunk.size.toLong(), remaining).toInt())
                if (n <= 0) break
                writePcm(chunk, n - (n % bytesPerFrame))
                remaining -= n
            }
        }
    }

    /** Amostras float (−1..1) da voz neural, convertidas para PCM 16 bits. Apenas mono. */
    fun appendFloat(samples: FloatArray) {
        val buf = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            buf[2 * i] = (v and 0xFF).toByte()
            buf[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        var off = 0
        while (off < buf.size) {
            val n = minOf(16 * 1024 - (16 * 1024 % bytesPerFrame), buf.size - off)
            writePcm(buf.copyOfRange(off, off + n), n)
            off += n
        }
    }

    /** Silêncio entre trechos (pausa natural entre parágrafos). */
    fun appendSilence(ms: Long) {
        val frames = sampleRate * ms / 1000
        val buf = ByteArray(4096 - (4096 % bytesPerFrame))
        var left = frames * bytesPerFrame
        while (left > 0) {
            val n = minOf(buf.size.toLong(), left).toInt()
            writePcm(buf, n)
            left -= n
        }
    }

    private fun writePcm(data: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val inBuf = codec.getInputBuffer(inIndex)!!
                inBuf.clear()
                val n = minOf(inBuf.remaining() - (inBuf.remaining() % bytesPerFrame), length - offset)
                inBuf.put(data, offset, n)
                val ptsUs = totalFrames * 1_000_000L / sampleRate
                codec.queueInputBuffer(inIndex, 0, n, ptsUs, 0)
                totalFrames += n / bytesPerFrame
                offset += n
            }
            drain(false)
        }
    }

    private fun drain(endOfStream: Boolean) {
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outIndex >= 0 -> {
                    val outBuf = codec.getOutputBuffer(outIndex)!!
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) bufferInfo.size = 0
                    if (bufferInfo.size > 0 && muxerStarted) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(track, outBuf, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    fun finish() {
        var queued = false
        while (!queued) {
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                codec.queueInputBuffer(inIndex, 0, 0, totalFrames * 1_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                queued = true
            } else drain(false)
        }
        drain(true)
        release()
    }

    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        if (muxerStarted) runCatching { muxer.stop() }
        runCatching { muxer.release() }
        muxerStarted = false
    }
}
