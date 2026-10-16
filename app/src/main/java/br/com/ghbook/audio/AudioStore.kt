package br.com.ghbook.audio

import br.com.ghbook.data.AppJson
import br.com.ghbook.data.AudioManifest
import br.com.ghbook.data.BookRepository
import br.com.ghbook.data.ChapterAudio
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * Áudio gerado de cada livro: books/<id>/audio/cap_NNN.m4a (AAC) + manifest.json com a duração
 * e o mapa de sincronização (início de cada parágrafo em ms).
 */
class AudioStore(private val repo: BookRepository) {

    private val _version = MutableStateFlow(0)
    /** Incrementa a cada alteração, para a interface recarregar. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun dir(bookId: String) = repo.audioDir(bookId)
    private fun manifestFile(bookId: String) = File(dir(bookId), "manifest.json")

    fun chapterFileName(chapter: Int) = "cap_%03d.m4a".format(chapter + 1)
    fun chapterFile(bookId: String, chapter: Int) = File(dir(bookId), chapterFileName(chapter))

    @Synchronized
    fun manifest(bookId: String): AudioManifest {
        val f = manifestFile(bookId)
        if (!f.exists()) return AudioManifest()
        val m = runCatching { AppJson.decodeFromString<AudioManifest>(f.readText()) }.getOrDefault(AudioManifest())
        // Descarta entradas cujo arquivo sumiu.
        return m.copy(chapters = m.chapters.filter { File(dir(bookId), it.file).exists() })
    }

    @Synchronized
    fun putChapter(bookId: String, audio: ChapterAudio, voiceName: String) {
        val m = manifest(bookId)
        val updated = m.copy(
            chapters = (m.chapters.filterNot { it.chapter == audio.chapter } + audio).sortedBy { it.chapter },
            voiceName = voiceName,
            lastError = null,
            updatedAt = System.currentTimeMillis(),
        )
        save(bookId, updated)
    }

    @Synchronized
    fun setError(bookId: String, error: String?) {
        if (!repo.bookDir(bookId).exists()) return
        save(bookId, manifest(bookId).copy(lastError = error, updatedAt = System.currentTimeMillis()))
    }

    private fun save(bookId: String, m: AudioManifest) {
        // Livro removido durante a geração: não recria a pasta.
        if (!File(repo.bookDir(bookId), "meta.json").exists()) return
        dir(bookId).mkdirs()
        BookRepository.writeJsonAtomic(manifestFile(bookId), AppJson.encodeToString(m))
        _version.value++
    }

    @Synchronized
    fun deleteAll(bookId: String) {
        dir(bookId).deleteRecursively()
        _version.value++
    }

    fun sizeBytes(bookId: String): Long = dir(bookId).walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
