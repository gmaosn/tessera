package tessera.editor

import androidx.compose.ui.geometry.Offset
import tessera.acbf.Point
import tessera.acbf.Polygon
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What spills over the frames of a page, found in the page image: its balloons (a light inside
 * closed by a dark outline, with lettering), and the ink that runs out of a frame into the
 * paper around it (a character's hair, a sound effect, a balloon with no lettering). From them,
 * the shape the reader cuts each frame with: the frame put onto its printed border where it was
 * drawn a little off it, with what spills out of it and a sliver of paper round that, without
 * the balloons that intrude from other frames. On screen only: the file's frames stay as they
 * are. The printed borders also guide the drawing tool ([borderNear]).
 */
object Spill {
    /**
     * A page's analysis, once per image: what each pixel is ([PAPER], [LIGHT] like paper but
     * off its colour, [INK] or [OTHER]), the paper's luma ([ground]), and its balloons.
     */
    class Ink(val w: Int, val h: Int, val kind: ByteArray, val ground: Int, val balloons: List<Balloon.Found>) {
        fun isInk(x: Int, y: Int): Boolean = x in 0 until w && y in 0 until h && kind[y * w + x] == INK
    }

    const val PAPER: Byte = 0
    const val INK: Byte = 1
    const val OTHER: Byte = 2
    const val LIGHT: Byte = 3

    /** What the reader cuts a frame with: [outline] as [Outline.of] walks it; [plain] when it is the frame's own rectangle. */
    class Cut(val outline: List<Offset>, val plain: Boolean) {
        val minX: Float = outline.minOf { it.x }
        val minY: Float = outline.minOf { it.y }
        val maxX: Float = outline.maxOf { it.x }
        val maxY: Float = outline.maxOf { it.y }
    }

    /** Letter-sized dark pieces a balloon must hold. */
    private const val LETTERS = 4

    /** How far out of its frame an element may run, as a share of the frame's smaller side. */
    private const val REACH = 0.3

    /** The paper left round ink that runs out of a frame, in pixels. */
    private const val RIM = 5

    /** Lines (panel borders) thinner than twice this are not elements that spill. */
    private const val LINE = 3

    /** Pieces of ink smaller than this many pixels are specks of the scan, not elements. */
    private const val SPECK = 120

    /** How far short of its printed border a frame may be drawn for the border still to be taken, in pixels. */
    private const val SHORT = 6

    /** Balloons found in the page: the paper's light, closed by an outline, with lettering inside. */
    fun analyse(pixels: IntArray, w: Int, h: Int): Ink {
        val n = w * h
        val (pr, pg, pb) = paperColour(pixels, w, h)
        val ground = (299 * pr + 587 * pg + 114 * pb) / 1000
        val dark = (ground * 0.45).toInt()
        val kind = ByteArray(n)
        val light = BooleanArray(n)
        for (i in 0 until n) {
            val c = pixels[i]
            val r = (c shr 16) and 255
            val g = (c shr 8) and 255
            val b = c and 255
            val d = abs(r - pr) + abs(g - pg) + abs(b - pb)
            // Paper is judged with room for a scan's shading; the gutters are often greyer than the margins.
            kind[i] = if (d <= 120) PAPER else if ((299 * r + 587 * g + 114 * b) / 1000 < dark) INK else if (d <= 160) LIGHT else OTHER
            light[i] = d <= 130
        }
        // The light pieces of the page, each with its extent.
        val label = IntArray(n) { -1 }
        val queue = IntArray(n)
        var count = 0
        var area = IntArray(1024)
        var box = IntArray(4 * 1024)
        var edge = BooleanArray(1024)
        for (s in 0 until n) {
            if (!light[s] || label[s] >= 0) continue
            val c = count++
            if (c >= area.size) { area = area.copyOf(2 * c); box = box.copyOf(8 * c); edge = edge.copyOf(2 * c) }
            var head = 0
            var tail = 0
            label[s] = c; queue[tail++] = s
            var x0 = w; var x1 = 0; var y0 = h; var y1 = 0
            var onEdge = false
            while (head < tail) {
                val i = queue[head++]
                val px = i % w
                val py = i / w
                if (px < x0) x0 = px
                if (px > x1) x1 = px
                if (py < y0) y0 = py
                if (py > y1) y1 = py
                if (px == 0 || py == 0 || px == w - 1 || py == h - 1) onEdge = true
                if (px > 0 && light[i - 1] && label[i - 1] < 0) { label[i - 1] = c; queue[tail++] = i - 1 }
                if (px < w - 1 && light[i + 1] && label[i + 1] < 0) { label[i + 1] = c; queue[tail++] = i + 1 }
                if (py > 0 && light[i - w] && label[i - w] < 0) { label[i - w] = c; queue[tail++] = i - w }
                if (py < h - 1 && light[i + w] && label[i + w] < 0) { label[i + w] = c; queue[tail++] = i + w }
            }
            area[c] = tail
            box[4 * c] = x0; box[4 * c + 1] = y0; box[4 * c + 2] = x1; box[4 * c + 3] = y1
            edge[c] = onEdge
        }
        // Candidates: larger than a few letters, far smaller than the page, not the margins, and
        // filling their box as a round or boxed shape does.
        val minArea = max(1500, n / 4000)
        val maxArea = n / 8
        val balloons = ArrayList<Balloon.Found>()
        for (c in 0 until count) {
            if (edge[c] || area[c] < minArea || area[c] > maxArea) continue
            val bw = box[4 * c + 2] - box[4 * c] + 1
            val bh = box[4 * c + 3] - box[4 * c + 1] + 1
            if (bw < 16 || bh < 16 || area[c] < 0.35 * bw * bh) continue
            val found = Balloon.measure(pixels, w, h, { label[it] == c }, box[4 * c], box[4 * c + 1], box[4 * c + 2], box[4 * c + 3], ground) ?: continue
            if (found.outlined && found.letters >= LETTERS) balloons += found
        }
        return Ink(w, h, kind, ground, balloons)
    }

    /** How far from a frame's edge a printed border may lie for the edge to be put onto it, in pixels. */
    private fun reachOf(ink: Ink) = max(20, ink.w / 70)

    /** A printed border line is at most this thick, in pixels. */
    private fun lineWidth(ink: Ink) = max(6, ink.w / 250)

    /**
     * [frame] with each straight side of its outline put onto the printed border found along
     * it, two pixels inside the ink: a frame drawn a little over its border, or a little short
     * of it, reads as the panel exactly. A side counts when it is long (a quarter of the frame)
     * or when it is at least 40 px and turns sharply at both ends; the short segments of a curve
     * traced round a balloon do not. Sides along which no line is found stay where they are.
     */
    internal fun tightened(ink: Ink, frame: Polygon): Polygon {
        val pts = frame.points
        val n = pts.size
        if (n < 3) return frame
        val clockwise = frame.signedArea > 0
        val size = max(frame.maxX - frame.minX, frame.maxY - frame.minY).toFloat()
        val reach = reachOf(ink)
        val delta = FloatArray(n) { Float.NaN }
        val dirs = Array(n) { Offset.Zero }
        val normals = Array(n) { Offset.Zero }
        fun turn(k: Int): Double {
            val a = pts[(k + n - 1) % n]
            val b = pts[k]
            val c = pts[(k + 1) % n]
            val e1x = (b.x - a.x).toDouble(); val e1y = (b.y - a.y).toDouble()
            val e2x = (c.x - b.x).toDouble(); val e2y = (c.y - b.y).toDouble()
            return abs(kotlin.math.atan2(e1x * e2y - e1y * e2x, e1x * e2x + e1y * e2y)) * 180 / kotlin.math.PI
        }
        for (k in 0 until n) {
            val a = pts[k]
            val b = pts[(k + 1) % n]
            val len = kotlin.math.hypot((b.x - a.x).toFloat(), (b.y - a.y).toFloat())
            if (len < 40f || (len < 0.25f * size && (turn(k) < 50.0 || turn((k + 1) % n) < 50.0))) continue
            val dir = Offset((b.x - a.x) / len, (b.y - a.y) / len)
            dirs[k] = dir
            normals[k] = if (clockwise) Offset(-dir.y, dir.x) else Offset(dir.y, -dir.x)
            val band = borderAlong(ink, Offset(a.x.toFloat(), a.y.toFloat()), Offset(b.x.toFloat(), b.y.toFloat()), normals[k]) ?: continue
            delta[k] = (band.last + 2).toFloat()
        }
        if (delta.all { it.isNaN() }) return frame
        val out = ArrayList<Point>(n)
        for (k in 0 until n) {
            val before = (k + n - 1) % n
            val p = Offset(pts[k].x.toFloat(), pts[k].y.toFloat())
            val dIn = if (delta[before].isNaN()) 0f else delta[before]
            val dOut = if (delta[k].isNaN()) 0f else delta[k]
            val sideIn = dirs[before] != Offset.Zero
            val sideOut = dirs[k] != Offset.Zero
            var moved = when {
                sideIn && sideOut -> {
                    // Where the two moved sides' lines cross.
                    val p1 = p + normals[before] * dIn
                    val p2 = p + normals[k] * dOut
                    val d1 = dirs[before]
                    val d2 = dirs[k]
                    val cross = d1.x * d2.y - d1.y * d2.x
                    if (abs(cross) < 0.2f) p + normals[k] * dOut
                    else p1 + d1 * (((p2.x - p1.x) * d2.y - (p2.y - p1.y) * d2.x) / cross)
                }
                sideIn -> p + normals[before] * dIn
                sideOut -> p + normals[k] * dOut
                else -> p
            }
            if ((moved - p).getDistance() > reach + 3f) moved = p + (if (abs(dOut) >= abs(dIn)) normals[k] * dOut else normals[before] * dIn)
            out += Point(moved.x.roundToInt(), moved.y.roundToInt())
        }
        return Polygon(out)
    }

    /**
     * The printed border line along the edge [a]→[b], as offsets along [inward] (a unit normal):
     * a band of ink no thicker than a line, solid along the edge (its balloons left out), within
     * reach of it, with paper beyond it (the gutter: a line of the drawing has the drawing
     * beyond it, and a neighbouring frame's border has that frame). Among such bands, the
     * farthest inside the edge (the frame drawn over the line, maybe over the next frame's line
     * too), else one just outside (the frame drawn a little short of it). Null when there is none.
     */
    private fun borderAlong(ink: Ink, a: Offset, b: Offset, inward: Offset): IntRange? {
        val reach = reachOf(ink)
        val len = (b - a).getDistance()
        val dir = (b - a) / len
        // Samples along the edge, clear of its ends, where the corners' other lines are.
        val from = 10f
        val to = len - 10f
        if (to - from < 20f) return null
        val samples = ((to - from) / 6f).toInt() + 1
        val low = -reach - 2
        val inkAt = IntArray(reach - low + 1)
        val paperAt = IntArray(reach - low + 1)
        // Balloons along the edge are left out: their lettering would read as a line with paper beyond.
        var counted = 0
        for (s in 0 until samples) {
            val p = a + dir * (from + (to - from) * s / (samples - 1).coerceAtLeast(1))
            if (ink.balloons.any { b -> val h = b.halo; p.x >= h.minX - 1 && p.x <= h.maxX + 1 && p.y >= h.minY - 1 && p.y <= h.maxY + 1 && h.contains(p.x.toDouble(), p.y.toDouble()) }) continue
            counted++
            for (d in low..reach) {
                // The pixel at offset d: the first inside the edge at d = 0, on every side alike.
                val q = p + inward * (d + 0.5f)
                val x = kotlin.math.floor(q.x).toInt()
                val y = kotlin.math.floor(q.y).toInt()
                if (x !in 0 until ink.w || y !in 0 until ink.h) continue
                when (ink.kind[y * ink.w + x]) {
                    INK -> inkAt[d - low]++
                    PAPER, LIGHT -> paperAt[d - low]++
                }
            }
        }
        if (counted < 4) return null
        // A line is solid along the edge, has paper beyond it and stands out from what is inside.
        val solid = (0.75 * counted).toInt().coerceAtLeast(2)
        val mostly = (0.6 * counted).toInt().coerceAtLeast(2)
        val width = lineWidth(ink)
        fun inside(band: IntRange) = band.last >= -2
        var best: IntRange? = null
        // Outside, only just: a frame is seldom drawn far short of its border, while the next
        // frame's border lies a gutter away.
        var d = -SHORT - 2
        while (d <= reach) {
            if (inkAt[d - low] < solid) { d++; continue }
            var e = d
            while (e + 1 <= reach && inkAt[e + 1 - low] >= solid) e++
            val band = d..e
            d = e + 1
            if (band.last - band.first + 1 > width) continue
            if (paperAt[band.first - 2 - low] < mostly) continue
            // Darker than what is just inside it, by a clear margin (a black ground hides its border).
            val bandMin = (band.first..band.last).minOf { inkAt[it - low] }
            if (band.last + 2 <= reach && inkAt[band.last + 2 - low] > 0.8 * bandMin) continue
            val b0 = best
            best = when {
                b0 == null -> band
                inside(band) != inside(b0) -> if (inside(band)) band else b0
                else -> if (band.last > b0.last) band else b0
            }
        }
        return best
    }

    /**
     * A printed border line near ([x], [y]): the columns (when [vertical]) or rows of a band of
     * ink no thicker than a line within [radius] pixels, the nearest to the point. The ink is
     * looked for over twenty pixels on either side of the point along the line, so that a
     * corner's lines are found from just outside the corner too.
     */
    fun borderNear(ink: Ink, x: Int, y: Int, radius: Int, vertical: Boolean): IntRange? {
        val width = lineWidth(ink)
        val probe = 20
        fun lineAt(c: Int): Boolean {
            var before = 0
            var after = 0
            for (t in 0..probe) {
                if (if (vertical) ink.isInk(c, y - t) else ink.isInk(x - t, c)) before++
                if (if (vertical) ink.isInk(c, y + t) else ink.isInk(x + t, c)) after++
            }
            return before * 2 > probe || after * 2 > probe
        }
        val centre = if (vertical) x else y
        val limit = if (vertical) ink.w else ink.h
        fun away(band: IntRange) = if (centre in band) 0 else min(abs(band.first - centre), abs(band.last - centre))
        var best: IntRange? = null
        var c = (centre - radius).coerceAtLeast(0)
        val end = (centre + radius).coerceAtMost(limit - 1)
        while (c <= end) {
            if (!lineAt(c)) { c++; continue }
            // The whole band, which may begin before the search's range or end past it.
            var s = c
            while (s - 1 >= 0 && c - s < width && lineAt(s - 1)) s--
            var e = c
            while (e + 1 <= limit - 1 && e - s < width && lineAt(e + 1)) e++
            val band = s..e
            c = e + 1
            if (band.last - band.first + 1 > width) continue
            val b0 = best
            if (b0 == null || away(band) < away(b0)) best = band
        }
        return best
    }

    /** The paper's colour: the median of the light pixels in the page's outer strips (white when there are none). */
    private fun paperColour(pixels: IntArray, w: Int, h: Int): IntArray {
        val sx = max(8, w / 50)
        val sy = max(8, h / 50)
        val hist = Array(3) { IntArray(256) }
        var total = 0
        var strip = 0
        fun add(i: Int) {
            strip++
            val c = pixels[i]
            val r = (c shr 16) and 255
            val g = (c shr 8) and 255
            val b = c and 255
            if ((299 * r + 587 * g + 114 * b) / 1000 < 150) return
            hist[0][r]++; hist[1][g]++; hist[2][b]++
            total++
        }
        for (y in 0 until h) {
            if (y < sy || y >= h - sy) for (x in 0 until w) add(y * w + x)
            else { for (x in 0 until min(sx, w)) add(y * w + x); for (x in max(0, w - sx) until w) add(y * w + x) }
        }
        if (total < strip / 100) return intArrayOf(255, 255, 255)
        return IntArray(3) { ch ->
            var seen = 0
            var v = 255
            for (k in 0..255) { seen += hist[ch][k]; if (seen * 2 >= total) { v = k; break } }
            v
        }
    }

    /** Where a balloon lies among the frames: the one holding most of it owns it. */
    private class Placed(val found: Balloon.Found, val owner: Int, val into: Set<Int>, val spills: Boolean)

    /**
     * What the reader cuts each of [frames] with, given the page's [ink] (null: the frames as
     * they are): a frame with its balloons and ink that spill out, without the balloons that
     * intrude from other frames.
     */
    fun cuts(ink: Ink?, frames: List<Polygon>): List<Cut> {
        val plain = frames.map { Cut(Outline.of(it), it.isRectangle) }
        if (ink == null || frames.isEmpty()) return plain
        val w = ink.w
        val h = ink.h
        // One frame covering the whole page has nothing outside it.
        if (frames.size == 1 && frames[0].minX <= 0 && frames[0].minY <= 0 && frames[0].maxX >= w && frames[0].maxY >= h) return plain
        // The frames as drawn, put onto their printed borders where they were drawn a little off them.
        val tight = frames.map { tightened(ink, it) }
        val label = IntArray(w * h) { -1 }
        tight.forEachIndexed { k, t -> Raster.fill(t.points.map { Offset(it.x.toFloat(), it.y.toFloat()) }, w, h) { if (label[it] < 0) label[it] = k } }
        val placed = ink.balloons.mapNotNull { b ->
            // How much of the balloon each frame holds, on a grid of samples inside it.
            val inner = b.inner
            val counts = IntArray(frames.size + 1) // the last: in no frame
            for (i in 0 until 24) for (j in 0 until 24) {
                val x = inner.minX + (inner.maxX - inner.minX) * (i + 0.5) / 24
                val y = inner.minY + (inner.maxY - inner.minY) * (j + 0.5) / 24
                if (!inner.contains(x, y)) continue
                val px = x.toInt()
                val py = y.toInt()
                if (px !in 0 until w || py !in 0 until h) continue
                val l = label[py * w + px]
                counts[if (l < 0) frames.size else l]++
            }
            val owner = frames.indices.maxByOrNull { counts[it] }?.takeIf { counts[it] > 0 } ?: return@mapNotNull null
            // It spills when its outline and paper do not all lie inside the owner.
            val spills = counts[owner] < counts.sum() || b.halo.points.any { !tight[owner].contains(it.x.toDouble(), it.y.toDouble()) }
            Placed(b, owner, frames.indices.filter { it != owner && counts[it] > 0 }.toSet(), spills)
        }
        return frames.indices.map { k -> cut(ink, label, frames[k], tight[k], placed, k, plain[k]) }
    }

    private fun cut(ink: Ink, label: IntArray, drawn: Polygon, f: Polygon, placed: List<Placed>, k: Int, plain: Cut): Cut {
        val w = ink.w
        val h = ink.h
        val dense = Outline.shape(f)
        val owned = placed.filter { it.owner == k && it.spills }
        val intruding = placed.filter { it.owner != k && k in it.into }
        // Other frames' balloons that come anywhere near this frame.
        val others = placed.filter { p ->
            val b = p.found.halo
            p.owner != k && b.maxX >= f.minX - 400 && b.minX <= f.maxX + 400 && b.maxY >= f.minY - 400 && b.minY <= f.maxY + 400
        }
        val reach = (REACH * min(f.maxX - f.minX, f.maxY - f.minY)).toInt().coerceIn(20, 300)
        // A window round the frame, as far as its ink may run, and round its balloons.
        var x0 = min(f.minX, drawn.minX) - reach - RIM - 2
        var y0 = min(f.minY, drawn.minY) - reach - RIM - 2
        var x1 = max(f.maxX, drawn.maxX) + reach + RIM + 2
        var y1 = max(f.maxY, drawn.maxY) + reach + RIM + 2
        for (p in owned) { x0 = min(x0, p.found.halo.minX - 2); y0 = min(y0, p.found.halo.minY - 2); x1 = max(x1, p.found.halo.maxX + 2); y1 = max(y1, p.found.halo.maxY + 2) }
        x0 = x0.coerceIn(0, w - 1); y0 = y0.coerceIn(0, h - 1)
        x1 = x1.coerceIn(x0 + 1, w); y1 = y1.coerceIn(y0 + 1, h)
        val ww = x1 - x0
        val wh = y1 - y0
        fun local(points: List<tessera.acbf.Point>) = points.map { Offset((it.x - x0).toFloat(), (it.y - y0).toFloat()) }
        val mask = BooleanArray(ww * wh)
        Raster.fill(dense.points.map { Offset(it.x - x0, it.y - y0) }, ww, wh) { mask[it] = true }
        var changed = f != drawn
        // Ink that runs out of the frame into the paper: whatever round the frame is not paper,
        // with the paper pockets ink closes off (a balloon's inside), except other frames'
        // balloons; lines (panel borders) are thinner than what counts.
        run {
            val cand = BooleanArray(ww * wh)
            val pocket = BooleanArray(ww * wh)
            for (ly in 0 until wh) for (lx in 0 until ww) {
                val i = (y0 + ly) * w + x0 + lx
                if (label[i] >= 0) continue
                if (ink.kind[i] == PAPER) pocket[ly * ww + lx] = true else cand[ly * ww + lx] = true
            }
            for (p in others) Raster.fill(local(p.found.halo.points), ww, wh) { cand[it] = false; pocket[it] = false }
            Raster.fillPockets(cand, pocket, ww, wh)
            Raster.open(cand, ww, wh, LINE)
            val spill = Raster.attached(cand, mask, ww, wh, reach)
            Raster.dropSmall(spill, ww, wh, SPECK)
            val amount = spill.count { it }
            if (amount > 0 && amount < 0.5 * f.area) {
                Raster.grow(spill, ww, wh, RIM)
                for (l in mask.indices) if (spill[l]) mask[l] = true
                changed = true
            }
        }
        // Its own balloons that spill, outline and paper round it; the balloons of other frames taken out, likewise.
        for (p in owned) { Raster.fill(local(p.found.halo.points), ww, wh) { mask[it] = true }; changed = true }
        for (p in intruding) { Raster.fill(local(p.found.halo.points), ww, wh) { mask[it] = false }; changed = true }
        if (!changed) return plain
        Raster.keepLargest(mask, ww, wh)
        val pts = dense.points
        val corners = dense.corners.map { c -> Outline.Corner(pts[c], pts[(c + pts.size - 1) % pts.size], pts[(c + 1) % pts.size]) }
        return Cut(Outline.fromMask(mask, ww, wh, x0, y0, corners), plain = false)
    }
}
