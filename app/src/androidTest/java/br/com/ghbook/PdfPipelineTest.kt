package br.com.ghbook

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.ghbook.data.BookStatus
import br.com.ghbook.data.ParagraphKind
import br.com.ghbook.importer.ImportResult
import br.com.ghbook.pdf.PdfException
import br.com.ghbook.pdf.PdfExtractor
import br.com.ghbook.pdf.TextCleaner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfPipelineTest {

    @Test
    fun extractsCleansChaptersAndOcrsScannedPage() {
        val result = PdfExtractor(TestUtil.app).extract(TestUtil.sample("livro-teste.pdf"))
        assertEquals(11, result.pageCount)
        assertEquals("O Farol de Areia Branca", result.title)
        assertEquals("página 11 é digitalizada e deve passar por OCR", listOf(11), result.ocrPages)
        val chapters = TextCleaner.buildChapters(result.pages, result.outline)
        chapters.forEach { c -> Log.i("PdfPipelineTest", "CAP ${c.index}: ${c.title} (${c.paragraphs.size} parágrafos)") }
        val titles = chapters.map { it.title }
        assertTrue(titles.toString(), titles.any { it.startsWith("Capítulo 1") && it.contains("A chegada") })
        assertTrue(titles.toString(), titles.any { it.startsWith("Capítulo 4") && it.contains("O novo registro") })
        val all = chapters.flatMap { it.paragraphs }
        val text = all.joinToString("\n") { it.text }
        assertFalse("cabeçalho corrente não pode aparecer", text.contains("O FAROL DE AREIA BRANCA"))
        assertFalse("sumário não pode ser narrado", text.contains("....."))
        assertFalse("nota de rodapé removida", text.contains("Registro mantido pela capitania"))
        assertFalse("número de página isolado", all.any { it.text.trim().matches(Regex("\\d{1,3}")) })
        assertTrue("texto do OCR presente", text.contains("digitalizada", ignoreCase = true))
        // Parágrafo que atravessa páginas é unido; hifenização desfeita.
        val p = all.first { it.text.startsWith("Quando o Sr. Antônio") }
        assertTrue(p.text, p.text.endsWith("banco de madeira."))
        assertFalse("sem hifens de quebra de linha", Regex("\\p{L}- \\p{Ll}").containsMatchIn(text))
        assertTrue(all.any { it.kind == ParagraphKind.HEADING })
    }

    @Test
    fun outlineDefinesChapters() {
        val result = PdfExtractor(TestUtil.app).extract(TestUtil.sample("livro-teste-com-sumario.pdf"))
        assertTrue(result.outline.size == 5)
        val chapters = TextCleaner.buildChapters(result.pages, result.outline)
        assertEquals(
            listOf("Capítulo 1: A chegada", "Capítulo 2: O livro de registros", "Capítulo 3: A tempestade", "Capítulo 4: O novo registro", "Posfácio"),
            chapters.map { it.title }.filter { it != "Início" },
        )
    }

    @Test
    fun passwordProtectedPdfGivesClearError() {
        try {
            PdfExtractor(TestUtil.app).extract(TestUtil.sample("protegido-senha.pdf"))
            fail("deveria falhar")
        } catch (e: PdfException) {
            assertTrue(e.message!!, e.message!!.contains("senha"))
        }
    }

    @Test
    fun importRejectsNonPdfAndMarksProtectedAsFailed() {
        val bad = runBlocking { AppGraph.importer.import(Uri.fromFile(TestUtil.sample("nao-e-pdf.pdf"))) }
        assertTrue(bad is ImportResult.Error)
        val id = TestUtil.importAndWait("protegido-senha.pdf")
        val meta = AppGraph.books.book(id)!!
        assertEquals(BookStatus.FAILED, meta.status)
        assertTrue(meta.errorMessage!!.contains("senha"))
        BookActionsHelper.delete(id)
        assertTrue(AppGraph.books.book(id) == null)
    }

    @Test
    fun importEndToEndPersistsBookAndProgress() {
        val id = TestUtil.importAndWait("livro-teste.pdf")
        val meta = AppGraph.books.book(id)!!
        assertEquals(BookStatus.READY, meta.status)
        assertEquals(11, meta.pageCount)
        assertTrue(meta.chapterCount >= 4)
        assertTrue(AppGraph.books.coverFile(id).exists())
        val toc = AppGraph.books.toc(id)
        assertEquals(meta.chapterCount, toc.size)
        val ch = runBlocking { AppGraph.books.chapter(id, 1) }!!
        assertTrue(ch.paragraphs.isNotEmpty())
        // Importar o mesmo arquivo de novo não duplica.
        val again = runBlocking { AppGraph.importer.import(Uri.fromFile(TestUtil.sample("livro-teste.pdf"))) }
        assertTrue(again is ImportResult.AlreadyImported)
        // Progresso persiste em disco (nova instância do repositório lê o arquivo).
        AppGraph.books.saveProgress(id, AppGraph.books.progressOf(id).copy(chapter = 2, paragraph = 3))
        val fresh = br.com.ghbook.data.BookRepository(TestUtil.app)
        assertEquals(2, fresh.progressOf(id).chapter)
        assertEquals(3, fresh.progressOf(id).paragraph)
    }
}

object BookActionsHelper {
    fun delete(id: String) = TestUtil.main { br.com.ghbook.ui.BookActions.delete(id) }
}
