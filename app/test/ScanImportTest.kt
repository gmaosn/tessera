import kotlinx.coroutines.runBlocking
import tessera.acbf.ComicFiles
import tessera.app.ScanImport
import tessera.app.ScanImportOptions
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScanImportTest {
    /**
     * A double page scanned lying on its side, 900 × 1300: white paper, a dark line where the
     * pages meet with the right page's shadow beside it, a black block on the left page and one
     * of colour [mark] on the right page.
     */
    private fun scan(dir: File, name: String, mark: Color) {
        val w = 1300
        val h = 900
        val upright = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) {
            val d = x - 650
            var v = 0.95
            if (d in 0 until 60) v *= 0.7 + 0.3 * d / 60
            if (kotlin.math.abs(d) < 2) v *= 0.5
            val g = (v * 255).toInt()
            upright.setRGB(x, y, (g shl 16) or (g shl 8) or g)
        }
        upright.createGraphics().apply { color = mark; fillRect(900, 300, 200, 300); color = Color.BLACK; fillRect(150, 300, 200, 300); dispose() }
        // Lying on its side: turned a quarter clockwise, as the owner's flatbed scans are.
        val lying = BufferedImage(h, w, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) lying.setRGB(h - 1 - y, x, upright.getRGB(x, y))
        ImageIO.write(lying, "png", File(dir, name))
    }

    @Test
    fun scansBecomePagesInReadingOrder() = runBlocking {
        val dir = Files.createTempDirectory("scans").toFile()
        scan(dir, "livre-10.png", Color.BLUE)
        scan(dir, "livre-2.png", Color.RED)
        File(dir, ".DS_Store").writeText("not an image")
        val scans = ScanImport.scansIn(dir)
        assertEquals(listOf("livre-2.png", "livre-10.png"), scans.map { it.name }, "natural order")
        val target = File(dir.parentFile, dir.name + ".cbz").apply { deleteOnExit() }
        val result = ScanImport.import(scans, target, ScanImportOptions(rightToLeft = true))
        assertEquals(2, result.scans)
        assertEquals(4, result.pages)
        val names = ZipFile(target).use { z -> z.entries().toList().map { it.name } }
        assertEquals(listOf("page-001.png", "page-002.png", "page-003.png", "page-004.png"), names)
        // Right to left: the right page, with the coloured mark, comes first.
        ZipFile(target).use { z ->
            val first = ImageIO.read(z.getInputStream(z.getEntry("page-001.png")))
            val second = ImageIO.read(z.getInputStream(z.getEntry("page-002.png")))
            fun hasRed(img: BufferedImage) = (0 until img.width step 7).any { x -> (0 until img.height step 7).any { y -> (img.getRGB(x, y) shr 16 and 0xFF) > 200 && (img.getRGB(x, y) and 0xFF) < 80 } }
            assertTrue(hasRed(first), "the first page is the right one")
            assertTrue(!hasRed(second))
            assertTrue(first.height > first.width, "an upright page")
        }
        assertEquals(4, ComicFiles.open(target).document.pages.size)
    }
}
