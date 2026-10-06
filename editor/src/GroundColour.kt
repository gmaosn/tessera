package tessera.editor

import androidx.compose.ui.graphics.ImageBitmap
import tessera.acbf.Polygon
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The colour behind the text drawn in [polygon] on [image], as "#rrggbb": the pixels inside are
 * split into dark and light by Otsu's threshold; when one side is clearly the smaller (the
 * letters), it is left out, with the letters' soft edges, and the rest averaged. A texture (screentone) has no small side and is
 * averaged whole, which is the colour the eye sees. Null when the polygon covers no pixel.
 */
fun groundColour(image: ImageBitmap, polygon: Polygon): String? {
    val x0 = polygon.minX.coerceIn(0, image.width)
    val y0 = polygon.minY.coerceIn(0, image.height)
    val w = polygon.maxX.coerceIn(0, image.width) - x0
    val h = polygon.maxY.coerceIn(0, image.height) - y0
    if (w <= 0 || h <= 0) return null
    val pixels = IntArray(w * h)
    image.readPixels(pixels, x0, y0, w, h)
    // At most about 60,000 samples, spread evenly.
    val step = max(1, kotlin.math.sqrt(w.toDouble() * h / 60_000).toInt())
    val inside = ArrayList<Int>()
    for (y in 0 until h step step) for (x in 0 until w step step) {
        if (polygon.contains(x0 + x + 0.5, y0 + y + 0.5)) inside += pixels[y * w + x]
    }
    if (inside.isEmpty()) return null
    fun luma(c: Int) = (299 * ((c shr 16) and 255) + 587 * ((c shr 8) and 255) + 114 * (c and 255)) / 1000
    val histogram = IntArray(256)
    for (c in inside) histogram[luma(c)]++
    val threshold = otsu(histogram, inside.size)
    val dark = inside.count { luma(it) <= threshold }
    val light = inside.size - dark
    // The side kept loses its half nearest the threshold too: the letters' soft edges.
    fun core(side: List<Int>, light: Boolean): List<Int> {
        val mean = side.sumOf { luma(it) }.toDouble() / side.size.coerceAtLeast(1)
        val cut = (threshold + mean) / 2
        return side.filter { if (light) luma(it) >= cut else luma(it) <= cut }.ifEmpty { side }
    }
    val kept = when {
        dark < inside.size * 0.35 -> core(inside.filter { luma(it) > threshold }, light = true)
        light < inside.size * 0.35 -> core(inside.filter { luma(it) <= threshold }, light = false)
        else -> inside
    }.ifEmpty { inside }
    var r = 0L
    var g = 0L
    var b = 0L
    for (c in kept) { r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255 }
    fun hex(v: Long) = (v.toDouble() / kept.size).roundToInt().coerceIn(0, 255).toString(16).padStart(2, '0')
    return "#" + hex(r) + hex(g) + hex(b)
}

/** The luminance threshold that best separates the histogram in two (Otsu). */
private fun otsu(histogram: IntArray, total: Int): Int {
    var sum = 0.0
    for (i in 0..255) sum += i.toDouble() * histogram[i]
    var sumB = 0.0
    var weightB = 0
    var best = 0.0
    var threshold = 127
    for (t in 0..255) {
        weightB += histogram[t]
        if (weightB == 0) continue
        val weightF = total - weightB
        if (weightF == 0) break
        sumB += t.toDouble() * histogram[t]
        val mB = sumB / weightB
        val mF = (sum - sumB) / weightF
        val between = weightB.toDouble() * weightF * (mB - mF) * (mB - mF)
        if (between > best) { best = between; threshold = t }
    }
    return threshold
}
