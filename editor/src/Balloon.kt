package tessera.editor

import androidx.compose.ui.graphics.ImageBitmap
import tessera.acbf.Point
import tessera.acbf.Polygon
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finding a balloon in the page image from a click inside it, like a magic wand: the light
 * interior is flooded from the click, its letters filled in, and the dark outline around it
 * measured. Only closed balloons are found: one whose light inside runs out into the page is not.
 */
object Balloon {
    /**
     * A balloon: [inner] covers its whole inside, reaching just onto its outline; [outer] runs
     * along the middle of the outline, to cut a frame around it (slightly inside, so that nothing
     * beyond the balloon shows); [clear] runs a little outside the outline, to cut it out of a
     * frame it intrudes on (so that nothing of it shows). [stroke] is the outline's width.
     */
    class Found(val inner: Polygon, val outer: Polygon, val clear: Polygon, val stroke: Int)

    /** The balloon around image pixel ([x], [y]) of [image], or null when there is no closed one. */
    fun find(image: ImageBitmap, x: Int, y: Int): Found? {
        val w = image.width
        val h = image.height
        if (x !in 0 until w || y !in 0 until h) return null
        val pixels = IntArray(w * h)
        image.readPixels(pixels, 0, 0, w, h)
        return find(pixels, w, h, x, y)
    }

    internal fun find(pixels: IntArray, w: Int, h: Int, x: Int, y: Int): Found? {
        fun luma(i: Int): Int { val c = pixels[i]; return (299 * ((c shr 16) and 255) + 587 * ((c shr 8) and 255) + 114 * (c and 255)) / 1000 }
        // A click on a letter starts from the nearest light pixel.
        var seed = -1
        val brightest = (max(0, y - 12)..min(h - 1, y + 12)).flatMap { yy -> (max(0, x - 12)..min(w - 1, x + 12)).map { xx -> yy * w + xx } }
            .sortedBy { i -> abs(i % w - x) + abs(i / w - y) }
        val top = brightest.maxOf { luma(it) }
        seed = brightest.first { luma(it) >= top - 30 }
        val threshold = (luma(seed) * 0.6).toInt()
        val light = { i: Int -> luma(i) > threshold }

        // The light inside, flooded from the seed (four neighbours, so that diagonal gaps hold).
        val inside = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0
        inside[seed] = true; queue[tail++] = seed
        var minX = w; var minY = h; var maxX = 0; var maxY = 0
        val limit = w.toLong() * h / 4
        while (head < tail) {
            val i = queue[head++]
            val px = i % w
            val py = i / w
            if (px == 0 || py == 0 || px == w - 1 || py == h - 1) return null // runs out to the page's edge
            if (tail > limit) return null // runs out into the page
            minX = min(minX, px); maxX = max(maxX, px); minY = min(minY, py); maxY = max(maxY, py)
            for (n in intArrayOf(i - 1, i + 1, i - w, i + w)) if (!inside[n] && light(n)) { inside[n] = true; queue[tail++] = n }
        }
        if (tail < 200) return null // a speck, not a balloon

        // A window around it, with room for the outline: outside is whatever its border reaches
        // without crossing the light inside; the rest (the letters too) is the balloon.
        val margin = 48
        val x0 = max(0, minX - margin); val y0 = max(0, minY - margin)
        val ww = min(w, maxX + margin + 1) - x0
        val wh = min(h, maxY + margin + 1) - y0
        val outside = BooleanArray(ww * wh)
        head = 0; tail = 0
        fun at(lx: Int, ly: Int) = (y0 + ly) * w + x0 + lx
        for (lx in 0 until ww) for (ly in intArrayOf(0, wh - 1)) { val l = ly * ww + lx; if (!outside[l] && !inside[at(lx, ly)]) { outside[l] = true; queue[tail++] = l } }
        for (ly in 0 until wh) for (lx in intArrayOf(0, ww - 1)) { val l = ly * ww + lx; if (!outside[l] && !inside[at(lx, ly)]) { outside[l] = true; queue[tail++] = l } }
        while (head < tail) {
            val l = queue[head++]
            val lx = l % ww
            val ly = l / ww
            if (lx > 0) visit(l - 1, outside, inside, at(lx - 1, ly), queue, tail).let { tail = it }
            if (lx < ww - 1) visit(l + 1, outside, inside, at(lx + 1, ly), queue, tail).let { tail = it }
            if (ly > 0) visit(l - ww, outside, inside, at(lx, ly - 1), queue, tail).let { tail = it }
            if (ly < wh - 1) visit(l + ww, outside, inside, at(lx, ly + 1), queue, tail).let { tail = it }
        }
        val balloon = BooleanArray(ww * wh) { !outside[it] }

        // Distance outward from the balloon, to measure its outline and grow it.
        val distance = IntArray(ww * wh) { if (balloon[it]) 0 else Int.MAX_VALUE }
        head = 0; tail = 0
        for (l in balloon.indices) if (balloon[l]) queue[tail++] = l
        // Ink: clearly darker than the grey or screentone often around a balloon.
        val ink = (luma(seed) * 0.4).toInt()
        val ring = IntArray(margin + 1)
        val ringDark = IntArray(margin + 1)
        while (head < tail) {
            val l = queue[head++]
            val d = distance[l]
            if (d >= margin) continue
            val lx = l % ww
            val ly = l / ww
            for (dy in -1..1) for (dx in -1..1) {
                val nx = lx + dx
                val ny = ly + dy
                if ((dx == 0 && dy == 0) || nx !in 0 until ww || ny !in 0 until wh) continue
                val n = ny * ww + nx
                if (distance[n] != Int.MAX_VALUE) continue
                distance[n] = d + 1
                ring[d + 1]++
                if (luma(at(nx, ny)) < ink) ringDark[d + 1]++
                queue[tail++] = n
            }
        }
        // The outline: the rings around the balloon as inked as the first ones; a screentone beyond
        // is far less so.
        fun inked(d: Int) = if (ring[d] == 0) 0f else ringDark[d].toFloat() / ring[d]
        val solid = max(inked(1), inked(2))
        var stroke = 0
        while (stroke < margin && ring[stroke + 1] > 0 && inked(stroke + 1) >= max(0.5f, solid * 0.7f)) stroke++
        stroke = stroke.coerceIn(1, 30)

        // Letters touching the outline would notch the shape: close it (grow, then shrink by as
        // much), which fills narrow notches and leaves the balloon's curves as they are.
        val r = (3 * stroke).coerceIn(6, 16).coerceAtMost(margin - 1)
        val grown = BooleanArray(ww * wh) { distance[it] <= r }
        val inward = IntArray(ww * wh) { if (grown[it]) Int.MAX_VALUE else 0 }
        head = 0; tail = 0
        for (l in grown.indices) if (!grown[l]) queue[tail++] = l
        while (head < tail) {
            val l = queue[head++]
            val d = inward[l]
            if (d > r) continue
            val lx = l % ww
            val ly = l / ww
            for (dy in -1..1) for (dx in -1..1) {
                val nx = lx + dx
                val ny = ly + dy
                if (nx !in 0 until ww || ny !in 0 until wh) continue
                val nl = ny * ww + nx
                if (inward[nl] != Int.MAX_VALUE) continue
                inward[nl] = d + 1
                queue[tail++] = nl
            }
        }
        val closed = BooleanArray(ww * wh) { balloon[it] || inward[it] > r }
        // Distance outward from the closed shape, to grow it to each outline.
        val reach = IntArray(ww * wh) { if (closed[it]) 0 else Int.MAX_VALUE }
        head = 0; tail = 0
        for (l in closed.indices) if (closed[l]) queue[tail++] = l
        while (head < tail) {
            val l = queue[head++]
            val d = reach[l]
            if (d >= margin) continue
            val lx = l % ww
            val ly = l / ww
            for (dy in -1..1) for (dx in -1..1) {
                val nx = lx + dx
                val ny = ly + dy
                if (nx !in 0 until ww || ny !in 0 until wh) continue
                val nl = ny * ww + nx
                if (reach[nl] != Int.MAX_VALUE) continue
                reach[nl] = d + 1
                queue[tail++] = nl
            }
        }

        fun polygon(grow: Int, tolerance: Float): Polygon? {
            val mask = BooleanArray(ww * wh) { reach[it] <= grow }
            val contour = trace(mask, ww, wh) ?: return null
            val simple = simplify(contour, tolerance)
            if (simple.size < 3) return null
            return Polygon(simple.map { Point(it.first + x0, it.second + y0) })
        }
        val inner = polygon(2, 1.2f) ?: return null
        val outer = polygon((stroke / 2.0).roundToInt(), 1.5f) ?: return null
        val clear = polygon(stroke + 2, 1.5f) ?: return null
        return Found(inner, outer, clear, stroke)
    }

    private fun visit(l: Int, outside: BooleanArray, inside: BooleanArray, image: Int, queue: IntArray, tail: Int): Int {
        if (outside[l] || inside[image]) return tail
        outside[l] = true
        queue[tail] = l
        return tail + 1
    }

    /** The outer boundary of the mask's shape, pixel by pixel (Moore neighbour tracing). */
    internal fun trace(mask: BooleanArray, w: Int, h: Int): List<Pair<Int, Int>>? {
        val start = mask.indexOfFirst { it }.takeIf { it >= 0 } ?: return null
        fun on(x: Int, y: Int) = x in 0 until w && y in 0 until h && mask[y * w + x]
        // Neighbours clockwise from west.
        val dx = intArrayOf(-1, -1, 0, 1, 1, 1, 0, -1)
        val dy = intArrayOf(0, -1, -1, -1, 0, 1, 1, 1)
        val sx = start % w
        val sy = start / w
        val out = ArrayList<Pair<Int, Int>>()
        var cx = sx
        var cy = sy
        var dir = 0 // we came from the west (the start is the first pixel of its row)
        do {
            out += cx to cy
            var found = false
            for (k in 0 until 8) {
                val d = (dir + k) % 8
                val nx = cx + dx[d]
                val ny = cy + dy[d]
                if (on(nx, ny)) {
                    cx = nx; cy = ny
                    dir = (d + 6) % 8 // look again from just behind the way we came
                    found = true
                    break
                }
            }
            if (!found || out.size > 4 * (w + h) * 4) break
        } while (cx != sx || cy != sy)
        return out
    }

    /** Douglas–Peucker on a closed outline, keeping points that matter more than [tolerance] pixels. */
    internal fun simplify(points: List<Pair<Int, Int>>, tolerance: Float): List<Pair<Int, Int>> {
        if (points.size < 4) return points
        // Split at the point farthest from the first, and simplify both halves.
        val far = points.indices.maxBy { (points[it].first - points[0].first).let { a -> a * a } + (points[it].second - points[0].second).let { b -> b * b } }
        fun dp(a: Int, b: Int, keep: BooleanArray) {
            if (b <= a + 1) return
            val (ax, ay) = points[a]
            val (bx, by) = points[b % points.size]
            val len = kotlin.math.hypot((bx - ax).toDouble(), (by - ay).toDouble()).coerceAtLeast(1e-9)
            var worst = -1
            var dist = 0.0
            for (i in a + 1 until b) {
                val (px, py) = points[i]
                val d = abs((bx - ax).toDouble() * (ay - py) - (ax - px).toDouble() * (by - ay)) / len
                if (d > dist) { dist = d; worst = i }
            }
            if (dist > tolerance) { keep[worst] = true; dp(a, worst, keep); dp(worst, b, keep) }
        }
        val keep = BooleanArray(points.size + 1)
        keep[0] = true; keep[far] = true
        dp(0, far, keep)
        dp(far, points.size, keep)
        return points.indices.filter { keep[it] }.map { points[it] }
    }

    /**
     * [frame] grown round [balloon] where the balloon spills over its edge: the frame's own points
     * stay as they are, except those the balloon covers, and the balloon's outline is followed
     * outside the frame. Null when no part of the balloon is outside the frame (or it is all outside).
     */
    fun around(frame: Polygon, balloon: Polygon): Polygon? {
        var current = frame
        var changed = false
        repeat(4) {
            val next = unionOnce(current, balloon) ?: return if (changed) current else null
            current = next; changed = true
        }
        return current
    }

    /**
     * [frame] cut back round [balloon] where the balloon intrudes on it: the frame's own points
     * stay, except those inside the balloon, and the balloon's outline is followed inside the
     * frame. Null when the balloon does not cross the frame's edge.
     */
    fun without(frame: Polygon, balloon: Polygon): Polygon? {
        var current = frame
        var changed = false
        repeat(4) {
            val next = cutOnce(current, balloon) ?: return if (changed) current else null
            current = next; changed = true
        }
        return current
    }

    private fun cutOnce(frame: Polygon, balloon: Polygon): Polygon? {
        val ccw = frame.signedArea > 0
        val f = frame.points
        var q = balloon.points
        if ((balloon.signedArea > 0) != ccw) q = q.reversed()
        val n = q.size
        val inside = BooleanArray(n) { frame.contains(q[it].x.toDouble(), q[it].y.toDouble()) && !onEdge(frame, q[it]) }
        if (inside.all { it } || inside.none { it }) return null
        // The longest run of balloon points inside the frame: the part that intrudes.
        var bestStart = -1
        var bestLen = 0
        for (s in 0 until n) {
            if (!inside[s] || inside[(s + n - 1) % n]) continue
            var len = 0
            while (len < n && inside[(s + len) % n]) len++
            if (len > bestLen) { bestLen = len; bestStart = s }
        }
        if (bestLen < 2) return null
        val a = bestStart
        val b = (bestStart + bestLen - 1) % n
        val (x1, i) = crossing(f, q[(a + n - 1) % n], q[a]) ?: return null // where the balloon enters
        val (x2, j) = crossing(f, q[b], q[(b + 1) % n]) ?: return null // and leaves
        // The frame up to where it meets the balloon, the balloon backwards inside it, then on.
        val out = ArrayList<Point>()
        out += x1
        var k = (i + 1) % f.size
        var guard = 0
        while (guard++ <= f.size) {
            out += f[k]
            if (k == j) break
            k = (k + 1) % f.size
        }
        out += x2
        for (m in 0 until bestLen) out += q[(b - m + n) % n]
        val distinct = out.filterIndexed { idx, p -> p != out[(idx + out.size - 1) % out.size] }
        if (distinct.size < 3) return null
        val cut = Polygon(distinct)
        // Cutting must leave less than before; otherwise the walk went the wrong way round.
        return cut.takeIf { it.area < frame.area }
    }

    private fun unionOnce(frame: Polygon, balloon: Polygon): Polygon? {
        val ccw = frame.signedArea > 0
        val f = frame.points
        var q = balloon.points
        if ((balloon.signedArea > 0) != ccw) q = q.reversed()
        val n = q.size
        val outside = BooleanArray(n) { !frame.contains(q[it].x.toDouble(), q[it].y.toDouble()) && !onEdge(frame, q[it]) }
        if (outside.all { it } || outside.none { it }) return null
        // The longest run of balloon points outside the frame: the part that spills over.
        var bestStart = -1
        var bestLen = 0
        for (s in 0 until n) {
            if (!outside[s] || outside[(s + n - 1) % n]) continue
            var len = 0
            while (len < n && outside[(s + len) % n]) len++
            if (len > bestLen) { bestLen = len; bestStart = s }
        }
        if (bestLen < 2) return null
        val a = bestStart
        val b = (bestStart + bestLen - 1) % n
        val (x1, i) = crossing(f, q[(a + n - 1) % n], q[a]) ?: return null
        val (x2, j) = crossing(f, q[b], q[(b + 1) % n]) ?: return null
        val out = ArrayList<Point>()
        out += x1
        for (k in 0 until bestLen) out += q[(a + k) % n]
        out += x2
        // Then the frame from just after the exit edge round to the entry edge.
        var k = (j + 1) % f.size
        var guard = 0
        while (guard++ <= f.size) {
            out += f[k]
            if (k == i) break
            k = (k + 1) % f.size
        }
        val distinct = out.filterIndexed { idx, p -> p != out[(idx + out.size - 1) % out.size] }
        return if (distinct.size >= 3) Polygon(distinct) else null
    }

    private fun onEdge(poly: Polygon, p: Point): Boolean = poly.points.indices.any { k ->
        val a = poly.points[k]
        val b = poly.points[(k + 1) % poly.points.size]
        val cross = (b.x - a.x).toLong() * (p.y - a.y) - (b.y - a.y).toLong() * (p.x - a.x)
        cross == 0L && p.x in min(a.x, b.x)..max(a.x, b.x) && p.y in min(a.y, b.y)..max(a.y, b.y)
    }

    /** Where the segment [p]→[q] crosses the polygon's outline, and on which edge (edge k: f[k]→f[k+1]). */
    private fun crossing(f: List<Point>, p: Point, q: Point): Pair<Point, Int>? {
        var best: Pair<Point, Int>? = null
        var bestT = Double.MAX_VALUE
        for (k in f.indices) {
            val a = f[k]
            val b = f[(k + 1) % f.size]
            val rx = (q.x - p.x).toDouble(); val ry = (q.y - p.y).toDouble()
            val sx = (b.x - a.x).toDouble(); val sy = (b.y - a.y).toDouble()
            val den = rx * sy - ry * sx
            if (den == 0.0) continue
            val t = ((a.x - p.x) * sy - (a.y - p.y) * sx) / den
            val u = ((a.x - p.x) * ry - (a.y - p.y) * rx) / den
            if (t in -0.001..1.001 && u in -0.001..1.001 && t < bestT) {
                bestT = t
                best = Point((p.x + rx * t).roundToInt(), (p.y + ry * t).roundToInt()) to k
            }
        }
        return best
    }
}
