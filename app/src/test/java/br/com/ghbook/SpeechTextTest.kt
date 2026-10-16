package br.com.ghbook

import br.com.ghbook.data.Chapter
import br.com.ghbook.data.Paragraph
import br.com.ghbook.data.ParagraphKind
import br.com.ghbook.tts.SpeechText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {

    @Test
    fun expandsAbbreviations() {
        assertEquals(
            "O Senhor Antônio e a Doutora Helena moram na Avenida Brasil, número 214, página 3.",
            SpeechText.normalize("O Sr. Antônio e a Dra. Helena moram na Av. Brasil, nº 214, pág. 3."),
        )
    }

    @Test
    fun doesNotExpandInsideWords() {
        assertEquals("Sra. é diferente de Srta.".let { SpeechText.normalize(it) }, "Senhora é diferente de Senhorita")
        assertEquals("Desp. etc. fim", SpeechText.normalize("Desp. etc. fim").replace("etcétera", "etc."))
    }

    @Test
    fun convertsRomanNumeralsInHeadings() {
        assertEquals("Capítulo 4. O mar.", SpeechText.normalize("Capítulo IV — O mar", heading = true))
        assertEquals("Parte 12.", SpeechText.normalize("PARTE XII", heading = true).replace("PARTE", "Parte"))
        assertEquals(1994, SpeechText.romanToInt("MCMXCIV"))
    }

    @Test
    fun removesFootnoteMarkers() {
        assertEquals("O farol foi construído em 1923.", SpeechText.normalize("O farol¹ foi construído em 1923.[2]").replace("farol foi", "farol foi"))
    }

    @Test
    fun splitsSentencesAndChunks() {
        val text = "Primeira frase. Segunda frase! Terceira? Quarta frase bem mais longa, com vírgulas, e mais palavras."
        assertEquals(4, SpeechText.sentences(text).size)
        val chunks = SpeechText.chunk(text, 40)
        assertTrue(chunks.all { it.length <= 41 })
        assertEquals(text.replace(" ", ""), chunks.joinToString("").replace(" ", ""))
    }

    @Test
    fun segmentsKeepParagraphMappingAndOffsets() {
        val ch = Chapter(
            0, "Capítulo 1",
            listOf(
                Paragraph("Capítulo 1", 1, ParagraphKind.HEADING),
                Paragraph("Era uma vez. Um farol.", 1),
                Paragraph("***", 1),
                Paragraph("Fim.", 2),
            ),
        )
        val segs = SpeechText.segments(ch, 400)
        assertEquals(listOf(0, 1, 3), segs.map { it.paragraph })
        assertTrue(segs[0].isHeading)
        assertTrue(segs.zipWithNext().all { (a, b) -> b.charOffset > a.charOffset })
        assertFalse(segs.any { it.text.contains("***") })
    }
}
