package tessera.editor

import androidx.compose.ui.geometry.Offset
import tessera.acbf.Polygon
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * What spills over the frames of a page, found in the page image: its balloons (a light inside
 * closed by a dark outline, with lettering), and the ink that runs out of a frame into the
 * paper around it (a character's hair, a sound effect, a balloon with no lettering). From them,
 * the shape the reader cuts each frame with: the frame, with what spills out of it and a sliver
 * of paper round that, without the balloons that intrude from other frames. On screen only:
 * the file's frames stay as they are.
 */
object Spill {
    /** A page's analysis, once per image: which pixels are paper, and its balloons. */
    class Ink(val w: Int, val h: Int, val paper: BooleanArray, val balloons: List<Balloon.Found>)

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

    /** Balloons found in the page: the paper's light, closed by an outline, with lettering inside. */
    fun analyse(pixels: IntArray, w: Int, h: Int): Ink {
        val n = w * h
        val (pr, pg, pb) = paperColour(pixels, w, h)
        val paper = BooleanArray(n)
        val light = BooleanArray(n)
        for (i in 0 until n) {
            val c = pixels[i]
            val d = abs(((c shr 16) and 255) - pr) + abs(((c shr 8) and 255) - pg) + abs((c and 255) - pb)
            paper[i] = d <= 90
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
        val ground = (299 * pr + 587 * pg + 114 * pb) / 1000
        val balloons = ArrayList<Balloon.Found>()
        for (c in 0 until count) {
            if (edge[c] || area[c] < minArea || area[c] > maxArea) continue
            val bw = box[4 * c + 2] - box[4 * c] + 1
            val bh = box[4 * c + 3] - box[4 * c + 1] + 1
            if (bw < 16 || bh < 16 || area[c] < 0.35 * bw * bh) continue
            val found = Balloon.measure(pixels, w, h, { label[it] == c }, box[4 * c], box[4 * c + 1], box[4 * c + 2], box[4 * c + 3], ground) ?: continue
            if (found.outlined && found.letters >= LETTERS) balloons += found
        }
        return Ink(w, h, paper, balloons)
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
        val label = IntArray(w * h) { -1 }
        frames.forEachIndexed { k, f -> Raster.fill(f.points.map { Offset(it.x.toFloat(), it.y.toFloat()) }, w, h) { if (label[it] < 0) label[it] = k } }
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
            val spills = counts[owner] < counts.sum() || b.halo.points.any { !frames[owner].contains(it.x.toDouble(), it.y.toDouble()) }
            Placed(b, owner, frames.indices.filter { it != owner && counts[it] > 0 }.toSet(), spills)
        }
        return frames.indices.map { k -> cut(ink, label, frames, k, placed, plain[k]) }
    }

    private fun cut(ink: Ink, label: IntArray, frames: List<Polygon>, k: Int, placed: List<Placed>, plain: Cut): Cut {
        val w = ink.w
        val h = ink.h
        val f = frames[k]
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
        var x0 = f.minX - reach - RIM - 2
        var y0 = f.minY - reach - RIM - 2
        var x1 = f.maxX + reach + RIM + 2
        var y1 = f.maxY + reach + RIM + 2
        for (p in owned) { x0 = min(x0, p.found.halo.minX - 2); y0 = min(y0, p.found.halo.minY - 2); x1 = max(x1, p.found.halo.maxX + 2); y1 = max(y1, p.found.halo.maxY + 2) }
        x0 = x0.coerceIn(0, w - 1); y0 = y0.coerceIn(0, h - 1)
        x1 = x1.coerceIn(x0 + 1, w); y1 = y1.coerceIn(y0 + 1, h)
        val ww = x1 - x0
        val wh = y1 - y0
        fun local(points: List<tessera.acbf.Point>) = points.map { Offset((it.x - x0).toFloat(), (it.y - y0).toFloat()) }
        val mask = BooleanArray(ww * wh)
        Raster.fill(dense.points.map { Offset(it.x - x0, it.y - y0) }, ww, wh) { mask[it] = true }
        var changed = false
        // Ink that runs out of the frame into the paper: whatever round the frame is not paper,
        // with the paper pockets ink closes off (a balloon's inside), except other frames'
        // balloons; lines (panel borders) are thinner than what counts.
        run {
            val cand = BooleanArray(ww * wh)
            val pocket = BooleanArray(ww * wh)
            for (ly in 0 until wh) for (lx in 0 until ww) {
                val i = (y0 + ly) * w + x0 + lx
                if (label[i] >= 0) continue
                if (ink.paper[i]) pocket[ly * ww + lx] = true else cand[ly * ww + lx] = true
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
