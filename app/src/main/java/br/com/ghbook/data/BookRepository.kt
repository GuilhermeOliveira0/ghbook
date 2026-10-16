package br.com.ghbook.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Armazenamento local dos livros. Estrutura em filesDir/books/<id>/:
 *  - source.pdf      cópia do PDF importado
 *  - cover.jpg       miniatura da primeira página
 *  - meta.json       [BookMeta]
 *  - toc.json        lista de [TocEntry]
 *  - chapters/NNNN.json  um [Chapter] por arquivo (carregado sob demanda)
 *  - progress.json   [ReadingProgress]
 *  - audio/          áudio gerado (ver AudioStore)
 */
class BookRepository(private val context: Context) {

    val booksDir: File = File(context.filesDir, "books").apply { mkdirs() }

    private val _books = MutableStateFlow<List<BookMeta>>(emptyList())
    val books: StateFlow<List<BookMeta>> = _books.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, ReadingProgress>>(emptyMap())
    val progress: StateFlow<Map<String, ReadingProgress>> = _progress.asStateFlow()

    private val tocCache = ConcurrentHashMap<String, List<TocEntry>>()
    private val chapterCache = object : LinkedHashMap<String, Chapter>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Chapter>?) = size > 6
    }

    init {
        reload()
    }

    fun bookDir(id: String) = File(booksDir, id)
    fun pdfFile(id: String) = File(bookDir(id), "source.pdf")
    fun coverFile(id: String) = File(bookDir(id), "cover.jpg")
    fun chaptersDir(id: String) = File(bookDir(id), "chapters")
    fun audioDir(id: String) = File(bookDir(id), "audio")

    @Synchronized
    fun reload() {
        val metas = booksDir.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            readJson<BookMeta>(File(dir, "meta.json"))
        } ?: emptyList()
        _books.value = metas.sortedByDescending { maxOf(it.lastOpenedAt, it.createdAt) }
        _progress.value = metas.associate { it.id to (readJson<ReadingProgress>(File(bookDir(it.id), "progress.json")) ?: ReadingProgress()) }
    }

    fun book(id: String): BookMeta? = _books.value.firstOrNull { it.id == id }

    @Synchronized
    fun saveMeta(meta: BookMeta) {
        bookDir(meta.id).mkdirs()
        writeJsonAtomic(File(bookDir(meta.id), "meta.json"), AppJson.encodeToString(meta))
        _books.update { list ->
            (list.filterNot { it.id == meta.id } + meta).sortedByDescending { maxOf(it.lastOpenedAt, it.createdAt) }
        }
        if (_progress.value[meta.id] == null) _progress.update { it + (meta.id to ReadingProgress()) }
    }

    /** Sincronizado com [saveMeta]: ler e regravar de forma atômica evita sobrescrever um status recém-gravado pela importação. */
    @Synchronized
    fun markOpened(id: String) {
        book(id)?.let { saveMeta(it.copy(lastOpenedAt = System.currentTimeMillis())) }
    }

    fun saveContent(id: String, toc: List<TocEntry>, chapters: Sequence<Chapter>) {
        val dir = chaptersDir(id)
        dir.deleteRecursively()
        dir.mkdirs()
        chapters.forEach { ch ->
            writeJsonAtomic(File(dir, chapterFileName(ch.index)), AppJson.encodeToString(ch))
        }
        writeJsonAtomic(File(bookDir(id), "toc.json"), AppJson.encodeToString(toc))
        tocCache[id] = toc
        synchronized(chapterCache) { chapterCache.keys.removeAll { it.startsWith("$id/") } }
    }

    fun toc(id: String): List<TocEntry> =
        tocCache[id] ?: (readJson<List<TocEntry>>(File(bookDir(id), "toc.json")) ?: emptyList()).also {
            if (it.isNotEmpty()) tocCache[id] = it
        }

    suspend fun chapter(id: String, index: Int): Chapter? = withContext(Dispatchers.IO) { chapterBlocking(id, index) }

    fun chapterBlocking(id: String, index: Int): Chapter? {
        val key = "$id/$index"
        synchronized(chapterCache) { chapterCache[key]?.let { return it } }
        val ch = readJson<Chapter>(File(chaptersDir(id), chapterFileName(index))) ?: return null
        synchronized(chapterCache) { chapterCache[key] = ch }
        return ch
    }

    fun progressOf(id: String): ReadingProgress = _progress.value[id] ?: ReadingProgress()

    fun saveProgress(id: String, progress: ReadingProgress) {
        if (!bookDir(id).exists()) return
        val p = progress.copy(updatedAt = System.currentTimeMillis())
        _progress.update { it + (id to p) }
        runCatching { writeJsonAtomic(File(bookDir(id), "progress.json"), AppJson.encodeToString(p)) }
    }

    /** Percentual lido aproximado (0..1) com base nos caracteres de cada capítulo. */
    fun percent(id: String, p: ReadingProgress = progressOf(id)): Float {
        val toc = toc(id)
        val total = toc.sumOf { it.charCount }.takeIf { it > 0 } ?: return 0f
        val before = toc.filter { it.index < p.chapter }.sumOf { it.charCount }
        val cur = toc.firstOrNull { it.index == p.chapter } ?: return (before.toFloat() / total).coerceIn(0f, 1f)
        val within = if (cur.paragraphCount > 0) cur.charCount * p.paragraph / cur.paragraphCount else 0
        return ((before + within).toFloat() / total).coerceIn(0f, 1f)
    }

    @Synchronized
    fun delete(id: String) {
        bookDir(id).deleteRecursively()
        tocCache.remove(id)
        synchronized(chapterCache) { chapterCache.keys.removeAll { it.startsWith("$id/") } }
        _books.update { list -> list.filterNot { it.id == id } }
        _progress.update { it - id }
    }

    fun findBySha(sha: String): BookMeta? = _books.value.firstOrNull { it.sha256 == sha && it.status != BookStatus.FAILED }

    private inline fun <reified T> readJson(file: File): T? =
        if (!file.exists()) null else runCatching { AppJson.decodeFromString<T>(file.readText()) }.getOrNull()

    companion object {
        fun chapterFileName(index: Int) = "%04d.json".format(index)

        /** Grava em arquivo temporário e renomeia, evitando arquivos corrompidos se o app for encerrado no meio. */
        fun writeJsonAtomic(file: File, text: String) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) {
                    file.writeText(text)
                    tmp.delete()
                }
            }
        }
    }
}
