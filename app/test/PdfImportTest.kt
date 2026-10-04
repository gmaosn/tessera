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
import tessera.app.PageFormat
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

    /** Page 1: a scanned page (one JPEG filling it). Page 2: text, so it must be rendered. */
    private fun samplePdf(): File {
        val file = File.createTempFile("tessera", ".pdf").apply { deleteOnExit() }
        PDDocument().use { doc ->
            doc.documentInformation.title = "Mon livre"
            doc.documentInformation.author = "Ana Lima"
            doc.documentInformation.subject = "Une histoire."
            val scan = PDPage(PDRectangle(600f, 900f))
            doc.addPage(scan)
            PDPageContentStream(doc, scan).use { it.drawImage(JPEGFactory.createFromByteArray(doc, jpeg), 0f, 0f, 600f, 900f) }
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
        assertEquals(2, info.pages)
        assertEquals("Mon livre", info.title)
        assertEquals("Ana Lima", info.author)
    }

    @Test
    fun keepsScannedPagesAsTheyAreAndRendersTheOthers() = runBlocking {
        val target = File.createTempFile("tessera", ".cbz").apply { deleteOnExit() }
        val steps = mutableListOf<Int>()
        val result = PdfImport.import(samplePdf(), target, PdfImportOptions(dpi = 100)) { done, _ -> steps += done }
        assertEquals(2, result.pages)
        assertEquals(1, result.originals)
        assertEquals(listOf(1, 2), steps)
        ZipFile(target).use { z ->
            assertEquals(listOf("page-001.jpg", "page-002.jpg"), z.entries().toList().map { it.name })
            // The scanned page is the very JPEG that was in the PDF.
            assertContentEquals(jpeg, z.getInputStream(z.getEntry("page-001.jpg")).readBytes())
            val rendered = ImageIO.read(z.getInputStream(z.getEntry("page-002.jpg")))
            // A5 at 100 dpi.
            assertEquals(583, rendered.width, 2)
        }
        val comic = ComicFiles.open(target)
        assertTrue(comic.generated)
        assertEquals(2, comic.document.pages.size)
    }

    @Test
    fun rendersEverythingInPngWhenAsked() = runBlocking {
        val target = File.createTempFile("tessera", ".cbz").apply { deleteOnExit() }
        val result = PdfImport.import(samplePdf(), target, PdfImportOptions(dpi = 72, format = PageFormat.Png, keepOriginals = false))
        assertEquals(0, result.originals)
        ZipFile(target).use { z -> assertEquals(listOf("page-001.png", "page-002.png"), z.entries().toList().map { it.name }) }
        assertEquals(2, Comic.imagePaths(ComicFiles.open(target).container.paths).size)
    }
}

private fun assertEquals(expected: Int, actual: Int, tolerance: Int) =
    assertTrue(kotlin.math.abs(expected - actual) <= tolerance, "expected $expected ± $tolerance, got $actual")
