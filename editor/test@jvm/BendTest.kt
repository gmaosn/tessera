import tessera.editor.enhance.Argb
import tessera.editor.scan.Bend
import tessera.editor.scan.BendSide
import tessera.editor.scan.Rgb
import tessera.editor.scan.Unfold
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BendTest {
    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    /** A double page 600 × 600 of white paper, with a shadow either side of the fold at x = 300 when [shadow]. */
    private fun spread(shadow: Boolean): Rgb {
        val img = Rgb(600, 600)
        for (y in 0 until 600) for (x in 0 until 600) {
            val d = abs(x - 300)
            val v = if (shadow && d < 60) 0.95f * (0.5f + 0.5f * d / 60) else 0.95f
            for (c in 0 until 3) img.data[(y * 600 + x) * 3 + c] = v
        }
        return img
    }

    @Test
    fun flatPaperIsLeftAsItIs() {
        val bend = Unfold.estimate(spread(shadow = false), 300.0)
        assertTrue(bend.left.gain < 0.01 && bend.right.gain < 0.01, "gains ${bend.left.gain} ${bend.right.gain}")
        assertTrue(bend.left.blur.all { it < 0.01 })
    }

    @Test
    fun aShadowAtTheFoldWidensThePageAwayFromTheLampMore() {
        val bend = Unfold.estimate(spread(shadow = true), 300.0, lampOnRight = true)
        assertTrue(bend.right.gain > 1, "right gain ${bend.right.gain}")
        // The same shadow on the left page, facing away from the lamp, means a steeper climb.
        assertTrue(bend.left.gain > bend.right.gain * 1.3, "left ${bend.left.gain} right ${bend.right.gain}")
        assertTrue(bend.left.blurAt(0.0) > bend.left.blurAt(50.0))
    }

    @Test
    fun columnsLandWhereTheBendSays() {
        // Each of the 20 pixels nearest the fold holds 1.5 px of paper on the left, 1.25 on the right.
        fun side(per: Double) = BendSide(DoubleArray(100) { d -> if (d < 20) sqrt(per * per - 1) else 0.0 }, DoubleArray(101))
        val bend = Bend(200.0, side(1.5), side(1.25))
        assertEquals(10.0, bend.left.gain, 1e-6)
        assertEquals(5.0, bend.right.gain, 1e-6)
        // A page with a dark column every 10 px.
        val w = 400
        val page = Argb(w, 10, IntArray(w * 10) { i -> if ((i % w) % 10 == 5) grey(0) else grey(255) })
        val out = Unfold.unfold(page, bend)
        assertEquals(w + 15, out.width)
        for (x in listOf(105, 185, 195, 205, 215, 295)) {
            val at = bend.unfoldedX(x + 0.5) - 0.5
            val v = out.pixels[5 * out.width + Math.round(at).toInt()] and 0xFF
            assertTrue(v < 128, "column $x should land at $at, found $v")
        }
        // Far from the fold the page is only shifted: left side unchanged.
        assertEquals(bend.unfoldedX(50.5), 50.5, 1e-9)
    }

    @Test
    fun aBlurredEdgeComesBackSteepWithoutHalo() {
        val w = 120
        val h = 40
        val sigma = 2.5
        // A step from 40 to 220 at x = 60, blurred by a Gaussian of σ 2.5.
        fun blurred(x: Int): Int {
            var s = 0.0
            var n = 0.0
            for (k in -12..12) {
                val g = exp(-0.5 * k * k / (sigma * sigma))
                s += g * (if (x + k < 60) 40 else 220); n += g
            }
            return (s / n).toInt()
        }
        val page = Argb(w, h, IntArray(w * h) { grey(blurred(it % w)) })
        val flat = BendSide(DoubleArray(60), DoubleArray(61) { sigma / Unfold.SHARPNESS })
        val sharp = Unfold.sharpen(page, Bend(60.0, flat, flat))
        fun width(p: Argb): Int = (0 until w).count { x -> (p.pixels[20 * w + x] and 0xFF) in 58..202 } // 10 to 90 % of the rise
        assertTrue(width(sharp) < width(page), "edge ${width(page)} px wide before, ${width(sharp)} after")
        val values = (0 until w).map { sharp.pixels[20 * w + it] and 0xFF }
        assertTrue(values.all { it in 38..222 }, "no halo: ${values.minOrNull()}..${values.maxOrNull()}")
    }

    @Test
    fun theReaderCanUnfoldMoreOrLess() {
        val bend = Unfold.estimate(spread(shadow = true), 300.0)
        assertEquals(0.0, bend.steeper(0.0).left.gain, 1e-9)
        assertTrue(bend.steeper(1.5).left.gain > bend.left.gain * 1.5, "steeper means more than proportionally wider")
    }

    @Test
    fun theWienerKernelKeepsBrightness() {
        for (s in listOf(0.5, 1.0, 3.0)) {
            val k = Unfold.wiener(s, 0.05)
            assertEquals(1.0, k.sum(), 1e-9)
            assertTrue(k.any { it < 0 }, "a sharpening kernel for σ $s has negative taps")
        }
    }
}
