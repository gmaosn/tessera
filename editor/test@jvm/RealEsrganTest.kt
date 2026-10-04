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

    @Test
    fun timesARealPage() = runBlocking {
        val page = File(root, "ref/ACBF/Sample Comic Book/Doctorow, Cory - Craphound-1.1/page1.jpg")
        if (!page.exists() || System.getenv("TESSERA_BENCH") == null) return@runBlocking
        val a = argb(page)
        val t = measureTime { RealEsrgan.upscale2x(a) }
        println("Real-ESRGAN ×2 of ${a.width}×${a.height}: $t")
    }
}
