package br.com.ghbook.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.min

class PdfException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class ExtractionResult(
    val title: String?,
    val author: String?,
    val pageCount: Int,
    val pages: List<RawPage>,
    val outline: List<OutlineItem>,
    val ocrPages: List<Int>,
    val unreadablePages: List<Int>,
)

/**
 * Extrai linhas de texto com posição e tamanho de fonte usando PdfBox-Android.
 * Páginas sem texto selecionável mas com imagens são tratadas como digitalizadas e passam por OCR local
 * (ML Kit, modelo embarcado no APK — funciona sem internet).
 */
class PdfExtractor(private val context: Context) {

    private class LineCollector : PDFTextStripper() {
        val pages = ArrayList<RawPage>()
        private var lines = ArrayList<RawLine>()
        private val current = StringBuilder()
        private val positions = ArrayList<TextPosition>()
        private var pageWidth = 0f
        private var pageHeight = 0f

        override fun startPage(page: PDPage) {
            lines = ArrayList()
            val box = page.cropBox ?: page.mediaBox
            val rotated = page.rotation % 180 != 0
            pageWidth = if (rotated) box.height else box.width
            pageHeight = if (rotated) box.width else box.height
            super.startPage(page)
        }

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            current.append(text)
            positions.addAll(textPositions)
        }

        override fun writeWordSeparator() {
            current.append(' ')
        }

        override fun writeLineSeparator() {
            finishLine()
        }

        private fun finishLine() {
            val text = current.toString()
            if (text.isNotBlank() && positions.isNotEmpty()) {
                val first = positions.first()
                val last = positions.last()
                val sizes = positions.map { p -> p.fontSizeInPt.takeIf { it > 1.5f } ?: (p.heightDir * 1.4f) }.sorted()
                val bold = positions.count { p ->
                    val n = p.font?.name?.lowercase() ?: ""
                    "bold" in n || "black" in n || "heavy" in n || "semibold" in n || "demi" in n
                } > positions.size / 2
                lines += RawLine(
                    text = text,
                    x = first.xDirAdj,
                    y = first.yDirAdj,
                    endX = last.xDirAdj + last.widthDirAdj,
                    fontSize = sizes[sizes.size / 2],
                    bold = bold,
                )
            }
            current.setLength(0)
            positions.clear()
        }

        override fun endPage(page: PDPage) {
            finishLine()
            pages += RawPage(currentPageNo, pageWidth, pageHeight, lines)
            super.endPage(page)
        }
    }

    /**
     * @param onProgress (páginas processadas, total, mensagem)
     */
    fun extract(pdf: File, onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }, isCancelled: () -> Boolean = { false }): ExtractionResult {
        val doc = try {
            PDDocument.load(pdf, MemoryUsageSetting.setupTempFileOnly())
        } catch (e: InvalidPasswordException) {
            throw PdfException("Este PDF é protegido por senha e não pode ser aberto.", e)
        } catch (e: IOException) {
            throw PdfException("Arquivo PDF inválido ou corrompido: ${e.message ?: "erro de leitura"}", e)
        } catch (e: OutOfMemoryError) {
            throw PdfException("Memória insuficiente para abrir este PDF.", e)
        }
        doc.use { document ->
            if (document.isEncrypted) {
                runCatching { document.isAllSecurityToBeRemoved = true }
            }
            val total = document.numberOfPages
            if (total == 0) throw PdfException("O PDF não contém páginas.")
            val info = runCatching { document.documentInformation }.getOrNull()
            val outline = runCatching { readOutline(document) }.getOrDefault(emptyList())

            val allPages = ArrayList<RawPage>(total)
            val batch = 16
            var start = 1
            while (start <= total) {
                if (isCancelled()) throw PdfException("Importação cancelada.")
                val end = min(total, start + batch - 1)
                val collector = LineCollector().apply {
                    sortByPosition = false
                    setShouldSeparateByBeads(true)
                    startPage = start
                    endPage = end
                    addMoreFormatting = false
                }
                try {
                    collector.getText(document)
                } catch (e: Exception) {
                    // Falha numa faixa de páginas não deve perder o livro inteiro: tenta página a página.
                    // Descarta as páginas já coletadas antes da falha, senão elas ficariam duplicadas.
                    collector.pages.clear()
                    for (pn in start..end) {
                        val single = LineCollector().apply { startPage = pn; endPage = pn }
                        runCatching { single.getText(document) }
                        collector.pages += single.pages.ifEmpty { listOf(RawPage(pn, 0f, 0f, emptyList())) }
                    }
                }
                allPages += collector.pages
                onProgress(end, total, "Extraindo texto: página $end de $total")
                start = end + 1
            }

            // Páginas sem texto selecionável: verificar se são digitalizadas (contêm imagem) para OCR.
            val needOcr = allPages.filter { p -> p.lines.sumOf { l -> l.text.count { !it.isWhitespace() } } < 15 }
                .map { it.number }
                .filter { pn -> runCatching { pageHasImage(document.getPage(pn - 1)) }.getOrDefault(true) }

            val ocrDone = ArrayList<Int>()
            val unreadable = ArrayList<Int>()
            if (needOcr.isNotEmpty()) {
                val ocrPages = runOcr(pdf, needOcr, onProgress, isCancelled)
                for (pn in needOcr) {
                    val r = ocrPages[pn]
                    if (r != null && r.lines.isNotEmpty()) {
                        val idx = allPages.indexOfFirst { it.number == pn }
                        if (idx >= 0) allPages[idx] = r
                        ocrDone += pn
                    } else unreadable += pn
                }
            }

            return ExtractionResult(
                title = info?.title?.trim()?.takeIf { it.length in 2..200 && !it.contains("untitled", true) && !it.endsWith(".doc", true) },
                author = info?.author?.trim()?.takeIf { it.length in 2..120 },
                pageCount = total,
                pages = allPages,
                outline = outline,
                ocrPages = ocrDone,
                unreadablePages = unreadable,
            )
        }
    }

    private fun pageHasImage(page: PDPage): Boolean {
        val res = page.resources ?: return false
        return res.xObjectNames.any { name -> runCatching { res.getXObject(name) is PDImageXObject }.getOrDefault(false) }
    }

    private fun readOutline(doc: PDDocument): List<OutlineItem> {
        val root = doc.documentCatalog?.documentOutline ?: return emptyList()
        val result = ArrayList<OutlineItem>()
        var item: PDOutlineItem? = root.firstChild
        while (item != null) {
            val title = item.title?.trim().orEmpty()
            val page = runCatching {
                val dest = item!!.destination ?: (item!!.action as? com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo)?.destination
                when (dest) {
                    is PDPageDestination -> {
                        val p = dest.page
                        if (p != null) doc.pages.indexOf(p) + 1 else dest.pageNumber + 1
                    }
                    else -> item!!.findDestinationPage(doc)?.let { doc.pages.indexOf(it) + 1 } ?: -1
                }
            }.getOrDefault(-1)
            if (title.isNotEmpty() && page > 0) result += OutlineItem(title, page)
            item = item.nextSibling
        }
        return result
    }

    /** OCR local das páginas indicadas. Renderiza com PdfRenderer e reconhece com ML Kit (offline). */
    private fun runOcr(pdf: File, pageNumbers: List<Int>, onProgress: (Int, Int, String) -> Unit, isCancelled: () -> Boolean): Map<Int, RawPage> {
        val out = HashMap<Int, RawPage>()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    pageNumbers.forEachIndexed { i, pn ->
                        if (isCancelled()) throw PdfException("Importação cancelada.")
                        onProgress(i, pageNumbers.size, "Reconhecendo texto (OCR) da página digitalizada $pn — ${i + 1} de ${pageNumbers.size}")
                        val page = renderer.openPage(pn - 1)
                        val scale = (2200f / maxOf(page.width, page.height)).coerceIn(1.5f, 4f)
                        val w = (page.width * scale).toInt()
                        val h = (page.height * scale).toInt()
                        val bmp = try {
                            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        } catch (e: OutOfMemoryError) {
                            page.close(); return@forEachIndexed
                        }
                        bmp.eraseColor(Color.WHITE)
                        // Falha ao renderizar uma página não pode deixá-la aberta (o PdfRenderer recusaria as seguintes).
                        val rendered = runCatching { page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }.isSuccess
                        val pw = page.width.toFloat()
                        val ph = page.height.toFloat()
                        page.close()
                        if (!rendered) {
                            bmp.recycle()
                            return@forEachIndexed
                        }
                        try {
                            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)))
                            val lines = text.textBlocks.flatMap { block ->
                                block.lines.mapNotNull { line ->
                                    val box = line.boundingBox ?: return@mapNotNull null
                                    RawLine(
                                        text = line.text,
                                        x = box.left / scale,
                                        y = box.bottom / scale,
                                        endX = box.right / scale,
                                        fontSize = box.height() / scale * 0.95f,
                                    )
                                }
                            }
                            out[pn] = RawPage(pn, pw, ph, lines, ocr = true)
                        } catch (e: Exception) {
                            // página fica marcada como não reconhecida
                        } finally {
                            bmp.recycle()
                        }
                    }
                }
            }
        } catch (e: PdfException) {
            throw e
        } catch (e: Exception) {
            // PdfRenderer falhou (ex.: PDF não suportado pelo renderizador do sistema): sem OCR.
        } finally {
            recognizer.close()
        }
        return out
    }

    /** Gera a capa (miniatura da primeira página). */
    fun renderCover(pdf: File, out: File) {
        runCatching {
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (renderer.pageCount == 0) return
                    renderer.openPage(0).use { page ->
                        val w = 360
                        val h = (w * page.height.toFloat() / page.width).toInt().coerceIn(200, 720)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                        bmp.recycle()
                    }
                }
            }
        }
    }
}
