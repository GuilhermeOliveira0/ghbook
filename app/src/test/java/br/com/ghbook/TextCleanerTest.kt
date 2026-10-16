package br.com.ghbook

import br.com.ghbook.data.ParagraphKind
import br.com.ghbook.pdf.OutlineItem
import br.com.ghbook.pdf.RawLine
import br.com.ghbook.pdf.RawPage
import br.com.ghbook.pdf.TextCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextCleanerTest {

    private val body = 11f
    private val lh = 16f

    /** Página com cabeçalho corrente, número de página e linhas de corpo justificadas. */
    private fun page(n: Int, vararg blocks: List<RawLine>): RawPage {
        val lines = ArrayList<RawLine>()
        lines += RawLine("O FAROL DE AREIA BRANCA", 48f, 36f, 200f, 8f)
        blocks.forEach { lines += it }
        lines += RawLine("$n", 165f, 450f, 172f, 9f)
        return RawPage(n, 340f, 480f, lines)
    }

    private fun para(startY: Float, vararg text: String, indentFirst: Boolean = true, lastShort: Boolean = true): List<RawLine> =
        text.mapIndexed { i, t ->
            val x = if (i == 0 && indentFirst) 64f else 48f
            val end = if (i == text.lastIndex && lastShort) 180f else 292f
            RawLine(t, x, startY + i * lh, end, body)
        }

    private fun heading(y: Float, t: String) = listOf(RawLine(t, 48f, y, 200f, 20f, bold = true))

    private fun book(): List<RawPage> = listOf(
        page(
            1,
            heading(110f, "Capítulo 1") + heading(138f, "A chegada"),
            para(180f, "Quando o Sr. Antônio desceu do ônibus em Areia", "Branca, o vento já carregava o cheiro de sal e de", "chuva."),
            para(228f, "Ele trazia apenas uma mala marrom e uma car-", "ta assinada pela Dra. Helena, dizia que o farol", "precisava de um guardião e que a casa ao lado"),
        ),
        page(
            2,
            para(64f, "estaria disponível a partir de março.", indentFirst = false),
            para(80f, "Antônio caminhou devagar pela rua principal e", "contou doze casas."),
        ),
        page(
            3,
            heading(110f, "Capítulo 2") + heading(138f, "O livro de registros"),
            para(180f, "Dentro da casa havia um livro de registros com", "capa de couro."),
            listOf(RawLine("1 Registro mantido pela capitania entre 1923 e 1987.", 48f, 428f, 290f, 8f)),
        ),
        page(
            4,
            para(64f, "Na página 214 encontrou uma frase sublinhada", "duas vezes."),
        ),
    )

    @Test
    fun removesRunningHeadersPageNumbersAndFootnotes() {
        val chapters = TextCleaner.buildChapters(book())
        val all = chapters.flatMap { it.paragraphs }.joinToString("\n") { it.text }
        assertFalse("cabeçalho repetido deve sumir", all.contains("FAROL DE AREIA BRANCA"))
        assertFalse("nota de rodapé deve sumir", all.contains("capitania"))
        assertFalse("números de página isolados devem sumir", chapters.flatMap { it.paragraphs }.any { it.text.matches(Regex("\\d+")) })
    }

    @Test
    fun detectsChaptersAndMergesHeadingLines() {
        val chapters = TextCleaner.buildChapters(book())
        assertEquals(2, chapters.size)
        assertEquals("Capítulo 1 — A chegada", chapters[0].title)
        assertEquals("Capítulo 2 — O livro de registros", chapters[1].title)
        assertEquals(ParagraphKind.HEADING, chapters[0].paragraphs.first().kind)
    }

    @Test
    fun joinsLinesIntoParagraphsAndRemovesHyphenation() {
        val ch = TextCleaner.buildChapters(book())[0]
        val texts = ch.paragraphs.filter { it.kind == ParagraphKind.TEXT }.map { it.text }
        assertEquals("Quando o Sr. Antônio desceu do ônibus em Areia Branca, o vento já carregava o cheiro de sal e de chuva.", texts[0])
        assertTrue("hífen de quebra de linha removido", texts[1].contains("uma carta assinada"))
        // Parágrafo continua na página seguinte (frase não terminada).
        assertTrue(texts[1].endsWith("ao lado estaria disponível a partir de março."))
        assertEquals(1, ch.paragraphs.first { it.text.startsWith("Ele trazia") }.page)
        assertEquals("Antônio caminhou devagar pela rua principal e contou doze casas.", texts[2])
    }

    @Test
    fun usesPdfOutlineWhenAvailable() {
        val outline = listOf(OutlineItem("Capítulo 1: A chegada", 1), OutlineItem("Capítulo 2: O livro de registros", 3))
        val chapters = TextCleaner.buildChapters(book(), outline)
        assertEquals(listOf("Capítulo 1: A chegada", "Capítulo 2: O livro de registros"), chapters.map { it.title })
    }

    @Test
    fun dropsTableOfContentsPages() {
        val toc = RawPage(
            1, 340f, 480f,
            listOf(RawLine("Sumário", 48f, 80f, 120f, 16f)) + (1..5).map { i ->
                RawLine("Capítulo $i - Título ................ ${i * 2 + 2}", 48f, 120f + i * 22, 292f, body)
            },
        )
        val pages = listOf(toc) + book().map { it.copy(number = it.number + 1) }
        val all = TextCleaner.buildChapters(pages).flatMap { it.paragraphs }.joinToString(" ") { it.text }
        assertFalse(all.contains("...."))
        assertFalse(all.contains("Sumário"))
    }

    @Test
    fun withoutStructureGroupsByPages() {
        val pages = (1..23).map { n -> RawPage(n, 340f, 480f, para(120f, "Texto corrido da página $n sem nenhum título,", "continuando por algumas linhas para formar", "um parágrafo comum.")) }
        val chapters = TextCleaner.buildChapters(pages)
        assertEquals(listOf("Páginas 1–10", "Páginas 11–20", "Páginas 21–23"), chapters.map { it.title })
    }

    @Test
    fun normalizesLigaturesAndSoftHyphens() {
        assertEquals("ficção fluida", TextCleaner.normalizeLine("ﬁcção ﬂui­da"))
    }
}
