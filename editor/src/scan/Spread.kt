package tessera.editor.scan

import tessera.editor.enhance.Argb
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The fold of a scanned double page, a straight line `x = slope · y + x0` in the pixels of the
 * upright page (spine vertical). Its angle is also how askew the book lay on the glass.
 */
data class Fold(val slope: Double, val x0: Double) {
    fun x(y: Double): Double = slope * y + x0
    val degrees: Double get() = atan(slope) * 180 / PI
}

/**
 * What [Spread.analyse] found: the fold, and whether the picture goes across it ([keepWhole], a
 * double-page picture kept as one wide page) or the two pages are to be cut apart.
 * [continuity] (−1 to 1) is how well the drawing on either side of the fold matches; [blank] is
 * the share of the fold's length with an empty margin beside it, on the more blank side.
 */
data class SpreadAnalysis(val fold: Fold, val keepWhole: Boolean, val continuity: Double, val blank: Double)

/**
 * Finds the fold of a scanned double page and decides whether to cut it, without a native
 * library. Tuned on flatbed scans of a manga volume (see the private notes): the fold is not a
 * dark valley but a step in the brightness of the paper, one page lit up to the spine and the
 * other in its shadow; a picture crosses the fold when both sides match and neither has a margin.
 */
object Spread {
    /** The analysis works on a copy this many pixels on its long side. */
    const val ANALYSIS = 1000

    /** Analyses [page] after [quarterTurns] quarter turns anticlockwise; the fold is in the turned page's pixels. */
    fun analyse(page: Argb, quarterTurns: Int = 0): SpreadAnalysis {
        val small = Rgb.downscale(page, ANALYSIS).quarterTurns(quarterTurns)
        val turned = if (quarterTurns % 2 == 0) page.width else page.height
        val scale = turned.toDouble() / small.width
        val a = analyseSmall(small)
        return a.copy(fold = Fold(a.fold.slope, a.fold.x0 * scale))
    }

    internal fun analyseSmall(img: Rgb): SpreadAnalysis {
        val fold = findFold(img)
        val continuity = continuity(img, fold)
        val w = img.width
        val near = (w * 0.004).toInt()
        val far = (w * 0.015).toInt()
        val blank = max(blank(img, fold, -far, -near), blank(img, fold, near, far))
        val keep = !continuity.isNaN() && continuity - 0.3 * blank > 0.15
        return SpreadAnalysis(fold, keep, continuity, blank)
    }

    /**
     * The fold. Most often the bottom of a valley in the paper's brightness, lit paper on both
     * sides: in the middle of a book both pages sink into the spine's shadow and the fold is the
     * thin dark line at the bottom; pressed flat, one page stays lit up to the fold and the other's
     * shadow is darkest against it. Without such a valley (a black page beside the fold), the
     * strongest step in the paper's brightness.
     */
    internal fun findFold(img: Rgb): Fold = findValley(img) ?: findStep(img)

    /** The bottom of the valley: a rough line from four quarters of the height, refined in 16 strips. */
    private fun findValley(img: Rgb): Fold? {
        val w = img.width
        val h = img.height
        val lum = img.luminance()
        val lo = (w * 0.3).toInt()
        val hi = (w * 0.7).toInt()
        val whole = boxSmooth(paperProfile(lum, w, lo, hi, (h * 0.08).toInt(), (h * 0.92).toInt()), (w * 0.004).toInt())
        val i0 = argmin(whole)
        val bottom = whole[i0]
        val side = (w * 0.15).toInt()
        val leftLight = (max(0, i0 - side) until i0).maxOfOrNull { whole[it] } ?: return null
        val rightLight = (i0 + 1 until min(whole.size, i0 + side)).maxOfOrNull { whole[it] } ?: return null
        val rim = min(leftLight, rightLight)
        if (rim - bottom < 0.15) return null
        // A rough line first, from the valley's bottom in each quarter of the height: a tilted
        // fold moves across more than the valley is wide.
        val near = (w * 0.05).toInt()
        val qFrom = max(0, lo + i0 - near)
        val qUntil = min(w, lo + i0 + near + 1)
        val qy = DoubleArray(4)
        val qx = DoubleArray(4)
        for (q in 0 until 4) {
            val y0 = (h * (0.05 + 0.9 * q / 4)).toInt()
            val y1 = (h * (0.05 + 0.9 * (q + 1) / 4)).toInt()
            qy[q] = (y0 + y1) / 2.0
            qx[q] = (qFrom + argmin(boxSmooth(paperProfile(lum, w, qFrom, qUntil, y0, y1), (w * 0.004).toInt()))).toDouble()
        }
        val rough = robustLine(qy, qx)
        // Then in 16 strips, the darkest column close to it: the thin line where the pages meet.
        val pad = max(3, (w * 0.006).toInt())
        val strips = 16
        val ys = DoubleArray(strips)
        val xs = DoubleArray(strips)
        for (s in 0 until strips) {
            val y0 = (h * (0.05 + 0.9 * s / strips)).toInt()
            val y1 = (h * (0.05 + 0.9 * (s + 1) / strips)).toInt()
            ys[s] = (y0 + y1) / 2.0
            val c = rough.x(ys[s]).roundToInt()
            val from = (c - pad).coerceIn(0, w - 1)
            val until = (c + pad + 1).coerceIn(from + 1, w)
            xs[s] = (from + argmin(paperProfile(lum, w, from, until, y0, y1))).toDouble()
        }
        return robustLine(ys, xs)
    }

    /** The strongest step in the paper's brightness near the middle, then a line through 16 strips. */
    private fun findStep(img: Rgb): Fold {
        val w = img.width
        val h = img.height
        val lum = img.luminance()
        val k = (w * 0.012).toInt()
        val lo = (w * 0.36).toInt()
        val hi = (w * 0.64).toInt()
        val whole = paperProfile(lum, w, lo, hi, (h * 0.08).toInt(), (h * 0.92).toInt())
        val x0 = lo + argmax(step(whole, k))
        val reach = (w * 0.03).toInt()
        val from = max(0, x0 - reach)
        val until = min(w, x0 + reach)
        val strips = 16
        val ys = DoubleArray(strips)
        val xs = DoubleArray(strips)
        for (s in 0 until strips) {
            val y0 = (h * (0.05 + 0.9 * s / strips)).toInt()
            val y1 = (h * (0.05 + 0.9 * (s + 1) / strips)).toInt()
            ys[s] = (y0 + y1) / 2.0
            val p = paperProfile(lum, w, from, until, y0, y1)
            val j = argmax(step(p, k))
            // The medians let a few pixels of the other side in, so the step starts early: the
            // fold is where the paper's brightness drops fastest, just after.
            var edge = j.coerceIn(1, p.size - 2)
            for (i in max(1, j - 2) until min(p.size - 1, j + 2 + k)) {
                if (abs(p[i + 1] - p[i - 1]) > abs(p[edge + 1] - p[edge - 1])) edge = i
            }
            xs[s] = (from + edge).toDouble()
        }
        return robustLine(ys, xs)
    }

    /** For each column from [x0] until [x1], the 90th percentile of the brightness of rows [y0] until [y1]: the paper, not the ink. */
    private fun paperProfile(lum: FloatArray, w: Int, x0: Int, x1: Int, y0: Int, y1: Int): DoubleArray {
        val column = DoubleArray(y1 - y0)
        return DoubleArray(x1 - x0) { i ->
            for (y in y0 until y1) column[y - y0] = lum[y * w + x0 + i].toDouble()
            percentile(column, 0.9)
        }
    }

    /** How sharply the profile steps at each point: the medians [k] long either side, [g] apart from it. */
    private fun step(p: DoubleArray, k: Int, g: Int = 2): DoubleArray {
        val out = DoubleArray(p.size)
        val window = DoubleArray(k)
        fun median(from: Int): Double {
            p.copyInto(window, 0, from, from + k)
            return percentile(window, 0.5)
        }
        for (i in k + g until p.size - k - g) out[i] = abs(median(i + g) - median(i - g - k))
        return out
    }

    /** A least-squares line `x = slope · y + x0`, refitted twice without the points far from it. */
    private fun robustLine(ys: DoubleArray, xs: DoubleArray): Fold {
        var keep = BooleanArray(ys.size) { true }
        var slope = 0.0
        var x0 = 0.0
        repeat(3) {
            var n = 0.0; var sy = 0.0; var sx = 0.0; var syy = 0.0; var syx = 0.0
            for (i in ys.indices) if (keep[i]) {
                n += 1; sy += ys[i]; sx += xs[i]; syy += ys[i] * ys[i]; syx += ys[i] * xs[i]
            }
            val d = n * syy - sy * sy
            slope = if (d == 0.0) 0.0 else (n * syx - sy * sx) / d
            x0 = (sx - slope * sy) / n
            val r = DoubleArray(ys.size) { abs(slope * ys[it] + x0 - xs[it]) }
            val limit = max(3.0, percentile(r.copyOf(), 0.5) * 2.5)
            keep = BooleanArray(ys.size) { r[it] < limit }
        }
        return Fold(slope, x0)
    }

    /**
     * How well the drawing matches across the fold: the colours 1 % of the width either side,
     * band-passed down the fold (detail of 6 to 60 pixels), correlated.
     */
    internal fun continuity(img: Rgb, fold: Fold): Double {
        val o = (img.width * 0.01).toInt()
        val left = img.alongFold(fold, -o - 4, -o)
        val right = img.alongFold(fold, o, o + 4)
        return correlation(bandPass(left), bandPass(right))
    }

    /** The share of rows with no fine detail between [d0] and [d1] pixels from the fold: an empty margin, even in the spine's shadow. */
    internal fun blank(img: Rgb, fold: Fold, d0: Int, d1: Int): Double {
        val ys = img.foldRows()
        val bw = d1 - d0
        val g = Array(ys.size) { r ->
            DoubleArray(bw) { c ->
                val px = img.sample(fold.x(ys[r].toDouble()) + d0 + c, ys[r])
                (px[0] + px[1] + px[2]) / 3.0
            }
        }
        val smooth = gaussian2d(g, 3.0)
        var empty = 0
        for (r in g.indices) {
            var detail = 0.0
            for (c in 0 until bw) detail = max(detail, abs(g[r][c] - smooth[r][c]))
            if (detail < 0.04) empty++
        }
        return empty.toDouble() / g.size
    }

    private fun bandPass(channels: Array<DoubleArray>): DoubleArray {
        val out = ArrayList<Double>()
        val bp = channels.map { c -> val a = gaussian1d(c, 6.0); val b = gaussian1d(c, 60.0); DoubleArray(c.size) { a[it] - b[it] } }
        for (i in bp[0].indices) for (c in 0 until 3) out += bp[c][i]
        return out.toDoubleArray()
    }

    private fun correlation(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average()
        val mb = b.average()
        var ab = 0.0; var aa = 0.0; var bb = 0.0
        for (i in a.indices) {
            val x = a[i] - ma
            val y = b[i] - mb
            ab += x * y; aa += x * x; bb += y * y
        }
        return ab / sqrt(aa * bb)
    }

    private fun argmin(v: DoubleArray): Int {
        var best = 0
        for (i in v.indices) if (v[i] < v[best]) best = i
        return best
    }

    /** A moving average [r] to each side, shortened at the ends. */
    private fun boxSmooth(v: DoubleArray, r: Int): DoubleArray = DoubleArray(v.size) { i ->
        val a = max(0, i - r)
        val b = min(v.size - 1, i + r)
        var s = 0.0
        for (j in a..b) s += v[j]
        s / (b - a + 1)
    }

    private fun argmax(v: DoubleArray): Int {
        var best = 0
        for (i in v.indices) if (v[i] > v[best]) best = i
        return best
    }
}

/** The [q] quantile (0–1) of [v] by linear interpolation, as numpy's; [v] is reordered. */
internal fun percentile(v: DoubleArray, q: Double): Double {
    v.sort()
    val pos = q * (v.size - 1)
    val i = floor(pos).toInt()
    return if (i + 1 < v.size) v[i] + (v[i + 1] - v[i]) * (pos - i) else v[i]
}

/** A Gaussian blur of [v] with mirrored ends, kernel out to 4 σ, as scipy's `gaussian_filter1d`. */
internal fun gaussian1d(v: DoubleArray, sigma: Double): DoubleArray {
    val r = (4 * sigma + 0.5).toInt()
    val kernel = DoubleArray(2 * r + 1) { exp(-0.5 * ((it - r) / sigma).let { x -> x * x }) }
    val sum = kernel.sum()
    for (i in kernel.indices) kernel[i] /= sum
    val n = v.size
    fun at(i: Int): Double {
        var j = i
        while (j < 0 || j >= n) j = if (j < 0) -j - 1 else 2 * n - j - 1
        return v[j]
    }
    return DoubleArray(n) { i -> var s = 0.0; for (k in kernel.indices) s += kernel[k] * at(i + k - r); s }
}

private fun gaussian2d(g: Array<DoubleArray>, sigma: Double): Array<DoubleArray> {
    val rows = Array(g.size) { gaussian1d(g[it], sigma) }
    val cols = g[0].size
    val out = Array(g.size) { DoubleArray(cols) }
    for (c in 0 until cols) {
        val col = gaussian1d(DoubleArray(g.size) { rows[it][c] }, sigma)
        for (r in g.indices) out[r][c] = col[r]
    }
    return out
}

/** A small RGB image for analysis, three floats per pixel from 0 to 1. */
class Rgb(val width: Int, val height: Int, val data: FloatArray = FloatArray(width * height * 3)) {
    fun luminance(): FloatArray = FloatArray(width * height) { (data[it * 3] + data[it * 3 + 1] + data[it * 3 + 2]) / 3f }

    /** Turned [n] quarter turns anticlockwise. */
    fun quarterTurns(n: Int): Rgb {
        var img = this
        repeat(((n % 4) + 4) % 4) { img = img.anticlockwise() }
        return img
    }

    private fun anticlockwise(): Rgb {
        val out = Rgb(height, width)
        for (y in 0 until height) for (x in 0 until width) {
            val s = (y * width + x) * 3
            val d = ((width - 1 - x) * height + y) * 3
            out.data[d] = data[s]; out.data[d + 1] = data[s + 1]; out.data[d + 2] = data[s + 2]
        }
        return out
    }

    /** The pixel nearest to ([x], [y]), clamped to the image. */
    fun sample(x: Double, y: Int): FloatArray {
        val xi = x.toInt().coerceIn(0, width - 1)
        val s = (y.coerceIn(0, height - 1) * width + xi) * 3
        return floatArrayOf(data[s], data[s + 1], data[s + 2])
    }

    /** The rows looked at beside the fold: its length less 6 % at each end. */
    fun foldRows(): IntArray = ((height * 0.06).toInt() until (height * 0.94).toInt()).toList().toIntArray()

    /** Per channel, down the fold, the mean of the columns [d0] until [d1] pixels from it. */
    fun alongFold(fold: Fold, d0: Int, d1: Int): Array<DoubleArray> {
        val ys = foldRows()
        val out = Array(3) { DoubleArray(ys.size) }
        for ((i, y) in ys.withIndex()) for (d in d0 until d1) {
            val px = sample(fold.x(y.toDouble()) + d, y)
            for (c in 0 until 3) out[c][i] += px[c].toDouble() / (d1 - d0)
        }
        return out
    }

    companion object {
        /** [page] scaled down by averaging so its long side is [long] pixels (never enlarged). */
        fun downscale(page: Argb, long: Int): Rgb {
            val s = min(1.0, long.toDouble() / max(page.width, page.height))
            val w = max(1, (page.width * s).roundToInt())
            val h = max(1, (page.height * s).roundToInt())
            val out = Rgb(w, h)
            val sum = DoubleArray(w * h * 3)
            val count = IntArray(w * h)
            for (y in 0 until page.height) {
                val oy = min(h - 1, (y * h) / page.height)
                for (x in 0 until page.width) {
                    val ox = min(w - 1, (x * w) / page.width)
                    val p = page.pixels[y * page.width + x]
                    val o = oy * w + ox
                    sum[o * 3] += ((p shr 16) and 0xFF).toDouble()
                    sum[o * 3 + 1] += ((p shr 8) and 0xFF).toDouble()
                    sum[o * 3 + 2] += (p and 0xFF).toDouble()
                    count[o]++
                }
            }
            for (o in 0 until w * h) for (c in 0 until 3) out.data[o * 3 + c] = (sum[o * 3 + c] / (count[o] * 255.0)).toFloat()
            return out
        }
    }
}
