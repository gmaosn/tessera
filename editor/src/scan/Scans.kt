package tessera.editor.scan

import tessera.editor.enhance.Argb
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * What to make of one scan: turned [quarterTurns] anticlockwise, straightened so its [fold] is
 * upright, cut at the fold unless [keepWhole], trimmed to [bounds]. The fold is in the turned
 * scan's pixels, the bounds in the straightened one's (the fold then stands at [foldX]).
 */
data class ScanPlan(
    val quarterTurns: Int,
    val width: Int,
    val height: Int,
    val fold: Fold,
    val keepWhole: Boolean,
    val bounds: Bounds,
    val continuity: Double,
    val blank: Double,
    val shade: Shade? = null,
    val bend: Bend? = null,
) {
    val foldX: Double get() = fold.x(height / 2.0)
}

/**
 * Turns flatbed scans of an open book into pages: straightened, the spine's shadow taken off, the
 * paper brought back into focus and unfolded near the spine, cut at the fold or kept whole,
 * trimmed to the paper.
 */
object Scans {
    /** Analyses one scan; [page] as read from the file. */
    fun plan(page: Argb, quarterTurns: Int): ScanPlan {
        val spread = Spread.analyse(page, quarterTurns)
        val turnedW = if (quarterTurns % 2 == 0) page.width else page.height
        val turnedH = if (quarterTurns % 2 == 0) page.height else page.width
        val small = Rgb.downscale(page, Paper.ANALYSIS).quarterTurns(quarterTurns)
        val s = turnedW.toDouble() / small.width
        val smallFold = Fold(spread.fold.slope, spread.fold.x0 / s)
        val straight = small.straightened(smallFold)
        val bounds = Paper.bounds(straight).scaled(s)
        val foldX = smallFold.x(straight.height / 2.0)
        val shade = Shading.estimate(straight, foldX).scaled(s)
        val bend = Unfold.estimate(straight, foldX).scaled(s)
        return ScanPlan(quarterTurns, turnedW, turnedH, spread.fold, spread.keepWhole, bounds, spread.continuity, spread.blank, shade, bend)
    }

    /**
     * The pages of a book are all the same size: a page height or width far from the others' is
     * a frame or a rule taken for the paper's edge, so it gives way to the book's, measured from
     * the edge that agrees; a missing edge is placed the same way when the opposite one was found.
     */
    fun reconcile(plans: List<ScanPlan>): List<ScanPlan> {
        val heights = plans.mapNotNull { p -> p.bounds.top?.let { t -> p.bounds.bottom?.let { it - t } } }
        val widths = plans.flatMap { p ->
            listOfNotNull(p.bounds.left?.let { p.foldX - it }, p.bounds.right?.let { it - p.foldX })
        }
        if (heights.isEmpty() && widths.isEmpty()) return plans
        val height = heights.median()
        val width = widths.median()
        val tops = plans.mapNotNull { it.bounds.top }.median()
        val bottoms = plans.mapNotNull { it.bounds.bottom }.median()
        return plans.map { p ->
            var (left, top, right, bottom) = p.bounds
            if (height != null) {
                val t = top
                val b = bottom
                if (t != null && b != null && abs(b - t - height) > 0.015 * height) {
                    // Keep the edge that sits where the others' do.
                    if (abs(t - tops!!) <= abs(b - bottoms!!)) bottom = t + height else top = b - height
                } else if (t != null && b == null) bottom = t + height
                else if (b != null && t == null) top = b - height
            }
            if (width != null) {
                val l = left?.let { p.foldX - it }
                val r = right?.let { it - p.foldX }
                if (l != null && abs(l - width) > 0.02 * width) left = null
                if (r != null && abs(r - width) > 0.02 * width) right = null
                val anyFound = left != null || right != null || p.bounds.left != null || p.bounds.right != null
                if (anyFound) {
                    if (left == null) left = p.foldX - width
                    if (right == null) right = p.foldX + width
                }
            }
            fun clampX(v: Double?) = v?.coerceIn(0.0, p.width.toDouble())
            fun clampY(v: Double?) = v?.coerceIn(0.0, p.height.toDouble())
            p.copy(bounds = Bounds(clampX(left), clampY(top), clampX(right), clampY(bottom)))
        }
    }

    /** The pages of one scan in reading order: right page first when [rightToLeft]. */
    fun render(page: Argb, plan: ScanPlan, rightToLeft: Boolean, finish: ScanFinish = ScanFinish()): List<Argb> {
        val straight = page.quarterTurns(plan.quarterTurns).straightened(plan.fold).let { p -> plan.shade?.let { Shading.apply(p, it) } ?: p }
        val bend = plan.bend?.steeper(finish.unfold)
        val flat = if (bend != null) Unfold.apply(straight, bend, finish.copy(unfold = 1.0)) else straight
        fun x(v: Double) = bend?.unfoldedX(v) ?: v
        val b = plan.bounds
        val left = x(b.left ?: 0.0).roundToInt().coerceIn(0, flat.width - 1)
        val right = (if (b.right != null) x(b.right) else flat.width.toDouble()).roundToInt().coerceIn(left + 1, flat.width)
        val top = (b.top ?: 0.0).roundToInt()
        val bottom = (b.bottom ?: flat.height.toDouble()).roundToInt()
        val fold = x(plan.foldX).roundToInt().coerceIn(left + 1, right - 1)
        if (plan.keepWhole) return listOf(flat.cut(left, top, right, bottom))
        val pages = listOf(flat.cut(left, top, fold, bottom), flat.cut(fold, top, right, bottom))
        return if (rightToLeft) pages.reversed() else pages
    }
}

private fun List<Double>.median(): Double? = if (isEmpty()) null else sorted().let { s ->
    if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
}

/** The rotation that makes [fold] upright, about its middle: where each output pixel is read from. */
private class Straighten(fold: Fold, height: Int) {
    val cy = height / 2.0
    val cx = fold.x(cy)
    private val angle = atan(fold.slope)
    private val c = cos(angle)
    private val s = sin(angle)
    fun sourceX(u: Double, v: Double) = cx + (u - cx) * c + (v - cy) * s
    fun sourceY(u: Double, v: Double) = cy - (u - cx) * s + (v - cy) * c
}

/** Straightened so [fold] stands upright, bilinear (analysis only). */
fun Rgb.straightened(fold: Fold): Rgb {
    val r = Straighten(fold, height)
    val out = Rgb(width, height)
    for (v in 0 until height) for (u in 0 until width) {
        val x = r.sourceX(u.toDouble(), v.toDouble()).coerceIn(0.0, width - 1.0)
        val y = r.sourceY(u.toDouble(), v.toDouble()).coerceIn(0.0, height - 1.0)
        val x0 = min(floor(x).toInt(), width - 2).coerceAtLeast(0)
        val y0 = min(floor(y).toInt(), height - 2).coerceAtLeast(0)
        val fx = (x - x0).toFloat()
        val fy = (y - y0).toFloat()
        for (ch in 0 until 3) {
            fun at(xx: Int, yy: Int) = data[(yy * width + xx) * 3 + ch]
            val top = at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx
            val bottom = at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx
            out.data[(v * width + u) * 3 + ch] = top * (1 - fy) + bottom * fy
        }
    }
    return out
}

/** Turned [n] quarter turns anticlockwise. */
fun Argb.quarterTurns(n: Int): Argb = when (((n % 4) + 4) % 4) {
    0 -> this
    1 -> Argb(height, width, IntArray(pixels.size)).also { o ->
        for (y in 0 until height) for (x in 0 until width) o.pixels[(width - 1 - x) * height + y] = pixels[y * width + x]
    }
    2 -> Argb(width, height, IntArray(pixels.size) { pixels[pixels.size - 1 - it] })
    else -> Argb(height, width, IntArray(pixels.size)).also { o ->
        for (y in 0 until height) for (x in 0 until width) o.pixels[x * height + (height - 1 - y)] = pixels[y * width + x]
    }
}

/** Straightened so [fold] stands upright, bicubic; a fold already upright leaves the pixels as they are. */
fun Argb.straightened(fold: Fold): Argb {
    if (fold.slope == 0.0) return this
    val r = Straighten(fold, height)
    val out = IntArray(width * height)
    val wx = DoubleArray(4)
    val wy = DoubleArray(4)
    for (v in 0 until height) for (u in 0 until width) {
        val x = r.sourceX(u.toDouble(), v.toDouble())
        val y = r.sourceY(u.toDouble(), v.toDouble())
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        cubicWeights(x - x0, wx)
        cubicWeights(y - y0, wy)
        var a = 0.0; var rr = 0.0; var g = 0.0; var b = 0.0
        for (j in 0 until 4) {
            val yy = (y0 - 1 + j).coerceIn(0, height - 1) * width
            for (i in 0 until 4) {
                val p = pixels[yy + (x0 - 1 + i).coerceIn(0, width - 1)]
                val k = wx[i] * wy[j]
                a += k * ((p ushr 24) and 0xFF); rr += k * ((p shr 16) and 0xFF); g += k * ((p shr 8) and 0xFF); b += k * (p and 0xFF)
            }
        }
        fun c(d: Double) = d.roundToInt().coerceIn(0, 255)
        out[v * width + u] = (c(a) shl 24) or (c(rr) shl 16) or (c(g) shl 8) or c(b)
    }
    return Argb(width, height, out)
}

/** Keys' cubic convolution (a = −0.5) weights for the four pixels around a point [t] past the second. */
private fun cubicWeights(t: Double, w: DoubleArray) {
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

/** The rectangle from ([x0], [y0]) to ([x1], [y1]), exclusive. */
fun Argb.cut(x0: Int, y0: Int, x1: Int, y1: Int): Argb {
    val w = max(1, x1 - x0)
    val h = max(1, y1 - y0)
    val out = IntArray(w * h)
    for (y in 0 until h) pixels.copyInto(out, y * w, (y0 + y) * width + x0, (y0 + y) * width + x0 + w)
    return Argb(w, h, out)
}
