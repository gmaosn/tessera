import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import tessera.acbf.Comic
import tessera.acbf.ComicFiles
import tessera.app.PdfImport
import tessera.app.PdfImportOptions
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfImportTest {
    private val jpeg: ByteArray = run {
        val img = BufferedImage(600, 900, BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply { color = Color.ORANGE; fillRect(0, 0, 600, 900); color = Color.BLACK; fillOval(100, 100, 400, 400); dispose() }
        ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    private val lossless = BufferedImage(500, 750, BufferedImage.TYPE_INT_RGB).also { img ->
        for (y in 0 until 750) for (x in 0 until 500) img.setRGB(x, y, (x * 255 / 500 shl 16) or (y * 255 / 750 shl 8) or 0x40)
    }

    /** Page 1: a scanned JPEG page. Page 2: a lossless image page. Page 3: text, rendered. */
    private fun samplePdf(): File {
        val file = File.createTempFile("tessera", ".pdf").apply { deleteOnExit() }
        PDDocument().use { doc ->
            doc.documentInformation.title = "Mon livre"
            doc.documentInformation.author = "Ana Lima"
            doc.documentInformation.subject = "Une histoire."
            val scan = PDPage(PDRectangle(600f, 900f))
            doc.addPage(scan)
            PDPageContentStream(doc, scan).use { it.drawImage(JPEGFactory.createFromByteArray(doc, jpeg), 0f, 0f, 600f, 900f) }
            val flat = PDPage(PDRectangle(500f, 750f))
            doc.addPage(flat)
            PDPageContentStream(doc, flat).use { it.drawImage(org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc, lossless), 0f, 0f, 500f, 750f) }
            val text = PDPage(PDRectangle.A5)
            doc.addPage(text)
            PDPageContentStream(doc, text).use {
                it.beginText(); it.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 24f); it.newLineAtOffset(50f, 400f); it.showText("Chapitre 1"); it.endText()
            }
            doc.save(file)
        }
        return file
    }

    @Test
    fun readsTheDocumentInformation() {
        val info = PdfImport.inspect(samplePdf())
        assertEquals(3, info.pages)
        assertEquals("Mon livre", info.title)
        assertEquals("Ana Lima", info.author)
    }

    @Test
    fun keepsScannedPagesAsTheyAreAndRendersTheOthers() = runBlocking {
        val target = File.createTempFile("tessera", ".cbz").apply { deleteOnExit() }
        val steps = mutableListOf<Int>()
        val result = PdfImport.import(samplePdf(), target, PdfImportOptions(dpi = 100)) { done, _ -> steps += done }
        assertEquals(3, result.pages)
        assertEquals(1, result.originals)
        assertEquals(1, result.extracted)
        assertEquals(1, result.rendered)
        assertEquals(listOf(1, 2, 3), steps)
        ZipFile(target).use { z ->
            assertEquals(listOf("page-001.jpg", "page-002.png", "page-003.png"), z.entries().toList().map { it.name })
            // The scanned page is the very JPEG that was in the PDF.
            assertContentEquals(jpeg, z.getInputStream(z.getEntry("page-001.jpg")).readBytes())
            // The lossless image keeps its exact pixels, at its own size.
            val extracted = ImageIO.read(z.getInputStream(z.getEntry("page-002.png")))
            assertEquals(lossless.width, extracted.width)
            assertEquals(lossless.getRGB(123, 456), extracted.getRGB(123, 456))
            // The text page: A5 at 100 dpi.
            val rendered = ImageIO.read(z.getInputStream(z.getEntry("page-003.png")))
            assertEquals(583, rendered.width, 2)
        }
        val comic = ComicFiles.open(target)
        assertTrue(comic.generated)
        assertEquals(3, comic.document.pages.size)
        assertEquals(3, Comic.imagePaths(comic.container.paths).size)
    }
}

private fun assertEquals(expected: Int, actual: Int, tolerance: Int) =
    assertTrue(kotlin.math.abs(expected - actual) <= tolerance, "expected $expected ± $tolerance, got $actual")
