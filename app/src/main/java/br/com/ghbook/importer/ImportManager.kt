package br.com.ghbook.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import br.com.ghbook.data.BookMeta
import br.com.ghbook.data.BookRepository
import br.com.ghbook.data.BookStatus
import br.com.ghbook.data.TocEntry
import br.com.ghbook.pdf.PdfException
import br.com.ghbook.pdf.PdfExtractor
import br.com.ghbook.pdf.TextCleaner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ImportState(
    val bookId: String,
    val title: String,
    val done: Int = 0,
    val total: Int = 0,
    val message: String = "Preparando…",
    val running: Boolean = true,
)

sealed class ImportResult {
    data class Started(val bookId: String) : ImportResult()
    data class AlreadyImported(val bookId: String) : ImportResult()
    data class Error(val message: String) : ImportResult()
}

/**
 * Importa PDFs: copia o arquivo escolhido para o armazenamento privado do app, extrai o texto em segundo plano
 * (com OCR quando necessário) e grava capítulos/parágrafos. Todo o processamento é local.
 */
class ImportManager(private val context: Context, private val repo: BookRepository) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    private val _states = MutableStateFlow<Map<String, ImportState>>(emptyMap())
    val states: StateFlow<Map<String, ImportState>> = _states.asStateFlow()

    suspend fun import(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = "livro.pdf"
        var declaredSize = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) declaredSize = c.getLong(1)
                }
            }
        }
        if (declaredSize > 0 && repo.booksDir.usableSpace < declaredSize * 2 + 20L * 1024 * 1024) {
            return@withContext ImportResult.Error("Espaço de armazenamento insuficiente para importar este PDF.")
        }
        val id = "b" + System.currentTimeMillis().toString(36) + UUID.randomUUID().toString().take(6)
        val dir = repo.bookDir(id).apply { mkdirs() }
        val target = repo.pdfFile(id)
        val sha = try {
            val digest = MessageDigest.getInstance("SHA-256")
            val input = resolver.openInputStream(uri) ?: throw IOException("Não foi possível abrir o arquivo.")
            input.use { ins ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: SecurityException) {
            dir.deleteRecursively()
            return@withContext ImportResult.Error("Sem permissão para ler o arquivo escolhido.")
        } catch (e: IOException) {
            dir.deleteRecursively()
            val msg = if (e.message?.contains("ENOSPC") == true) "Espaço de armazenamento insuficiente." else "Falha ao copiar o arquivo: ${e.message}"
            return@withContext ImportResult.Error(msg)
        } catch (e: RuntimeException) {
            // Ex.: URI inválida/provedor com erro — não pode derrubar a tela que chamou import().
            dir.deleteRecursively()
            return@withContext ImportResult.Error("Falha ao copiar o arquivo: ${e.message ?: e.javaClass.simpleName}")
        }
        // Verifica a assinatura %PDF no início do arquivo.
        val header = runCatching {
            target.inputStream().use { s -> ByteArray(1024).let { b -> val n = s.read(b); if (n > 0) String(b, 0, n, Charsets.ISO_8859_1) else "" } }
        }.getOrDefault("")
        if (!header.contains("%PDF")) {
            dir.deleteRecursively()
            return@withContext ImportResult.Error("O arquivo escolhido não é um PDF válido.")
        }
        repo.findBySha(sha)?.let { existing ->
            dir.deleteRecursively()
            return@withContext ImportResult.AlreadyImported(existing.id)
        }
        val title = name.removeSuffix(".pdf").removeSuffix(".PDF").replace('_', ' ').trim().ifEmpty { "Livro sem título" }
        repo.saveMeta(BookMeta(id = id, title = title, fileName = name, fileSize = target.length(), sha256 = sha, status = BookStatus.PROCESSING))
        process(id)
        ImportResult.Started(id)
    }

    /** Extrai (ou reextrai) o texto do PDF já copiado. */
    fun process(bookId: String) {
        if (jobs[bookId]?.isActive == true) return
        cancelled.remove(bookId)
        val meta = repo.book(bookId) ?: return
        _states.update { it + (bookId to ImportState(bookId, meta.title)) }
        jobs[bookId] = scope.launch {
            val pdf = repo.pdfFile(bookId)
            try {
                if (!pdf.exists()) throw PdfException("O arquivo PDF deste livro não foi encontrado.")
                repo.saveMeta(meta.copy(status = BookStatus.PROCESSING, errorMessage = null))
                val extractor = PdfExtractor(context)
                extractor.renderCover(pdf, repo.coverFile(bookId))
                val result = extractor.extract(
                    pdf,
                    onProgress = { done, total, msg -> setState(bookId) { it.copy(done = done, total = total, message = msg) } },
                    isCancelled = { bookId in cancelled },
                )
                setState(bookId) { it.copy(message = "Organizando capítulos e parágrafos…") }
                val chapters = TextCleaner.buildChapters(result.pages, result.outline)
                val totalChars = chapters.sumOf { c -> c.paragraphs.sumOf { it.text.length.toLong() } }
                if (chapters.isEmpty() || totalChars < 20) {
                    val reason = if (result.unreadablePages.isNotEmpty()) {
                        "Não foi possível reconhecer texto neste PDF (${result.unreadablePages.size} páginas digitalizadas sem texto legível pelo OCR)."
                    } else "Nenhum texto foi encontrado neste PDF."
                    throw PdfException(reason)
                }
                val toc = chapters.map { c ->
                    TocEntry(
                        index = c.index,
                        title = c.title,
                        startPage = c.paragraphs.minOfOrNull { it.page } ?: 1,
                        endPage = c.paragraphs.maxOfOrNull { it.page } ?: 1,
                        paragraphCount = c.paragraphs.size,
                        charCount = c.paragraphs.sumOf { it.text.length.toLong() },
                    )
                }
                repo.saveContent(bookId, toc, chapters.asSequence())
                val current = repo.book(bookId) ?: return@launch // excluído durante o processamento
                repo.saveMeta(
                    current.copy(
                        title = result.title?.takeIf { looksLikeTitle(it) } ?: current.title,
                        author = result.author ?: "",
                        pageCount = result.pageCount,
                        chapterCount = chapters.size,
                        totalChars = totalChars,
                        ocrPages = result.ocrPages,
                        unreadablePages = result.unreadablePages,
                        status = BookStatus.READY,
                        errorMessage = null,
                    ),
                )
                _states.update { it - bookId }
            } catch (e: Throwable) {
                val msg = when (e) {
                    is PdfException -> e.message
                    is OutOfMemoryError -> "Memória insuficiente para processar este PDF."
                    is IOException -> if (e.message?.contains("ENOSPC") == true) "Espaço de armazenamento insuficiente." else "Erro de leitura: ${e.message}"
                    else -> "Falha na extração do texto: ${e.message ?: e.javaClass.simpleName}"
                } ?: "Falha desconhecida."
                repo.book(bookId)?.let { repo.saveMeta(it.copy(status = BookStatus.FAILED, errorMessage = msg)) }
                _states.update { it - bookId }
            } finally {
                jobs.remove(bookId)
                // Livro excluído durante o processamento: saveContent/renderCover podem ter recriado a pasta (órfã).
                if (repo.book(bookId) == null) repo.bookDir(bookId).deleteRecursively()
            }
        }
    }

    private fun looksLikeTitle(t: String): Boolean {
        val letters = t.count { it.isLetter() }
        return letters >= 3 && !t.contains("Microsoft Word", true) && !t.contains(".indd", true) && !t.contains(".pdf", true)
    }

    private fun setState(id: String, f: (ImportState) -> ImportState) {
        _states.update { m -> m[id]?.let { m + (id to f(it)) } ?: m }
    }

    fun cancel(bookId: String) {
        cancelled.add(bookId)
    }

    /** Livros que estavam sendo processados quando o app foi encerrado voltam para a fila. */
    fun resumeInterrupted() {
        repo.books.value.filter { it.status == BookStatus.PROCESSING }.forEach { process(it.id) }
    }

    fun isRunning(bookId: String) = jobs[bookId]?.isActive == true

    fun pdfExists(bookId: String) = File(repo.bookDir(bookId), "source.pdf").exists()
}
