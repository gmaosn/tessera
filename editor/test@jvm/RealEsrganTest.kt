import kotlinx.coroutines.runBlocking
import tessera.editor.enhance.Argb
import tessera.editor.enhance.RealEsrgan
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

/** The Kotlin engine against PyTorch running the official model (fixtures/sr). */
class RealEsrganTest {
    private val root = run {
        var dir = File("").absoluteFile
        while (!File(dir, "project.yaml").exists()) dir = dir.parentFile
        dir
    }

    private fun argb(f: File) = ImageIO.read(f).let { Argb(it.width, it.height, it.getRGB(0, 0, it.width, it.height, null, 0, it.width)) }

    @Test
    fun matchesTheReferenceImplementation() = runBlocking {
        assertTrue(RealEsrgan.available, "weights not found on the class path")
        val input = argb(File(root, "fixtures/sr/input.png"))
        val expected = argb(File(root, "fixtures/sr/realesrgan-x4.png"))
        val actual = RealEsrgan.upscale4x(input)
        assertEquals(expected.width, actual.width)
        var worst = 0
        var total = 0L
        for (i in expected.pixels.indices) for (shift in intArrayOf(16, 8, 0)) {
            val d = abs(((expected.pixels[i] shr shift) and 0xFF) - ((actual.pixels[i] shr shift) and 0xFF))
            worst = maxOf(worst, d); total += d
        }
        println("Real-ESRGAN vs PyTorch: worst difference $worst, mean ${total.toDouble() / (expected.pixels.size * 3)}")
        // Float rounding only: at most one or two levels out of 255.
        assertTrue(worst <= 2, "worst difference $worst")
    }

    /** A computation that gave way takes up from the tiles done, and ends as one never stopped. */
    @Test
    fun resumesWhereItGaveWay() = runBlocking {
        val input = argb(File(root, "fixtures/sr/input.png"))
        // The fixture repeated over 300 × 300 pixels: nine tiles.
        val page = Argb(300, 300, IntArray(300 * 300) { i ->
            val x = i % 300; val y = i / 300
            input.pixels[(y % input.height) * input.width + x % input.width]
        })
        val whole = RealEsrgan.upscale2x(page)
        val partial = RealEsrgan.partial2x(page)
        val asked = java.util.concurrent.atomic.AtomicInteger()
        val gaveWay = runCatching { RealEsrgan.upscale2x(page, shouldYield = { asked.incrementAndGet() > 2 }, partial = partial) }
        assertTrue(gaveWay.exceptionOrNull() is RealEsrgan.Yielded, "should have given way")
        asked.set(0)
        val resumed = RealEsrgan.upscale2x(page, shouldYield = { asked.incrementAndGet(); false }, partial = partial)
        assertTrue(whole.pixels.contentEquals(resumed.pixels), "resumed result differs")
        val tiles = ((page.width + 127) / 128) * ((page.height + 127) / 128)
        assertTrue(asked.get() < tiles, "tiles done before were computed again (${asked.get()} of $tiles)")
    }

    @Test
    fun timesARealPage() = runBlocking {
        val page = File(root, "ref/ACBF/Sample Comic Book/Doctorow, Cory - Craphound-1.1/page1.jpg")
        if (!page.exists() || System.getenv("TESSERA_BENCH") == null) return@runBlocking
        val a = argb(page)
        val t = measureTime { RealEsrgan.upscale2x(a) }
        println("Real-ESRGAN ×2 of ${a.width}×${a.height}: $t")
    }
}
