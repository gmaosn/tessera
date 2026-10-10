import tessera.editor.enhance.Argb
import tessera.editor.scan.Scans
import tessera.editor.scan.Spread
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpreadTest {
    /**
     * A flatbed scan of a double page 2000 × 1400, upright: the fold at x = 1030 + 0.02·y, the
     * right page in the spine's shadow (darkening away from the fold over 120 px). With [across],
     * one picture fills both pages; otherwise each page has its own drawing within white margins.
     */
    private fun spread(across: Boolean): Argb {
        val w = 2000
        val h = 1400
        val rnd = Random(7)
        // Ink on paper where a sum of sine waves is low: fine detail down the page, long shapes across it, as a landscape.
        val waves = List(6) { Triple(rnd.nextDouble(0.002, 0.01), rnd.nextDouble(0.02, 0.08), rnd.nextDouble(0.0, 6.0)) }
        fun picture(x: Int, y: Int) = if (waves.sumOf { (a, b, c) -> sin(a * x + b * y + c) } < -0.8) 0.12 else 0.95
        val px = IntArray(w * h)
        for (y in 0 until h) {
            val fold = 1030 + 0.02 * y
            for (x in 0 until w) {
                val page = if (x < fold) 0 else 1
                val inner = abs(x - fold)
                var v = if (across) picture(x, y) else {
                    val margin = inner < 90 || x < 80 || x > w - 80 || y < 80 || y > h - 80
                    if (margin) 0.95 else picture(x + page * 777, y + page * 333)
                }
                if (page == 1 && inner < 120) v *= 0.7 + 0.3 * inner / 120
                if (inner < 2) v *= 0.5 // where the pages meet, a thin dark line
                val g = (v.coerceIn(0.0, 1.0) * 255).toInt()
                px[y * w + x] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
        }
        return Argb(w, h, px)
    }

    @Test
    fun theFoldIsFoundWithItsAngle() {
        val a = Spread.analyse(spread(across = false))
        assertTrue(abs(a.fold.x(700.0) - 1044.0) < 6, "fold at ${a.fold.x(700.0)}")
        assertTrue(abs(a.fold.slope - 0.02) < 0.006, "slope ${a.fold.slope}")
    }

    @Test
    fun pagesWithMarginsAreCut() {
        val a = Spread.analyse(spread(across = false))
        assertFalse(a.keepWhole, "$a")
    }

    @Test
    fun aPictureAcrossTheFoldIsKeptWhole() {
        val a = Spread.analyse(spread(across = true))
        assertTrue(a.keepWhole, "$a")
    }

    @Test
    fun aQuarterTurnIsReportedInTheTurnedPixels() {
        val s = spread(across = false)
        // The same spread scanned lying on its side: turned a quarter clockwise.
        val lying = IntArray(s.pixels.size)
        for (y in 0 until s.height) for (x in 0 until s.width) lying[x * s.height + (s.height - 1 - y)] = s.pixels[y * s.width + x]
        val a = Spread.analyse(Argb(s.height, s.width, lying), quarterTurns = 1)
        assertTrue(abs(a.fold.x(700.0) - 1044.0) < 6, "fold at ${a.fold.x(700.0)}")
    }

    /** The owner's scans, never in the repository: TESSERA_SCANS=<folder>[:<folder>…]; prints each decision. */
    @Test
    fun ownersScans() {
        val dirs = System.getenv("TESSERA_SCANS")?.split(':')?.map(::File) ?: return
        val kept = mutableListOf<String>()
        for (f in dirs.flatMap { d -> d.listFiles { f -> f.extension.lowercase() in setOf("png", "jpg", "jpeg", "tif", "tiff") }!!.sorted() }) {
            val img = ImageIO.read(f)
            val argb = Argb(img.width, img.height, img.getRGB(0, 0, img.width, img.height, null, 0, img.width))
            val a = Spread.analyse(argb, quarterTurns = if (img.height > img.width) 1 else 0)
            println("%s  %s  fold %.1f° at %.0f  continuity %.2f  blank %.2f".format(f.name, if (a.keepWhole) "KEEP" else "cut ", a.fold.degrees, a.fold.x(argb.width / 2.0), a.continuity, a.blank))
            if (a.keepWhole) kept += f.name
        }
        System.getenv("TESSERA_SCANS_KEEP")?.let { assertEquals(it.split(','), kept) }
    }

    /**
     * The owner's scans made into pages (right to left, as a manga): TESSERA_SCANS_OUT=<folder>
     * gets a small JPEG of each page to look at, and the plan of each scan is printed.
     */
    @Test
    fun ownersScansIntoPages() {
        val dirs = System.getenv("TESSERA_SCANS")?.split(':')?.map(::File) ?: return
        val out = System.getenv("TESSERA_SCANS_OUT")?.let(::File) ?: return
        out.mkdirs()
        val files = dirs.flatMap { d -> d.listFiles { f -> f.extension.lowercase() == "png" }!!.sorted() }
        fun read(f: File): Argb = ImageIO.read(f).let { Argb(it.width, it.height, it.getRGB(0, 0, it.width, it.height, null, 0, it.width)) }
        val plans = Scans.reconcile(files.map { f -> read(f).let { Scans.plan(it, if (it.height > it.width) 1 else 0) } })
        var n = 0
        for ((f, plan) in files.zip(plans)) {
            plan.bend?.let { b ->
                for ((n, sd) in listOf("left" to b.left, "right" to b.right)) {
                    println("  $n excess " + listOf(0, 25, 50, 100, 200, 300, 500, 800, 1000).filter { it <= sd.reach }.joinToString(" ") { "$it:%.1f".format(sd.unfolded[it] - it) } + "  blur " + listOf(0, 50, 100, 200, 400).joinToString(" ") { "$it:%.1f".format(sd.blurAt(it.toDouble())) })
                }
            }
            println("${f.name}  ${plan.copy(shade = null, bend = null)} gain ${plan.bend?.let { "%.1f / %.1f".format(it.left.gain, it.right.gain) }}")
            for (page in Scans.render(read(f), plan, rightToLeft = true)) {
                n++
                val s = 800.0 / maxOf(page.width, page.height)
                val small = BufferedImage((page.width * s).toInt(), (page.height * s).toInt(), BufferedImage.TYPE_INT_RGB)
                val full = BufferedImage(page.width, page.height, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, page.width, page.height, page.pixels, 0, page.width) }
                small.createGraphics().apply { drawImage(full.getScaledInstance(small.width, small.height, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, null); dispose() }
                ImageIO.write(small, "jpg", File(out, "%03d-%s-%dx%d.jpg".format(n, f.nameWithoutExtension, page.width, page.height)))
                if (System.getenv("TESSERA_SCANS_FULL") != null) ImageIO.write(full, "png", File(out, "%03d-%s.png".format(n, f.nameWithoutExtension)))
            }
        }
    }
}
