package tessera.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB
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
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/** What the PDF says about itself, to fill the book information dialog. */
data class PdfInfo(val pages: Int, val title: String, val author: String, val subject: String, val keywords: String)

enum class PageFormat(val extension: String) { Jpeg("jpg"), Png("png") }

/** How pages that are not a single embedded image get rendered. */
data class PdfImportOptions(val dpi: Int = 200, val format: PageFormat = PageFormat.Jpeg, val keepOriginals: Boolean = true)

data class PdfImportResult(val pages: Int, val originals: Int)

/**
 * Turns a PDF into a CBZ of page images, ready to get frames and an ACBF document in Tessera.
 * A page that is just one JPEG covering it (a scanned comic) keeps that JPEG as is: no quality
 * lost, nothing to compute. Other pages are rendered at the chosen resolution.
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
                val total: Int
                Loader.loadPDF(pdf).use { doc ->
                    total = doc.numberOfPages
                    val renderer = PDFRenderer(doc)
                    val digits = maxOf(3, total.toString().length)
                    ZipOutputStream(temp.outputStream().buffered(1 shl 20)).use { zip ->
                        for (i in 0 until total) {
                            coroutineContext.ensureActive()
                            val number = (i + 1).toString().padStart(digits, '0')
                            val original = if (options.keepOriginals) originalJpeg(doc, i) else null
                            if (original != null) {
                                originals++
                                zip.stored("page-$number.jpg", original)
                            } else {
                                val image = renderer.renderImageWithDPI(i, options.dpi.toFloat(), ImageType.RGB)
                                val bytes = encode(image, options.format)
                                if (options.format == PageFormat.Jpeg) zip.stored("page-$number.jpg", bytes)
                                else zip.deflated("page-$number.png", bytes)
                            }
                            progress(i + 1, total)
                        }
                    }
                }
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                PdfImportResult(total, originals)
            } finally {
                temp.delete()
            }
        }

    /**
     * The page's JPEG, when the page is nothing but one RGB or grey JPEG with the page's
     * proportions and no text; null otherwise (the page is then rendered).
     */
    internal fun originalJpeg(doc: PDDocument, index: Int): ByteArray? {
        val page = doc.getPage(index)
        if (page.rotation % 360 != 0) return null
        val resources = page.resources ?: return null
        val images = resources.xObjectNames.mapNotNull { resources.getXObject(it) as? PDImageXObject }
        val image = images.singleOrNull() ?: return null
        if (resources.xObjectNames.count() != 1) return null
        val stream = image.cosObject
        val filters = stream.filters
        val onlyDct = when (filters) {
            is COSName -> filters == COSName.DCT_DECODE
            is org.apache.pdfbox.cos.COSArray -> filters.size() == 1 && filters.getObject(0) == COSName.DCT_DECODE
            else -> false
        }
        if (!onlyDct) return null
        if (image.colorSpace !is PDDeviceRGB && image.colorSpace !is PDDeviceGray) return null
        val box = page.cropBox
        val pageRatio = box.width / box.height
        val imageRatio = image.width.toFloat() / image.height
        if (abs(pageRatio - imageRatio) / pageRatio > 0.02f) return null
        val text = PDFTextStripper().apply { startPage = index + 1; endPage = index + 1 }.getText(doc)
        if (text.isNotBlank()) return null
        return stream.createRawInputStream().use { it.readBytes() }
    }

    private fun encode(image: BufferedImage, format: PageFormat): ByteArray {
        val out = ByteArrayOutputStream()
        if (format == PageFormat.Png) {
            ImageIO.write(image, "png", out)
        } else {
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            val param = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = 0.9f }
            ImageIO.createImageOutputStream(out).use { ios ->
                writer.output = ios
                writer.write(null, IIOImage(image, null, null), param)
            }
            writer.dispose()
        }
        return out.toByteArray()
    }

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
