package tessera.editor

import androidx.compose.ui.geometry.Offset
import kotlin.math.ceil
import kotlin.math.floor

/** Pixel masks of a window of the page: filled shapes, grown or shrunk, their pockets and pieces. */
internal object Raster {
    /** Calls [set] for every pixel of the [ww]×[wh] window whose centre lies inside the closed path [points] (even-odd). */
    inline fun fill(points: List<Offset>, ww: Int, wh: Int, set: (Int) -> Unit) {
        val n = points.size
        if (n < 3) return
        val xs = FloatArray(n + 1)
        val y0 = floor(points.minOf { it.y } - 0.5f).toInt().coerceAtLeast(0)
        val y1 = ceil(points.maxOf { it.y } + 0.5f).toInt().coerceAtMost(wh - 1)
        for (y in y0..y1) {
            val cy = y + 0.5f
            var count = 0
            for (k in 0 until n) {
                val a = points[k]
                val b = points[(k + 1) % n]
                if ((a.y > cy) != (b.y > cy)) xs[count++] = a.x + (cy - a.y) * (b.x - a.x) / (b.y - a.y)
            }
            if (count < 2) continue
            xs.sort(0, count)
            var k = 0
            while (k + 1 < count) {
                val from = ceil(xs[k] - 0.5f).toInt().coerceAtLeast(0)
                val to = floor(xs[k + 1] - 0.5f).toInt().coerceAtMost(ww - 1)
                for (x in from..to) set(y * ww + x)
                k += 2
            }
        }
    }

    /** [mask] grown by [r] pixels in every direction (chessboard), in place; shrunk instead when [shrink]. */
    fun grow(mask: BooleanArray, ww: Int, wh: Int, r: Int, shrink: Boolean = false) {
        if (r <= 0) return
        // Rings outward from the edge of the set pixels (or inward, from the edge of the unset ones).
        val dist = IntArray(ww * wh) { if (mask[it] != shrink) 0 else Int.MAX_VALUE }
        val queue = IntArray(ww * wh)
        var head = 0
        var tail = 0
        for (l in mask.indices) {
            if (dist[l] != 0) continue
            val lx = l % ww
            val ly = l / ww
            val edge = (lx > 0 && mask[l - 1] == shrink) || (lx < ww - 1 && mask[l + 1] == shrink) || (ly > 0 && mask[l - ww] == shrink) || (ly < wh - 1 && mask[l + ww] == shrink) ||
                (lx > 0 && ly > 0 && mask[l - ww - 1] == shrink) || (lx < ww - 1 && ly > 0 && mask[l - ww + 1] == shrink) ||
                (lx > 0 && ly < wh - 1 && mask[l + ww - 1] == shrink) || (lx < ww - 1 && ly < wh - 1 && mask[l + ww + 1] == shrink)
            if (edge) queue[tail++] = l
        }
        while (head < tail) {
            val l = queue[head++]
            val d = dist[l]
            if (d >= r) continue
            val lx = l % ww
            val ly = l / ww
            for (dy in -1..1) for (dx in -1..1) {
                val nx = lx + dx
                val ny = ly + dy
                if (nx !in 0 until ww || ny !in 0 until wh) continue
                val nl = ny * ww + nx
                if (dist[nl] != Int.MAX_VALUE) continue
                dist[nl] = d + 1
                queue[tail++] = nl
            }
        }
        for (l in mask.indices) if (dist[l] <= r) mask[l] = !shrink
    }

    /** Opening: shrunk then grown back by [r], which takes away anything thinner than twice [r]. */
    fun open(mask: BooleanArray, ww: Int, wh: Int, r: Int) {
        grow(mask, ww, wh, r, shrink = true)
        grow(mask, ww, wh, r)
    }

    /**
     * Labels the 4-connected pieces of [mask] in [label] (−1 elsewhere) and returns their sizes.
     * [queue] is scratch space of the window's size.
     */
    private fun pieces(mask: BooleanArray, ww: Int, wh: Int, label: IntArray, queue: IntArray): IntArray {
        var sizes = IntArray(64)
        var count = 0
        for (s in mask.indices) {
            if (!mask[s] || label[s] >= 0) continue
            val c = count++
            if (c >= sizes.size) sizes = sizes.copyOf(2 * c)
            var head = 0
            var tail = 0
            label[s] = c; queue[tail++] = s
            while (head < tail) {
                val l = queue[head++]
                val lx = l % ww
                val ly = l / ww
                if (lx > 0 && mask[l - 1] && label[l - 1] < 0) { label[l - 1] = c; queue[tail++] = l - 1 }
                if (lx < ww - 1 && mask[l + 1] && label[l + 1] < 0) { label[l + 1] = c; queue[tail++] = l + 1 }
                if (ly > 0 && mask[l - ww] && label[l - ww] < 0) { label[l - ww] = c; queue[tail++] = l - ww }
                if (ly < wh - 1 && mask[l + ww] && label[l + ww] < 0) { label[l + ww] = c; queue[tail++] = l + ww }
            }
            sizes[c] = tail
        }
        return sizes.copyOf(count)
    }

    /** Keeps only the largest 4-connected piece of [mask]. */
    fun keepLargest(mask: BooleanArray, ww: Int, wh: Int) {
        val label = IntArray(ww * wh) { -1 }
        val sizes = pieces(mask, ww, wh, label, IntArray(ww * wh))
        if (sizes.size <= 1) return
        val best = sizes.indices.maxBy { sizes[it] }
        for (l in mask.indices) if (mask[l] && label[l] != best) mask[l] = false
    }

    /** Takes out of [mask] every 4-connected piece smaller than [minArea] pixels. */
    fun dropSmall(mask: BooleanArray, ww: Int, wh: Int, minArea: Int) {
        val label = IntArray(ww * wh) { -1 }
        val sizes = pieces(mask, ww, wh, label, IntArray(ww * wh))
        if (sizes.all { it >= minArea }) return
        for (l in mask.indices) if (mask[l] && sizes[label[l]] < minArea) mask[l] = false
    }

    /** Adds to [mask] the [pocket] pixels that no run of pocket pixels joins to the window's border. */
    fun fillPockets(mask: BooleanArray, pocket: BooleanArray, ww: Int, wh: Int) {
        val reached = BooleanArray(ww * wh)
        val queue = IntArray(ww * wh)
        var head = 0
        var tail = 0
        for (l in pocket.indices) {
            val lx = l % ww
            val ly = l / ww
            if (pocket[l] && (lx == 0 || ly == 0 || lx == ww - 1 || ly == wh - 1)) { reached[l] = true; queue[tail++] = l }
        }
        while (head < tail) {
            val l = queue[head++]
            val lx = l % ww
            val ly = l / ww
            if (lx > 0 && pocket[l - 1] && !reached[l - 1]) { reached[l - 1] = true; queue[tail++] = l - 1 }
            if (lx < ww - 1 && pocket[l + 1] && !reached[l + 1]) { reached[l + 1] = true; queue[tail++] = l + 1 }
            if (ly > 0 && pocket[l - ww] && !reached[l - ww]) { reached[l - ww] = true; queue[tail++] = l - ww }
            if (ly < wh - 1 && pocket[l + ww] && !reached[l + ww]) { reached[l + ww] = true; queue[tail++] = l + ww }
        }
        for (l in pocket.indices) if (pocket[l] && !reached[l]) mask[l] = true
    }

    /** The [cand] pixels within [reach] steps (4-connected, through cand pixels) of a pixel next to [from]. */
    fun attached(cand: BooleanArray, from: BooleanArray, ww: Int, wh: Int, reach: Int): BooleanArray {
        val dist = IntArray(ww * wh) { Int.MAX_VALUE }
        val queue = IntArray(ww * wh)
        var head = 0
        var tail = 0
        for (l in cand.indices) {
            if (!cand[l]) continue
            val lx = l % ww
            val ly = l / ww
            val next = (lx > 0 && from[l - 1]) || (lx < ww - 1 && from[l + 1]) || (ly > 0 && from[l - ww]) || (ly < wh - 1 && from[l + ww])
            if (next) { dist[l] = 0; queue[tail++] = l }
        }
        while (head < tail) {
            val l = queue[head++]
            val d = dist[l]
            if (d >= reach) continue
            val lx = l % ww
            val ly = l / ww
            if (lx > 0 && cand[l - 1] && dist[l - 1] == Int.MAX_VALUE) { dist[l - 1] = d + 1; queue[tail++] = l - 1 }
            if (lx < ww - 1 && cand[l + 1] && dist[l + 1] == Int.MAX_VALUE) { dist[l + 1] = d + 1; queue[tail++] = l + 1 }
            if (ly > 0 && cand[l - ww] && dist[l - ww] == Int.MAX_VALUE) { dist[l - ww] = d + 1; queue[tail++] = l - ww }
            if (ly < wh - 1 && cand[l + ww] && dist[l + ww] == Int.MAX_VALUE) { dist[l + ww] = d + 1; queue[tail++] = l + ww }
        }
        return BooleanArray(ww * wh) { dist[it] != Int.MAX_VALUE }
    }
}
