package tessera.editor.scan

import tessera.editor.enhance.Argb
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The spine's shadow on a straightened scan: for each side of the fold, how much light the paper
 * gets at each distance from it (1 is full light), in bands of [rows] rows. Measured on the
 * analysis copy; [scale] maps it to the scan's own pixels.
 */
class Shade(
    val foldX: Double,
    val reach: Int,
    val rows: Int,
    val left: Array<FloatArray>,
    val right: Array<FloatArray>,
    val scale: Double = 1.0,
) {
    fun scaled(s: Double) = Shade(foldX, reach, rows, left, right, scale * s)

    /** The light at ([x], [y]) in the scan's pixels. */
    fun light(x: Double, y: Double): Float {
        val ax = x / scale
        val ay = y / scale
        val d = ax - foldX
        val side = if (d < 0) left else right
        val dist = kotlin.math.abs(d) - 1
        if (dist >= reach - 1 || side.isEmpty()) return 1f
        val band = (ay / rows - 0.5).coerceIn(0.0, side.size - 1.0)
        val b0 = min(floor(band).toInt(), side.size - 1)
        val b1 = min(b0 + 1, side.size - 1)
        val fb = (band - b0).toFloat()
        val dd = dist.coerceAtLeast(0.0)
        val d0 = min(floor(dd).toInt(), reach - 1)
        val d1 = min(d0 + 1, reach - 1)
        val fd = (dd - d0).toFloat()
        fun at(b: Int) = side[b][d0] * (1 - fd) + side[b][d1] * fd
        return at(b0) * (1 - fb) + at(b1) * fb
    }
}

/**
 * Takes the spine's shadow off a flatbed scan. The paper's brightness (the 90th percentile of a
 * band of rows, so ink does not count) is followed from 8 % of the width away down to the fold,
 * relative to where it is out of the shadow; bands where that far paper is dark (a picture, a
 * black page) borrow the typical shadow of the others. The shadow only deepens towards the fold,
 * and varies smoothly along it. Pixels are then divided by the light, up to a gain of 3.
 */
object Shading {
    private const val ROWS = 40
    private const val MAX_GAIN = 3f

    fun estimate(img: Rgb, foldX: Double): Shade {
        val reach = (img.width * 0.08).toInt()
        val lum = img.luminance()
        return Shade(foldX, reach, ROWS, side(lum, img.width, img.height, foldX, reach, -1), side(lum, img.width, img.height, foldX, reach, 1))
    }

    private fun side(lum: FloatArray, w: Int, h: Int, foldX: Double, reach: Int, sign: Int): Array<FloatArray> {
        val xf = foldX.roundToInt()
        val xs = IntArray(reach) { (xf + sign * (it + 1)).coerceIn(0, w - 1) }
        val bands = h / ROWS
        if (bands == 0) return emptyArray()
        val ratios = Array(bands) { DoubleArray(reach) }
        val far = DoubleArray(bands)
        val column = DoubleArray(ROWS)
        for (s in 0 until bands) {
            val paper = DoubleArray(reach) { d ->
                for (r in 0 until ROWS) column[r] = lum[(s * ROWS + r) * w + xs[d]].toDouble()
                percentile(column, 0.9)
            }
            val ref = max(percentile(paper.copyOfRange(reach - 30, reach), 0.5), 1e-3)
            far[s] = ref
            for (d in 0 until reach) ratios[s][d] = paper[d] / ref
        }
        val bright = percentile(far.copyOf(), 0.9)
        val good = BooleanArray(bands) { far[it] > 0.7 * bright }
        val typical = DoubleArray(reach) { d ->
            val v = (0 until bands).filter { good[it] }.map { ratios[it][d] }.toDoubleArray()
            if (v.isEmpty()) 1.0 else percentile(v, 0.6)
        }
        for (s in 0 until bands) {
            if (!good[s]) typical.copyInto(ratios[s])
            // No more light than full, and never brighter nearer the fold.
            var m = 1.0
            for (d in reach - 1 downTo 0) {
                m = min(m, min(ratios[s][d], 1.0))
                ratios[s][d] = m
            }
        }
        // Smooth along the fold (σ 2 bands) and across it (σ 3 pixels).
        val across = Array(bands) { gaussian1d(ratios[it], 3.0) }
        val out = Array(bands) { FloatArray(reach) }
        for (d in 0 until reach) {
            val down = gaussian1d(DoubleArray(bands) { across[it][d] }, 2.0)
            for (s in 0 until bands) out[s][d] = down[s].toFloat().coerceIn(1f / MAX_GAIN, 1f)
        }
        return out
    }

    /** [page] (straightened, in the scan's pixels) with the shadow taken off. */
    fun apply(page: Argb, shade: Shade): Argb {
        val out = page.pixels.copyOf()
        val from = max(0, ((shade.foldX - shade.reach) * shade.scale).toInt())
        val until = min(page.width, ((shade.foldX + shade.reach) * shade.scale).toInt() + 1)
        for (y in 0 until page.height) for (x in from until until) {
            val light = shade.light(x + 0.5, y + 0.5)
            if (light >= 0.999f) continue
            val p = out[y * page.width + x]
            fun ch(v: Int) = (v / light).roundToInt().coerceIn(0, 255)
            out[y * page.width + x] = (p and 0xFF000000.toInt()) or (ch((p shr 16) and 0xFF) shl 16) or (ch((p shr 8) and 0xFF) shl 8) or ch(p and 0xFF)
        }
        return Argb(page.width, page.height, out)
    }
}
