package tessera.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import org.apache.pdfbox.pdmodel.graphics.color.PDICCBased
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/** What the PDF says about itself, to fill the book information dialog. */
data class PdfInfo(val pages: Int, val title: String, val author: String, val subject: String, val keywords: String)

/**
 * The resolution for pages that must be rendered (text, vector drawings), which have no resolution
 * of their own: print quality. Pages that are images keep theirs, and a rendered page never goes
 * below the resolution of its own images.
 */
data class PdfImportOptions(val dpi: Int = 300)

/** How each page came out: copied as is, extracted losslessly, or rendered. */
data class PdfImportResult(val pages: Int, val originals: Int, val extracted: Int, val rendered: Int)

/**
 * Turns a PDF into a CBZ of page images, ready to get frames and an ACBF document in Tessera,
 * always at the best quality the PDF holds and without loss:
 *
 * - a page that is one JPEG covering it (a scanned comic) keeps that JPEG byte for byte;
 * - a page that is one other image keeps its pixels, at their own resolution, as PNG;
 * - any other page is rendered as PNG at [PdfImportOptions.dpi], or higher if its images need it.
 */
object PdfImport {
    fun inspect(pdf: File): PdfInfo = Loader.loadPDF(pdf).use { doc ->
        val info = doc.documentInformation
        PdfInfo(doc.numberOfPages, info?.title.orEmpty().trim(), info?.author.orEmpty().trim(), info?.subject.orEmpty().trim(), info?.keywords.orEmpty().trim())
    }

    /** Writes [target]; [progress] gets (pages done, total). Cancelling leaves no file behind. */
    suspend fun import(pdf: File, target: File, options: PdfImportOptions, progress: (Int, Int) -> Unit = { _, _ -> }): PdfImportResult =
        withContext(Dispatchers.IO) {
            val temp = File.createTempFile(".${target.name}.", ".tmp", target.absoluteFile.parentFile)
            try {
                var originals = 0
                var extracted = 0
                var rendered = 0
                val total: Int
                Loader.loadPDF(pdf).use { doc ->
                    total = doc.numberOfPages
                    // Images enlarged during rendering get bicubic interpolation, the best Java2D has.
                    val renderer = PDFRenderer(doc).apply {
                        renderingHints = java.awt.RenderingHints(mapOf(
                            java.awt.RenderingHints.KEY_INTERPOLATION to java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC,
                            java.awt.RenderingHints.KEY_RENDERING to java.awt.RenderingHints.VALUE_RENDER_QUALITY,
                            java.awt.RenderingHints.KEY_ANTIALIASING to java.awt.RenderingHints.VALUE_ANTIALIAS_ON,
                            java.awt.RenderingHints.KEY_TEXT_ANTIALIASING to java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
                        ))
                    }
                    val digits = maxOf(3, total.toString().length)
                    ZipOutputStream(temp.outputStream().buffered(1 shl 20)).use { zip ->
                        for (i in 0 until total) {
                            coroutineContext.ensureActive()
                            val name = "page-" + (i + 1).toString().padStart(digits, '0')
                            val image = fullPageImage(doc, i)
                            val jpeg = image?.let(::rawJpeg)
                            val pixels = if (image != null && jpeg == null) runCatching { image.image }.getOrNull() else null
                            when {
                                jpeg != null -> { originals++; zip.stored("$name.jpg", jpeg) }
                                pixels != null -> { extracted++; zip.deflated("$name.png", png(pixels)) }
                                else -> {
                                    rendered++
                                    val dpi = maxOf(options.dpi, imageDpi(doc, i)).coerceAtMost(MAX_DPI)
                                    zip.deflated("$name.png", png(renderer.renderImageWithDPI(i, dpi.toFloat(), ImageType.RGB)))
                                }
                            }
                            progress(i + 1, total)
                        }
                    }
                }
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                PdfImportResult(total, originals, extracted, rendered)
            } finally {
                temp.delete()
            }
        }

    private const val MAX_DPI = 600

    /** The page's only image when it covers the page alone: same proportions, no text, upright. */
    internal fun fullPageImage(doc: PDDocument, index: Int): PDImageXObject? {
        val page = doc.getPage(index)
        if (page.rotation % 360 != 0) return null
        val resources = page.resources ?: return null
        val names = resources.xObjectNames.toList()
        if (names.size != 1) return null
        val image = resources.getXObject(names[0]) as? PDImageXObject ?: return null
        if (image.cosObject.containsKey(COSName.SMASK) || image.isStencil) return null
        val box = page.cropBox
        val pageRatio = box.width / box.height
        val imageRatio = image.width.toFloat() / image.height
        if (abs(pageRatio - imageRatio) / pageRatio > 0.02f) return null
        val text = PDFTextStripper().apply { startPage = index + 1; endPage = index + 1 }.getText(doc)
        return image.takeIf { text.isBlank() }
    }

    /**
     * The image's JPEG bytes when they can be used unchanged: JPEG alone, grey or colour (an ICC
     * profile is fine), no decode array that would change its colours.
     */
    internal fun rawJpeg(image: PDImageXObject): ByteArray? {
        val stream = image.cosObject
        val onlyDct = when (val filters = stream.filters) {
            is COSName -> filters == COSName.DCT_DECODE
            is org.apache.pdfbox.cos.COSArray -> filters.size() == 1 && filters.getObject(0) == COSName.DCT_DECODE
            else -> false
        }
        if (!onlyDct || image.decode != null) return null
        val cs = runCatching { image.colorSpace }.getOrNull() ?: return null
        val simple = cs is PDDeviceRGB || cs is PDDeviceGray || (cs is PDICCBased && cs.numberOfComponents in setOf(1, 3))
        if (!simple) return null
        return stream.createRawInputStream().use { it.readBytes() }
    }

    /** The resolution at which the page's sharpest image is drawn, so rendering loses none of it. */
    private fun imageDpi(doc: PDDocument, index: Int): Int {
        val page = doc.getPage(index)
        val resources = page.resources ?: return 0
        val widthInches = page.cropBox.width / 72f
        return resources.xObjectNames.mapNotNull { resources.getXObject(it) as? PDImageXObject }
            .maxOfOrNull { (it.width / widthInches).toInt() } ?: 0
    }

    private fun png(image: BufferedImage): ByteArray = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

    private fun ZipOutputStream.stored(name: String, bytes: ByteArray) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size
            crc = CRC32().apply { update(bytes) }.value
        }
        putNextEntry(entry); write(bytes); closeEntry()
    }

    private fun ZipOutputStream.deflated(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name)); write(bytes); closeEntry()
    }
}
