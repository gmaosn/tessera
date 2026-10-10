package tessera.editor.scan

import tessera.editor.enhance.Argb
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * How the paper bends into the spine on one side of the fold, from the scan's own pixels: at
 * each distance `d` from the fold as seen on the glass (0 to [reach]), the paper's [slope] there
 * and its defocus [blur] (Gaussian σ, in the scan's pixels). [unfolded] follows from the slope:
 * the length of paper from the fold out to each distance.
 */
class BendSide(val slope: DoubleArray, val blur: DoubleArray) {
    val reach: Int get() = slope.size

    val unfolded: DoubleArray = DoubleArray(slope.size + 1).also { u ->
        for (d in 1..slope.size) u[d] = u[d - 1] + sqrt(1 + slope[d - 1] * slope[d - 1])
    }

    /** How much wider the side gets once unfolded. */
    val gain: Double get() = unfolded[reach] - reach

    /** The distance on the glass where the unfolded length is [t]. */
    fun seenAt(t: Double): Double {
        if (t >= unfolded[reach]) return reach + (t - unfolded[reach])
        var lo = 0
        var hi = reach
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (unfolded[mid] <= t) lo = mid else hi = mid
        }
        val span = unfolded[hi] - unfolded[lo]
        return lo + if (span > 0) (t - unfolded[lo]) / span else 0.0
    }

    fun blurAt(d: Double): Double {
        if (d >= blur.size - 1) return 0.0
        val i = floor(d).toInt().coerceIn(0, blur.size - 2)
        val f = d - i
        return blur[i] * (1 - f) + blur[i + 1] * f
    }

    /** The same bend [strength] times as steep: 0 flat, 1 as measured. */
    fun steeper(strength: Double) = BendSide(DoubleArray(slope.size) { slope[it] * strength }, blur)

    /** In pixels [s] times smaller (s > 1: the scan's own from the analysis copy's). */
    fun scaled(s: Double): BendSide {
        val n = (reach * s).roundToInt()
        fun at(v: DoubleArray, x: Double): Double {
            val i = floor(x).toInt().coerceIn(0, v.size - 2)
            val f = (x - i).coerceIn(0.0, 1.0)
            return v[i] * (1 - f) + v[i + 1] * f
        }
        return BendSide(DoubleArray(n) { at(slope, (it + 0.5) / s - 0.5) }, DoubleArray(n + 1) { at(blur, it / s) * s })
    }
}

/**
 * The reader's settings for the paper near the spine, as fractions of Tessera's own (1): how
 * much it is unfolded, how much it is brought back into focus. 0 leaves it as scanned.
 */
data class ScanFinish(val unfold: Double = 1.0, val sharpen: Double = 1.0)

/** The paper's bend on both sides of the fold, which stands at [foldX] on the straightened scan. */
class Bend(val foldX: Double, val left: BendSide, val right: BendSide) {
    fun scaled(s: Double) = Bend(foldX * s, left.scaled(s), right.scaled(s))

    /** The same bend [strength] times as steep (the reader's setting; 1 is as measured). */
    fun steeper(strength: Double) = if (strength == 1.0) this else Bend(foldX, left.steeper(strength), right.steeper(strength))

    /** Where a column of the straightened scan lands once unfolded. */
    fun unfoldedX(x: Double): Double {
        val fold = foldX + left.gain
        val d = x - foldX
        return if (d < 0) fold - (if (-d >= left.reach) -d + left.gain else interp(left.unfolded, -d))
        else fold + (if (d >= right.reach) d + right.gain else interp(right.unfolded, d))
    }

    private fun interp(v: DoubleArray, x: Double): Double {
        val i = floor(x).toInt().coerceIn(0, v.size - 2)
        val f = x - i
        return v[i] * (1 - f) + v[i + 1] * f
    }
}

/**
 * Unfolds the paper near the spine of a flatbed scan. On the glass the paper is flat; near the
 * spine it lifts away, so the scan sees it foreshortened, darker and out of focus. The darker it
 * is, the steeper it climbs: its brightness (read in the bands of rows where only paper shows,
 * so neither ink nor a black page passes for shadow) gives its slope, and the slope the length
 * of paper the scan squeezed into each pixel. The scanner's lamp lights from one side, so the same shadow means a
 * steeper climb on the page facing away from it. Calibrated on pressed and loose scans of the
 * same double pages (the paper's movement between them matches to 0.62 px, median, at 2000 px).
 * Defocus grows with the shadow too, and is undone by a Wiener deconvolution with a clamp
 * against halos.
 */
object Unfold {
    /** Slope = [SLOPE] · max(0, 1 − light − [DARK])^[POWER], [AWAY] times more on the page away from the lamp. */
    private const val SLOPE = 0.76
    private const val POWER = 0.66
    private const val DARK = 0.056
    private const val AWAY = 1.51

    /** Defocus σ² = [BLUR] · (1 − light)^[BLUR_POWER], in pixels of an image 4000 px wide. */
    private const val BLUR = 45.5
    private const val BLUR_POWER = 1.55
    /**
     * How much of the defocus Tessera undoes by default: half, with a Wiener noise of 0.1. More
     * did not bring the loose scans closer to the pressed ones, only noise and screen patterns.
     */
    const val SHARPNESS = 0.5
    private const val WIENER_NOISE = 0.1

    /** How far from the fold the bend is followed: a fifth of the width of the double page. */
    private const val REACH = 0.2

    /**
     * The bend on a straightened analysis copy [img] (a double page, fold at [foldX]); the page
     * on the left faces away from the lamp when [lampOnRight].
     */
    fun estimate(img: Rgb, foldX: Double, lampOnRight: Boolean = true): Bend {
        val reach = (img.width * REACH).toInt()
        val lum = img.luminance()
        fun side(sign: Int): BendSide {
            val light = lightProfile(lum, img.width, img.height, foldX, reach, sign)
            val away = if ((sign < 0) == lampOnRight) AWAY else 1.0
            val slope = DoubleArray(reach) { d -> SLOPE * away * max(0.0, 1 - light[d] - DARK).pow(POWER) }
            val toAnalysis = img.width / 4000.0
            val blur = DoubleArray(reach + 1) { d ->
                val l = light[min(d, reach - 1)]
                sqrt(BLUR * max(0.0, 1 - l).pow(BLUR_POWER)) * toAnalysis
            }
            return BendSide(slope, blur)
        }
        return Bend(foldX, side(-1), side(1))
    }

    /**
     * The paper's light at each distance from the fold, relative to the page's paper further
     * out, read only where nothing but paper shows. Per band of 40 rows the 90th percentile of
     * each column (paper, not ink), thin lines such as frame borders taken out by a median over 9
     * columns; a band counts when, beyond where a shadow reaches, it is nearly as light as the
     * page's whitest paper all along, and changes smoothly on the way to the fold (a picture
     * changes suddenly, a tint or a black page is darker). Their median against that white, never brighter nearer the fold,
     * smoothed. Fewer than 3 such bands (a page in colour to its edge): the paper is taken as flat.
     */
    internal fun lightProfile(lum: FloatArray, w: Int, h: Int, foldX: Double, reach: Int, sign: Int): DoubleArray {
        val rows = 40
        val flat = DoubleArray(reach) { 1.0 }
        val xf = foldX.roundToInt()
        val xs = IntArray(reach) { (xf + sign * it).coerceIn(0, w - 1) }
        val bands = (100 until h - 100 - rows step rows).toList()
        if (bands.isEmpty() || reach < 20) return flat
        val col = DoubleArray(rows)
        val bandsByD = bands.map { y ->
            median9(DoubleArray(reach) { d ->
                for (r in 0 until rows) col[r] = lum[(y + r) * w + xs[d]].toDouble()
                percentile(col, 0.9)
            })
        }
        // The page's whitest paper: what the light is measured against.
        val white = percentile(DoubleArray(bandsByD.size * reach) { bandsByD[it / reach][it % reach] }, 0.95)
        if (white <= 0.5) return flat
        val clean = bandsByD.filter { band ->
            var jump = 0.0
            for (d in 15 until reach - 2) jump = max(jump, abs(band[d + 2] - band[d]))
            // Beyond the shadow (it never reached 0.13 of the width on the calibration scans) the band is paper.
            val beyond = (reach * 0.65).toInt()
            (beyond until reach).all { band[it] > 0.85 * white } && jump < 0.15
        }
        if (clean.size < 3) return flat
        val across = DoubleArray(clean.size)
        val profile = median9(DoubleArray(reach) { d ->
            for (i in clean.indices) across[i] = clean[i][d]
            min(1.0, percentile(across, 0.5) / white)
        })
        var m = 1.0
        for (d in reach - 1 downTo 0) {
            m = min(m, profile[d])
            profile[d] = m
        }
        return gaussian1d(profile, 2.0)
    }

    private fun median9(v: DoubleArray): DoubleArray {
        val window = DoubleArray(9)
        return DoubleArray(v.size) { d ->
            for (j in 0 until 9) window[j] = v[(d + j - 4).coerceIn(0, v.size - 1)]
            percentile(window, 0.5)
        }
    }

    /** [page] (straightened, the fold upright at [bend]'s foldX, in its pixels) brought back into focus, then unfolded. */
    fun apply(page: Argb, bend: Bend, finish: ScanFinish = ScanFinish()): Argb =
        bend.steeper(finish.unfold).let { b -> unfold(if (finish.sharpen > 0) sharpen(page, b, finish.sharpen) else page, b) }

    /** Widens the page around the fold so each column of paper gets its true width back. */
    fun unfold(page: Argb, bend: Bend): Argb {
        val f = bend.foldX
        val outW = page.width + ceil(bend.left.gain + bend.right.gain).toInt()
        val fOut = f + bend.left.gain
        val src = DoubleArray(outW) { xo ->
            val dt = xo + 0.5 - fOut
            val s = if (dt < 0) f - bend.left.seenAt(-dt) else f + bend.right.seenAt(dt)
            s - 0.5
        }
        val out = IntArray(outW * page.height)
        val w = DoubleArray(4)
        for (xo in 0 until outW) {
            val x = src[xo]
            val x0 = floor(x).toInt()
            keys(x - x0, w)
            for (y in 0 until page.height) {
                val row = y * page.width
                var a = 0.0; var r = 0.0; var g = 0.0; var b = 0.0
                for (i in 0 until 4) {
                    val p = page.pixels[row + (x0 - 1 + i).coerceIn(0, page.width - 1)]
                    a += w[i] * ((p ushr 24) and 0xFF); r += w[i] * ((p shr 16) and 0xFF); g += w[i] * ((p shr 8) and 0xFF); b += w[i] * (p and 0xFF)
                }
                out[y * outW + xo] = (byte(a) shl 24) or (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
            }
        }
        return Argb(outW, page.height, out)
    }

    /**
     * Undoes the defocus near the fold: per column, a Wiener deconvolution of its σ, across then
     * down, each pass held between the darkest and lightest pixels about 2σ around (along that
     * pass), so no halo appears.
     */
    fun sharpen(page: Argb, bend: Bend, strength: Double = 1.0): Argb {
        val f = bend.foldX
        val from = max(0, floor(f - bend.left.reach).toInt())
        val until = min(page.width, ceil(f + bend.right.reach).toInt() + 1)
        val sigma = DoubleArray(until - from) { i ->
            val d = from + i + 0.5 - f
            (if (d < 0) bend.left.blurAt(-d) else bend.right.blurAt(d)) * SHARPNESS * strength
        }
        if (sigma.all { it < 0.3 }) return page
        val kernels = HashMap<Int, DoubleArray>()
        fun kernel(s: Double): DoubleArray? {
            val q = (s * 4).roundToInt()
            if (q < 1) return null
            return kernels.getOrPut(q) { wiener(q / 4.0, WIENER_NOISE) }
        }
        val width = page.width
        val h = page.height
        val out = page.pixels.copyOf()
        for (c in 0 until 3) {
            val shift = 16 - 8 * c
            val ch = FloatArray(width * h) { ((page.pixels[it] shr shift) and 0xFF).toFloat() }
            val across = ch.copyOf()
            for (i in sigma.indices) {
                val k = kernel(sigma[i]) ?: continue
                val x = from + i
                val r = k.size / 2
                val hold = halo(sigma[i])
                for (y in 0 until h) {
                    var s = 0.0
                    var lo = 255f
                    var hi = 0f
                    for (j in k.indices) {
                        val v = ch[y * width + (x + j - r).coerceIn(0, width - 1)]
                        s += k[j] * v
                        if (abs(j - r) <= hold) { if (v < lo) lo = v; if (v > hi) hi = v }
                    }
                    across[y * width + x] = s.toFloat().coerceIn(lo, hi)
                }
            }
            for (i in sigma.indices) {
                val k = kernel(sigma[i]) ?: continue
                val x = from + i
                val r = k.size / 2
                val hold = halo(sigma[i])
                for (y in 0 until h) {
                    var s = 0.0
                    var lo = 255f
                    var hi = 0f
                    for (j in k.indices) {
                        val v = across[(y + j - r).coerceIn(0, h - 1) * width + x]
                        s += k[j] * v
                        if (abs(j - r) <= hold) { if (v < lo) lo = v; if (v > hi) hi = v }
                    }
                    val v = s.toFloat().coerceIn(lo, hi).roundToInt().coerceIn(0, 255)
                    val o = y * width + x
                    out[o] = (out[o] and (0xFF shl shift).inv()) or (v shl shift)
                }
            }
        }
        return Argb(width, h, out)
    }

    /** How far the halo clamp looks: about as far as the blur spreads. */
    private fun halo(sigma: Double) = ceil(2 * sigma).toInt() + 1

    /** The Wiener inverse of a Gaussian blur of [sigma], noise to signal [noise], as a kernel summing to 1. */
    internal fun wiener(sigma: Double, noise: Double): DoubleArray {
        val n = 256
        val half = (ceil(4 * sigma).toInt() + 6).coerceAtMost(n / 2 - 1)
        val response = DoubleArray(n / 2 + 1) { k ->
            val g = exp(-2 * (PI * sigma * k / n).let { it * it })
            g / (g * g + noise)
        }
        // The kernel's taps: an inverse real DFT of an even response, kept to ±half.
        val k = DoubleArray(2 * half + 1) { i ->
            val t = i - half
            var s = response[0]
            for (j in 1 until n / 2) s += 2 * response[j] * cos(2 * PI * j * t / n)
            s += response[n / 2] * cos(PI * t)
            s / n
        }
        val sum = k.sum()
        for (i in k.indices) k[i] /= sum
        return k
    }

    private fun byte(d: Double) = d.roundToInt().coerceIn(0, 255)

    private fun keys(t: Double, w: DoubleArray) {
        fun k(d: Double): Double {
            val x = abs(d)
            return when {
                x <= 1 -> 1.5 * x * x * x - 2.5 * x * x + 1
                x < 2 -> -0.5 * x * x * x + 2.5 * x * x - 4 * x + 2
                else -> 0.0
            }
        }
        for (i in 0 until 4) w[i] = k(t - (i - 1))
    }
}
