package tessera.editor.scan

import kotlin.math.abs
import kotlin.math.max

/**
 * Where the paper ends on each side of an upright, straightened scan, in its pixels; null where
 * the scan shows no background (the page runs off the glass or fills the scan).
 */
data class Bounds(val left: Double?, val top: Double?, val right: Double?, val bottom: Double?) {
    fun scaled(s: Double) = Bounds(left?.times(s), top?.times(s), right?.times(s), bottom?.times(s))
}

/**
 * Finds the paper's edges on a flatbed scan of an open book. Seen from the scan's border inwards:
 * a band of background (the dark lid, the cover), then on the fore-edge the stacked edges of the
 * other pages (thin lines close together), then the paper. An edge is a line, parallel to the
 * side, where the brightness changes along most of its length. The band of background must be
 * narrow and dark or coloured, so a rule or a frame near a page edge never passes for it.
 */
object Paper {
    /** The analysis works on a copy this many pixels on its long side: the stacked edges are thin. */
    const val ANALYSIS = 2000

    fun bounds(img: Rgb): Bounds {
        val w = img.width
        val h = img.height
        val lum = img.luminance()
        val gap = max(3, (w * 0.004).toInt())
        // Columns read over the middle 80 % of the height, rows over the middle 90 % of the width.
        val y0 = (h * 0.1).toInt()
        val y1 = (h * 0.9).toInt()
        val x0 = (w * 0.05).toInt()
        val x1 = (w * 0.95).toInt()
        val colEdges = DoubleArray(w) { x -> edgeShare(lum, w, x, horizontal = true, y0, y1) }
        val rowEdges = DoubleArray(h) { y -> edgeShare(lum, w, y, horizontal = false, x0, x1) }
        val zoneX = (w * 0.035).toInt()
        val zoneY = (h * 0.035).toInt()
        fun column(i: Int) = Line(img, vertical = true, at = i, from = y0, until = y1)
        fun row(i: Int) = Line(img, vertical = false, at = i, from = x0, until = x1)
        val left = side(colEdges, zoneX, gap, ::column)
        val right = side(colEdges.reversedArray(), zoneX, gap) { column(w - 1 - it) }
        val top = side(rowEdges, zoneY, gap, ::row)
        val bottom = side(rowEdges.reversedArray(), zoneY, gap) { row(h - 1 - it) }
        return Bounds(left?.toDouble(), top?.toDouble(), right?.let { (w - it).toDouble() }, bottom?.let { (h - it).toDouble() })
    }

    /** One column or row of [img], over part of its length. */
    private class Line(val img: Rgb, val vertical: Boolean, val at: Int, val from: Int, val until: Int) {
        fun pixels(): Sequence<FloatArray> = (from until until).asSequence().map { i ->
            if (vertical) img.sample(at.toDouble(), i) else img.sample(i.toDouble(), at)
        }
    }

    /**
     * The share of a column's (or row's) length where brightness changes across it by more than
     * 0.05 over two pixels.
     */
    private fun edgeShare(lum: FloatArray, w: Int, at: Int, horizontal: Boolean, from: Int, until: Int): Double {
        val size = if (horizontal) w else lum.size / w
        if (at < 1 || at >= size - 1) return 0.0
        var n = 0
        for (i in from until until) {
            val d = if (horizontal) lum[i * w + at + 1] - lum[i * w + at - 1] else lum[(at + 1) * w + i] - lum[(at - 1) * w + i]
            if (abs(d) > 0.05f) n++
        }
        return n.toDouble() / (until - from)
    }

    /**
     * Inwards from the border ([edges] ordered so), the paper's edge: just past the cluster of
     * edges that starts the side (the background's own edge, then any stacked page edges, no more
     * than [gap] apart). Null when there is none within [zone], or when what lies before it looks
     * like paper rather than background.
     */
    private fun side(edges: DoubleArray, zone: Int, gap: Int, line: (Int) -> Line): Int? {
        val found = (0 until zone).filter { edges[it] > 0.5 }
        if (found.isEmpty()) return null
        val first = found[0]
        if (first > 0) {
            var sum = 0.0
            var saturation = 0.0
            var n = 0
            for (i in 0 until first) for (p in line(i).pixels()) {
                sum += (p[0] + p[1] + p[2]) / 3.0
                saturation += maxOf(p[0], p[1], p[2]) - minOf(p[0], p[1], p[2])
                n++
            }
            if (sum / n >= 0.6 && saturation / n <= 0.25) return null
        }
        var last = first
        for (i in found.drop(1)) if (i - last <= gap) last = i else break
        return last + 1
    }
}
