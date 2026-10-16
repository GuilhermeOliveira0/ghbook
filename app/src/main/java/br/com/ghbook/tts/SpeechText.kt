package br.com.ghbook.tts

import br.com.ghbook.data.Chapter
import br.com.ghbook.data.ParagraphKind

/** Trecho falado: um pedaço de um parágrafo, pequeno o suficiente para o TextToSpeech. */
data class SpeechSegment(
    val paragraph: Int,
    val text: String,
    /** Deslocamento (em caracteres falados) do início do segmento dentro do capítulo — base da estimativa de tempo. */
    val charOffset: Long,
    val isHeading: Boolean = false,
)

/**
 * Prepara texto para narração em português: expande abreviações, converte numerais romanos de títulos,
 * remove marcadores de nota de rodapé e divide em frases/segmentos.
 * Kotlin puro (testável na JVM).
 */
object SpeechText {

    /** Estimativa de duração da fala em velocidade 1.0 (pt-BR ≈ 14–15 caracteres por segundo). */
    const val MS_PER_CHAR = 68L

    private val abbreviations: List<Pair<Regex, String>> = listOf(
        "Sr." to "Senhor", "Sra." to "Senhora", "Srta." to "Senhorita", "Srs." to "Senhores",
        "Dr." to "Doutor", "Dra." to "Doutora", "Drs." to "Doutores", "Prof." to "Professor", "Profa." to "Professora",
        "Exmo." to "Excelentíssimo", "Exma." to "Excelentíssima", "V.Exa." to "Vossa Excelência", "Sto." to "Santo", "Sta." to "Santa",
        "Av." to "Avenida", "Cia." to "Companhia", "Ltda." to "Limitada", "Gov." to "Governador", "Pres." to "Presidente",
        "Eng." to "Engenheiro", "Gen." to "General", "Cel." to "Coronel", "Cap." to "Capítulo", "cap." to "capítulo",
        "pág." to "página", "págs." to "páginas", "p." to "página", "pp." to "páginas", "vol." to "volume", "ed." to "edição",
        "etc." to "etcétera", "ex.:" to "exemplo:", "Ex.:" to "Exemplo:", "obs.:" to "observação:", "Obs.:" to "Observação:",
        "aprox." to "aproximadamente", "tel." to "telefone", "séc." to "século", "a.C." to "antes de Cristo", "d.C." to "depois de Cristo",
        "i.e." to "isto é", "e.g." to "por exemplo", "vs." to "versus", "km/h" to "quilômetros por hora",
    ).map { (abbr, full) ->
        val pattern = (if (abbr.first().isLetter()) "(?<![\\p{L}.])" else "") + Regex.escape(abbr) + (if (abbr.last().isLetter()) "(?!\\p{L})" else "")
        Regex(pattern) to full
    }

    private val ordinalNumber = Regex("\\b[nN]\\.?\\s?[ºo°]\\s?(?=\\d)")
    private val footnoteMarkers = Regex("(?<=[\\p{L}.,;:!?\"”)])[¹²³⁴⁵⁶⁷⁸⁹⁰]+|\\[\\d{1,3}]|(?<=[\\p{L}])\\*+(?=\\s|$)")
    private val url = Regex("https?://(www\\.)?")
    private val bulletChars = Regex("^[•●▪■◦]\\s*")
    private val headingRoman = Regex("^((?:cap[ií]tulo|parte|livro|chapter|part|ato|cena)\\s+)([IVXLCDM]{1,7})\\b", RegexOption.IGNORE_CASE)
    private val sentenceSplit = Regex("(?<=[.!?…])[\"”»’)]*\\s+(?=[\\p{Lu}0-9\"“«(—–-])")

    fun romanToInt(s: String): Int? {
        val map = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        var total = 0
        var prev = 0
        for (c in s.uppercase().reversed()) {
            val v = map[c] ?: return null
            if (v < prev) total -= v else { total += v; prev = v }
        }
        return total.takeIf { it > 0 }
    }

    fun normalize(text: String, heading: Boolean = false): String {
        var t = text
        t = bulletChars.replace(t, "")
        t = footnoteMarkers.replace(t, "")
        t = url.replace(t, "")
        t = ordinalNumber.replace(t, "número ")
        t = t.replace("§", " parágrafo ").replace("&", " e ").replace(" / ", ", ")
        for ((re, full) in abbreviations) t = re.replace(t, full)
        if (heading) {
            t = headingRoman.replace(t) { m ->
                val n = romanToInt(m.groupValues[2])
                if (n != null) m.groupValues[1] + n else m.value
            }
            t = t.replace(" — ", ". ").replace(" – ", ". ")
            if (t.isNotEmpty() && t.last().isLetterOrDigit()) t += "."
        }
        t = t.replace(Regex("[_=~]{3,}"), " ").replace(Regex("\\.{4,}"), "…")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    fun sentences(text: String): List<String> = text.split(sentenceSplit).map { it.trim() }.filter { it.isNotEmpty() }

    /** Divide um texto em pedaços de até [maxLen] caracteres, preferindo limites de frase, vírgula e espaço. */
    fun chunk(text: String, maxLen: Int): List<String> {
        if (text.length <= maxLen) return listOf(text)
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (s in sentences(text)) {
            if (s.length > maxLen) {
                if (cur.isNotEmpty()) { out += cur.toString(); cur.setLength(0) }
                var rest = s
                while (rest.length > maxLen) {
                    var cut = rest.lastIndexOfAny(charArrayOf(',', ';', ':'), maxLen)
                    if (cut < maxLen / 3) cut = rest.lastIndexOf(' ', maxLen)
                    if (cut < maxLen / 3) cut = maxLen
                    out += rest.substring(0, cut + if (cut < rest.length && rest[cut] != ' ') 1 else 0).trim()
                    rest = rest.substring(minOf(rest.length, cut + 1)).trim()
                }
                if (rest.isNotEmpty()) cur.append(rest)
            } else if (cur.length + s.length + 1 > maxLen) {
                out += cur.toString(); cur.setLength(0); cur.append(s)
            } else {
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(s)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out.filter { it.isNotBlank() }
    }

    /** Segmentos de fala de um capítulo inteiro. */
    fun segments(chapter: Chapter, maxLen: Int): List<SpeechSegment> {
        val out = ArrayList<SpeechSegment>()
        var offset = 0L
        chapter.paragraphs.forEachIndexed { pi, p ->
            val heading = p.kind == ParagraphKind.HEADING
            val norm = normalize(p.text, heading)
            if (norm.isEmpty() || norm.none { it.isLetterOrDigit() }) return@forEachIndexed
            for (piece in chunk(norm, maxLen)) {
                out += SpeechSegment(pi, piece, offset, heading)
                offset += piece.length + if (heading) 20 else 4 // pausas aproximadas
            }
        }
        return out
    }

    fun totalChars(segments: List<SpeechSegment>): Long =
        segments.lastOrNull()?.let { it.charOffset + it.text.length } ?: 0L
}
