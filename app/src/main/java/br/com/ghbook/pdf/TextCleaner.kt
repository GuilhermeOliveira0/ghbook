package br.com.ghbook.pdf

import br.com.ghbook.data.Chapter
import br.com.ghbook.data.Paragraph
import br.com.ghbook.data.ParagraphKind
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** Linha de texto com geometria (coordenadas com origem no topo esquerdo da página, em pontos). */
data class RawLine(
    val text: String,
    val x: Float,
    val y: Float,
    val endX: Float,
    val fontSize: Float,
    val bold: Boolean = false,
)

data class RawPage(
    /** Número da página (1-based). */
    val number: Int,
    val width: Float,
    val height: Float,
    val lines: List<RawLine>,
    val ocr: Boolean = false,
)

/** Item do sumário embutido (bookmarks) do PDF. */
data class OutlineItem(val title: String, val page: Int)

/**
 * Transforma linhas extraídas do PDF em capítulos e parágrafos legíveis/narráveis:
 * remove cabeçalhos e rodapés repetidos, números de página, notas de rodapé e páginas de sumário,
 * junta linhas em parágrafos (desfazendo hifenização) e detecta títulos e capítulos.
 *
 * É Kotlin puro (sem dependências Android) para poder ser testado na JVM.
 */
object TextCleaner {

    private val chapterRegex = Regex(
        "^(cap[ií]tulo|chapter|parte|part|livro|pr[óo]logo|ep[íi]logo|introdu[çc][ãa]o|pref[áa]cio|" +
            "conclus[ãa]o|ap[êe]ndice|apresenta[çc][ãa]o|agradecimentos|nota do autor|pos?f[áa]cio|ato|cena)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val chapterLabelOnly = Regex(
        "^(cap[ií]tulo|chapter|parte|part|livro)\\s+([0-9]{1,3}|[ivxlcdm]{1,7}|[a-zçéúíóâêô]+)\\.?$",
        RegexOption.IGNORE_CASE,
    )
    private val pageNumberRegex = Regex(
        "^[-–—\\s|]*((p[áa]g(ina)?|page|p)\\.?\\s*)?([0-9]{1,4}|[ivxlcdm]{1,7})(\\s*(/|de|of)\\s*[0-9]{1,4})?[-–—\\s|]*$",
        RegexOption.IGNORE_CASE,
    )
    private val tocLineRegex = Regex("^.{2,}?([\\s.·…_\\-]{3,}|\\s{2,}|\\t)([0-9]{1,4}|[ivxlcdm]{1,6})$", RegexOption.IGNORE_CASE)
    private val footnoteStart = Regex("^([0-9]{1,3}|[¹²³⁴⁵⁶⁷⁸⁹⁰]+|\\*{1,3}|†)[\\s.)]")
    private val sentenceEnd = Regex("[.!?…:;\"”»’)\\]]$")
    private val bulletStart = Regex("^([•●▪■◦\\-–—]|[0-9]{1,3}[.)]|[a-z][.)])\\s+")

    fun normalizeLine(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                'ﬀ' -> sb.append("ff")
                'ﬁ' -> sb.append("fi")
                'ﬂ' -> sb.append("fl")
                'ﬃ' -> sb.append("ffi")
                'ﬄ' -> sb.append("ffl")
                '­', '​', '﻿' -> {}
                ' ', '\t', ' ', ' ' -> sb.append(' ')
                else -> if (!c.isISOControl()) sb.append(c)
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun edgeKey(s: String): String =
        s.lowercase().replace(Regex("[0-9]+"), "#").replace(Regex("[^\\p{L}#]+"), " ").trim()

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    /** Tamanho de fonte do corpo do texto: moda ponderada pela quantidade de caracteres. */
    fun bodyFontSize(pages: List<RawPage>): Float {
        val hist = HashMap<Int, Int>()
        pages.forEach { p -> p.lines.forEach { l -> hist.merge((l.fontSize * 2).toInt(), l.text.length, Int::plus) } }
        val best = hist.maxByOrNull { it.value }?.key ?: return 10f
        return max(best / 2f, 1f)
    }

    data class Cleaned(val pages: List<RawPage>, val removedLines: Int)

    /** Remove cabeçalhos/rodapés repetidos, números de página, notas de rodapé e páginas de sumário. */
    fun removeBoilerplate(input: List<RawPage>): Cleaned {
        val pages = input.map { p ->
            p.copy(lines = p.lines.map { it.copy(text = normalizeLine(it.text)) }.filter { it.text.isNotEmpty() })
        }
        var removed = 0
        val textBody = bodyFontSize(pages.filter { !it.ocr })
        val ocrBody = bodyFontSize(pages.filter { it.ocr })
        fun body(p: RawPage) = if (p.ocr) ocrBody else textBody

        // Candidatos de borda: as duas linhas mais altas e as duas mais baixas de cada página.
        fun edgeLines(p: RawPage): Set<RawLine> {
            val byY = p.lines.sortedBy { it.y }
            val candidates = (byY.take(2) + byY.takeLast(2)).toSet()
            // Só linhas na faixa superior/inferior da página contam como cabeçalho/rodapé.
            return if (p.height > 0) candidates.filter { it.y < p.height * 0.16f || it.y > p.height * 0.84f }.toSet() else candidates
        }

        val pagesWithText = pages.count { it.lines.isNotEmpty() }
        val threshold = max(3, ceil(pagesWithText * 0.25).toInt())
        val keyPages = HashMap<String, MutableSet<Int>>()
        if (pagesWithText >= 3) {
            pages.forEach { p ->
                edgeLines(p).forEach { l ->
                    val k = edgeKey(l.text)
                    if (k.isNotBlank() && k.replace("#", "").isNotBlank() && l.text.length <= 90 && l.fontSize <= body(p) * 1.15f) {
                        keyPages.getOrPut(k) { HashSet() }.add(p.number)
                    }
                }
            }
        }
        val repeated = keyPages.filterValues { it.size >= threshold }.keys

        val result = pages.map { p ->
            val b = body(p)
            val edges = edgeLines(p)
            // Página de sumário (muitas linhas terminando em número de página) não é narrada.
            val tocLike = p.lines.size >= 5 && p.lines.count { tocLineRegex.matches(it.text) } >= p.lines.size * 0.4
            if (tocLike) {
                removed += p.lines.size
                return@map p.copy(lines = emptyList())
            }
            var kept = p.lines.filter { l ->
                val isEdge = l in edges
                val drop = isEdge && (
                    (pageNumberRegex.matches(l.text) && l.fontSize < b * 1.2f) ||
                        (edgeKey(l.text) in repeated && l.fontSize <= b * 1.15f)
                    )
                if (drop) removed++
                !drop
            }
            // Notas de rodapé: bloco final em fonte menor, iniciado por marcador numérico/asterisco.
            if (kept.size >= 3 && kept.any { it.fontSize >= b * 0.95f }) {
                val byY = kept.sortedBy { it.y }
                var start = byY.size
                while (start > 0) {
                    val l = byY[start - 1]
                    if (l.fontSize <= b * 0.85f && l.y > p.height * 0.55f) start-- else break
                }
                if (start < byY.size) {
                    val block = byY.subList(start, byY.size)
                    if (footnoteStart.containsMatchIn(block.first().text)) {
                        removed += block.size
                        val blockSet = block.toSet()
                        kept = kept.filter { it !in blockSet }
                    }
                }
            }
            p.copy(lines = kept)
        }
        return Cleaned(result, removed)
    }

    private enum class HeadingLevel { NONE, SECTION, CHAPTER }

    private fun headingLevel(l: RawLine, body: Float, standalone: Boolean): HeadingLevel {
        val t = l.text
        if (t.length > 150 || t.none { it.isLetter() }) return HeadingLevel.NONE
        val ratio = if (body > 0) l.fontSize / body else 1f
        val matchesChapter = t.first().isUpperCase() && chapterRegex.containsMatchIn(t) && t.length <= 90 && !t.endsWith(",")
        return when {
            ratio >= 1.45f && t.length <= 120 -> HeadingLevel.CHAPTER
            matchesChapter && (ratio >= 1.1f || l.bold || chapterLabelOnly.matches(t) || (standalone && !sentenceEnd.containsMatchIn(t.trimEnd('.')))) -> HeadingLevel.CHAPTER
            ratio >= 1.18f && t.length <= 120 -> HeadingLevel.SECTION
            l.bold && standalone && t.length <= 90 && !t.endsWith(".") && ratio >= 0.98f -> HeadingLevel.SECTION
            else -> HeadingLevel.NONE
        }
    }

    private data class Block(val text: String, val page: Int, val level: HeadingLevel)

    /** Junta linhas em parágrafos e títulos, preservando a ordem de leitura. */
    private fun assemble(pages: List<RawPage>): List<Block> {
        val textBody = bodyFontSize(pages.filter { !it.ocr })
        val ocrBody = bodyFontSize(pages.filter { it.ocr })
        val out = ArrayList<Block>()
        val buf = StringBuilder()
        var bufPage = 0

        fun flush() {
            val t = buf.toString().trim()
            if (t.isNotEmpty()) out += Block(t, bufPage, HeadingLevel.NONE)
            buf.setLength(0)
        }

        fun append(text: String, page: Int) {
            if (buf.isEmpty()) {
                bufPage = page
                buf.append(text)
                return
            }
            val last = buf.last()
            val prevChar = if (buf.length >= 2) buf[buf.length - 2] else ' '
            if ((last == '-' || last == '‐') && prevChar.isLetter() && text.firstOrNull()?.isLowerCase() == true) {
                buf.setLength(buf.length - 1)
                buf.append(text)
            } else {
                buf.append(' ').append(text)
            }
        }

        for (p in pages) {
            if (p.lines.isEmpty()) continue
            val body = if (p.ocr) ocrBody else textBody
            val lines = p.lines
            val textLines = lines.filter { it.text.length > 25 }
            val left = if (textLines.isNotEmpty()) textLines.minOf { it.x } else lines.minOf { it.x }
            val right = if (textLines.size >= 3) textLines.map { it.endX }.sorted().let { it[(it.size * 0.85).toInt().coerceAtMost(it.size - 1)] } else lines.maxOf { it.endX }
            val gaps = lines.zipWithNext { a, b -> b.y - a.y }.filter { it > 0 && it < body * 4 }
            val typicalGap = median(gaps).takeIf { it > 0 } ?: (body * 1.3f)

            // Agrupa linhas consecutivas que formam um mesmo título.
            var i = 0
            var prev: RawLine? = null
            var dropCap: String? = null
            while (i < lines.size) {
                val raw = lines[i]
                // Letra capitular (inicial grande) é unida à linha seguinte.
                if (raw.text.length <= 2 && raw.text.all { it.isLetter() } && raw.fontSize >= body * 1.45f && i + 1 < lines.size) {
                    dropCap = raw.text
                    i++
                    continue
                }
                val l = if (dropCap != null) raw.copy(text = dropCap + raw.text, fontSize = lines[i].fontSize) else raw
                dropCap = null
                val gapBefore = prev?.let { l.y - it.y } ?: Float.MAX_VALUE
                val gapAfter = if (i + 1 < lines.size) lines[i + 1].y - l.y else Float.MAX_VALUE
                val standalone = (gapBefore > typicalGap * 1.4f || prev == null || sentenceEnd.containsMatchIn(prev.text)) &&
                    (gapAfter > typicalGap * 1.4f || i + 1 >= lines.size || lines[i + 1].fontSize < l.fontSize * 0.9f)
                val level = headingLevel(l, body, standalone)
                if (level != HeadingLevel.NONE) {
                    flush()
                    val parts = mutableListOf(l.text)
                    var lv = level
                    var j = i + 1
                    while (j < lines.size && parts.size < 4) {
                        val n = lines[j]
                        val nLevel = headingLevel(n, body, true)
                        val close = n.y - lines[j - 1].y in 0f..(max(l.fontSize, n.fontSize) * 3.2f)
                        val similar = abs(n.fontSize - lines[j - 1].fontSize) <= max(1f, lines[j - 1].fontSize * 0.25f)
                        if (nLevel != HeadingLevel.NONE && close && (similar || chapterLabelOnly.matches(parts.last()))) {
                            parts += n.text
                            if (nLevel == HeadingLevel.CHAPTER) lv = HeadingLevel.CHAPTER
                            j++
                        } else break
                    }
                    val title = if (parts.size > 1 && chapterLabelOnly.matches(parts[0])) {
                        parts[0] + " — " + parts.drop(1).joinToString(" ")
                    } else parts.joinToString(" ")
                    out += Block(title.replace(Regex("\\s+"), " ").trim(), p.number, lv)
                    prev = lines[j - 1]
                    i = j
                    continue
                }

                if (buf.isEmpty()) {
                    append(l.text, p.number)
                } else if (prev == null) {
                    // Primeira linha da página: continua o parágrafo aberto apenas se a frase não terminou.
                    val open = buf.toString()
                    val continues = !sentenceEnd.containsMatchIn(open) &&
                        (l.text.first().isLowerCase() || open.endsWith("-") || open.endsWith(",") || !bulletStart.containsMatchIn(l.text))
                    if (!continues) flush()
                    append(l.text, p.number)
                } else {
                    val prevEnded = sentenceEnd.containsMatchIn(prev.text)
                    val indent = l.x > left + body * 0.8f
                    val shortPrev = prev.endX < right - body * 2.5f
                    val gap = l.y - prev.y
                    val bigGap = gap > typicalGap * 1.6f
                    val hugeGap = gap > typicalGap * 2.6f
                    val bullet = bulletStart.containsMatchIn(l.text)
                    val newPara = hugeGap || (prevEnded && (indent || shortPrev || bigGap || bullet))
                    if (newPara) flush()
                    append(l.text, p.number)
                }
                prev = l
                i++
            }
        }
        flush()
        return out
    }

    /**
     * Pipeline completo: limpa, monta parágrafos e divide em capítulos.
     * Se o PDF tiver sumário embutido (outline), ele define os capítulos; senão usa os títulos detectados;
     * na falta de ambos, agrupa por blocos de páginas.
     */
    fun buildChapters(rawPages: List<RawPage>, outline: List<OutlineItem> = emptyList()): List<Chapter> {
        val cleaned = removeBoilerplate(rawPages).pages
        val blocks = assemble(cleaned)
        if (blocks.isEmpty()) return emptyList()

        data class Draft(var title: String, val paragraphs: MutableList<Paragraph>)
        val drafts = ArrayList<Draft>()

        val validOutline = outline
            .filter { it.title.isNotBlank() && it.page >= 1 }
            .sortedBy { it.page }
            .distinctBy { it.page }
        val useOutline = validOutline.size >= 2

        fun toParagraph(b: Block) = Paragraph(
            text = b.text,
            page = b.page,
            kind = if (b.level == HeadingLevel.NONE) ParagraphKind.TEXT else ParagraphKind.HEADING,
        )

        if (useOutline) {
            // Fronteira do capítulo: o título correspondente na página de início; senão o começo da página.
            val boundaries = IntArray(validOutline.size) { -1 }
            var searchFrom = 0
            validOutline.forEachIndexed { k, item ->
                val key = edgeKey(item.title)
                var idx = (searchFrom until blocks.size).firstOrNull { bi ->
                    val b = blocks[bi]
                    b.page == item.page && b.level != HeadingLevel.NONE &&
                        (edgeKey(b.text).contains(key) || key.contains(edgeKey(b.text)))
                }
                if (idx == null) idx = (searchFrom until blocks.size).firstOrNull { blocks[it].page >= item.page }
                boundaries[k] = idx ?: blocks.size
                if (idx != null) searchFrom = idx
            }
            if (boundaries[0] > 0) {
                drafts += Draft("Início", blocks.subList(0, boundaries[0]).map(::toParagraph).toMutableList())
            }
            validOutline.forEachIndexed { k, item ->
                val from = boundaries[k]
                val to = if (k + 1 < boundaries.size) boundaries[k + 1] else blocks.size
                if (to > from) drafts += Draft(normalizeLine(item.title), blocks.subList(from, to).map(::toParagraph).toMutableList())
            }
        } else {
            val chapterIdx = blocks.indices.filter { blocks[it].level == HeadingLevel.CHAPTER }
            val sectionIdx = blocks.indices.filter { blocks[it].level == HeadingLevel.SECTION }
            val starts = when {
                chapterIdx.size >= 2 -> chapterIdx
                sectionIdx.size >= 3 -> (chapterIdx + sectionIdx).sorted()
                else -> emptyList()
            }
            if (starts.isNotEmpty()) {
                if (starts[0] > 0) drafts += Draft("Início", blocks.subList(0, starts[0]).map(::toParagraph).toMutableList())
                starts.forEachIndexed { k, s ->
                    val e = if (k + 1 < starts.size) starts[k + 1] else blocks.size
                    drafts += Draft(blocks[s].text, blocks.subList(s, e).map(::toParagraph).toMutableList())
                }
            } else {
                // Sem estrutura detectável: blocos de ~10 páginas para permitir navegação.
                val groupSize = 10
                blocks.groupBy { (it.page - 1) / groupSize }.toSortedMap().forEach { (_, list) ->
                    val first = list.first().page
                    val last = list.last().page
                    drafts += Draft(if (first == last) "Página $first" else "Páginas $first–$last", list.map(::toParagraph).toMutableList())
                }
            }
        }

        // Capítulos sem texto (ex.: "Parte I" seguido de "Capítulo 1") são fundidos ao seguinte.
        val merged = ArrayList<Draft>()
        var pending: Draft? = null
        for (d in drafts) {
            val hasText = d.paragraphs.any { it.kind == ParagraphKind.TEXT }
            // Só rótulos de divisão ("Parte I", "Livro 2") entram no título combinado; folha de rosto e afins não.
            fun combine(a: String, b: String) =
                if (a != "Início" && chapterRegex.containsMatchIn(a) && a.length + b.length < 80 && a != b) "$a — $b" else b
            if (!hasText) {
                pending = pending?.let { pd -> Draft(combine(pd.title, d.title), (pd.paragraphs + d.paragraphs).toMutableList()) } ?: d
                continue
            }
            if (pending != null) {
                val title = combine(pending.title, d.title)
                merged += Draft(title, (pending.paragraphs + d.paragraphs).toMutableList())
                pending = null
            } else merged += d
        }
        pending?.let { pd ->
            if (merged.isEmpty()) merged += pd else merged.last().paragraphs.addAll(pd.paragraphs)
        }
        // Início muito curto (ex.: só a folha de rosto) é fundido ao primeiro capítulo.
        if (merged.size >= 2 && merged[0].title == "Início" && merged[0].paragraphs.sumOf { it.text.length } < 300) {
            val first = merged.removeAt(0)
            merged[0].paragraphs.addAll(0, first.paragraphs)
        }

        return merged.mapIndexed { i, d ->
            Chapter(index = i, title = d.title.take(140), paragraphs = d.paragraphs.toList())
        }
    }
}
