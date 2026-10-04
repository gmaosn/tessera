import kotlinx.coroutines.runBlocking
import tessera.editor.enhance.Argb
import tessera.editor.enhance.EnhanceMode
import tessera.editor.enhance.Enhancement
import tessera.editor.enhance.Enhancer
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

class EnhancerTest {
    private val root = run {
        var dir = File("").absoluteFile
        while (!File(dir, "project.yaml").exists()) dir = dir.parentFile
        dir
    }

    @Test
    fun aFlatImageStaysFlat() = runBlocking {
        val grey = Argb(40, 30, IntArray(40 * 30) { 0xFF808080.toInt() })
        for (mode in listOf(EnhanceMode.Sharpen, EnhanceMode.Restore)) {
            val out = Enhancer.enhance(grey, Enhancement(mode, 0.6f, 1f))
            val expected = if (mode == EnhanceMode.Restore) 80 * 60 else 40 * 30
            assertEquals(expected, out.pixels.size)
            // A network trained on line art adds next to nothing to a uniform grey.
            assertTrue(out.pixels.all { abs(((it shr 8) and 0xFF) - 0x80) <= 6 }, "$mode changed a flat image")
        }
    }

    /** Before/after crops of a real page into build/screens, and the time it takes. */
    @Test
    fun enhancesARealPage() = runBlocking {
        val page = File(root, "ref/ACBF/Sample Comic Book/Doctorow, Cory - Craphound-1.1/page1.jpg")
        if (!page.exists()) return@runBlocking
        val img = ImageIO.read(page)
        val argb = Argb(img.width, img.height, img.getRGB(0, 0, img.width, img.height, null, 0, img.width))
        lateinit var out: Argb
        val time = measureTime { out = Enhancer.enhance(argb, Enhancement(EnhanceMode.Restore, 0.4f, 1f)) }
        println("Restore ×2 of ${img.width}×${img.height}: $time")
        assertEquals(img.width * 2, out.width)
        val dir = File(root, "build/screens").apply { mkdirs() }
        // The same region, 3× from the original (nearest neighbour) and 1.5× from the result.
        fun crop(a: Argb, x: Int, y: Int, w: Int, h: Int, zoom: Int): java.awt.image.BufferedImage {
            val b = java.awt.image.BufferedImage(w * zoom, h * zoom, java.awt.image.BufferedImage.TYPE_INT_RGB)
            for (yy in 0 until h * zoom) for (xx in 0 until w * zoom) b.setRGB(xx, yy, a.pixels[(y + yy / zoom) * a.width + x + xx / zoom])
            return b
        }
        ImageIO.write(crop(argb, 560, 470, 200, 160, 4), "png", dir.resolve("enhance-before.png"))
        ImageIO.write(crop(out, 1120, 940, 400, 320, 2), "png", dir.resolve("enhance-after.png"))
        val sharp = Enhancer.sharpen(argb, 0.6f)
        ImageIO.write(crop(sharp, 560, 470, 200, 160, 4), "png", dir.resolve("enhance-sharpen.png"))
    }
}
