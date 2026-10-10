import tessera.editor.enhance.Argb
import tessera.editor.scan.Fold
import tessera.editor.scan.Paper
import tessera.editor.scan.Rgb
import tessera.editor.scan.Spread
import tessera.editor.scan.Unfold
import tessera.editor.scan.straightened
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

class BendProbe {
    @Test
    fun probe() {
        val f = System.getenv("TESSERA_PROBE")?.let(::File) ?: return
        val img = ImageIO.read(f)
        val page = Argb(img.width, img.height, img.getRGB(0, 0, img.width, img.height, null, 0, img.width))
        val q = if (page.height > page.width) 1 else 0
        val spread = Spread.analyse(page, q)
        val small = Rgb.downscale(page, Paper.ANALYSIS).quarterTurns(q)
        val s = (if (q % 2 == 0) page.width else page.height).toDouble() / small.width
        val sf = Fold(spread.fold.slope, spread.fold.x0 / s)
        val straight = small.straightened(sf)
        val fx = sf.x(straight.height / 2.0)
        println("analysis ${straight.width}x${straight.height} fold $fx")
        for (sign in listOf(-1, 1)) {
            val p = Unfold.lightProfile(straight.luminance(), straight.width, straight.height, fx, 400, sign)
            println("side $sign " + listOf(0, 5, 10, 20, 30, 40, 60, 80, 100, 150, 200, 300, 399).joinToString(" ") { "$it:%.2f".format(p[it]) })
        }
    }
}
