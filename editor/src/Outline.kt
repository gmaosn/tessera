package tessera.editor

import androidx.compose.ui.geometry.Offset
import tessera.acbf.Polygon
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The shape the reader cuts a frame out with, drawn from the frame's polygon. Corners stay
 * sharp; points that follow a curve (around a balloon) become a smooth curve through them; a
 * corner that turns inward (where a balloon meets the frame's edge) gets a small round fillet.
 * On screen only: the file keeps its points.
 */
object Outline {
    /** Points of every outline: the same for all frames, so that two outlines morph point by point. */
    const val POINTS = 512

    /** A vertex turning more than this many degrees is a corner. */
    private const val CORNER_TURN = 50.0

    /** A vertex next to a side longer than this share of the perimeter is a corner too. */
    private const val LONG_SIDE = 0.08f

    /** The fillet's reach along each side: a share of the frame's diagonal, at most 40 % of a side. */
    private const val FILLET = 0.01f
    private const val MIN_FILLET = 4f

    /** Curves are sampled about every [STEP] image pixels before the even walk. */
    private const val STEP = 2f

    private val cache = HashMap<Polygon, List<Offset>>()

    /**
     * The outline walked at even steps, always in the same direction and starting at the corner
     * nearest the top-left, with every sharp corner exactly on one of the points.
     */
    fun of(poly: Polygon): List<Offset> {
        cache[poly]?.let { return it }
        if (cache.size > 256) cache.clear()
        return walk(shape(poly), POINTS, Offset(poly.minX.toFloat(), poly.minY.toFloat())).also { cache[poly] = it }
    }

    /** A closed path sampled densely; [corners] are the indices of its sharp corners. */
    internal class Dense(val points: List<Offset>, val corners: List<Int>)

    internal fun shape(poly: Polygon): Dense {
        val raw = poly.points.map { Offset(it.x.toFloat(), it.y.toFloat()) }
        var pts = raw.filterIndexed { k, p -> p != raw[(k + raw.size - 1) % raw.size] }.ifEmpty { listOf(raw[0]) }
        if (poly.signedArea < 0) pts = pts.reversed()
        val n = pts.size
        if (n < 3) return Dense(pts, pts.indices.toList())

        val side = FloatArray(n) { (pts[(it + 1) % n] - pts[it]).getDistance() } // side k: pts[k] → pts[k + 1]
        val perimeter = side.sum()
        val diagonal = hypot((poly.maxX - poly.minX).toFloat(), (poly.maxY - poly.minY).toFloat())
        val reach = max(MIN_FILLET, FILLET * diagonal)
        val corner = BooleanArray(n)
        val tangent = Array(n) { Offset.Zero }
        val trim = FloatArray(n) // how far the fillet at each vertex reaches along both sides
        for (k in 0 until n) {
            val before = (k + n - 1) % n
            val e1 = pts[k] - pts[before]
            val e2 = pts[(k + 1) % n] - pts[k]
            val cross = e1.x * e2.y - e1.y * e2.x
            val turn = abs(atan2(cross, e1.x * e2.x + e1.y * e2.y)) * 180.0 / PI
            corner[k] = turn > CORNER_TURN || max(side[before], side[k]) > LONG_SIDE * perimeter
            if (!corner[k]) tangent[k] = unit(unit(e1) + unit(e2))
            // Positive area: an inward corner turns the other way.
            else if (cross < 0f) trim[k] = min(reach, 0.4f * min(side[before], side[k]))
        }

        val out = ArrayList<Offset>()
        val corners = ArrayList<Int>()
        for (k in 0 until n) {
            val next = (k + 1) % n
            val p = pts[k]
            val q = pts[next]
            val dir = (q - p) / side[k]
            if (trim[k] > 0f) {
                // Fillet: from the incoming side to the outgoing one, pulled towards the corner.
                val inDir = (p - pts[(k + n - 1) % n]) / side[(k + n - 1) % n]
                val a = p - inDir * trim[k]
                val b = p + dir * trim[k]
                val steps = max(4, ceil(2 * trim[k] / STEP).toInt())
                for (s in 0..steps) {
                    val t = s.toFloat() / steps
                    out += a * ((1 - t) * (1 - t)) + p * (2 * t * (1 - t)) + b * (t * t)
                }
            } else {
                if (corner[k]) corners += out.size
                out += p
            }
            if (corner[k] && corner[next]) continue // a straight side
            val start = p + dir * trim[k]
            val end = q - dir * trim[next]
            val third = (end - start).getDistance() / 3f
            val c0 = start + (if (corner[k]) dir else tangent[k]) * third
            val c1 = end - (if (corner[next]) dir else tangent[next]) * third
            val steps = max(2, ceil(3 * third / STEP).toInt())
            for (s in 1 until steps) out += cubic(start, c0, c1, end, s.toFloat() / steps)
        }
        return Dense(out, corners)
    }

    /**
     * [count] points along [dense] at even steps, starting at the corner nearest [topLeft]; the
     * steps stretch a little between corners so that each corner is one of the points.
     */
    internal fun walk(dense: Dense, count: Int, topLeft: Offset): List<Offset> {
        val all = dense.points
        val n = all.size
        val near = { i: Int -> (all[i] - topLeft).getDistanceSquared() }
        val first = dense.corners.minByOrNull(near) ?: all.indices.minBy(near)
        val pts = all.drop(first) + all.take(first)
        val at = FloatArray(n + 1) // distance walked to each point, then the whole loop
        for (k in 0 until n) at[k + 1] = at[k] + (pts[(k + 1) % n] - pts[k]).getDistance()
        val total = at[n]
        if (total <= 0f) return List(count) { pts[0] }

        // Stops: the start, each corner, the end; points are shared out between them by length.
        val stops = buildList {
            add(0f)
            if (dense.corners.size <= count / 4) for (c in dense.corners.map { (it - first + n) % n }.sorted()) if (c != 0) add(at[c])
            add(total)
        }
        val gaps = stops.zipWithNext { a, b -> b - a }
        val shares = IntArray(gaps.size) { 1 }
        val ideal = gaps.map { it / total * (count - gaps.size) }
        ideal.forEachIndexed { k, v -> shares[k] += v.toInt() }
        ideal.indices.sortedByDescending { ideal[it] - ideal[it].toInt() }.take(count - shares.sum()).forEach { shares[it]++ }

        val out = ArrayList<Offset>(count)
        var seg = 0
        for ((g, gap) in gaps.withIndex()) {
            for (m in 0 until shares[g]) {
                val d = stops[g] + gap * m / shares[g]
                while (seg < n - 1 && at[seg + 1] < d) seg++
                val a = pts[seg]
                val b = pts[(seg + 1) % n]
                val len = at[seg + 1] - at[seg]
                out += if (len > 0f) a + (b - a) * ((d - at[seg]) / len).coerceIn(0f, 1f) else a
            }
        }
        return out
    }

    private fun unit(v: Offset): Offset = v.getDistance().let { if (it > 0f) v / it else Offset.Zero }

    private fun cubic(p0: Offset, p1: Offset, p2: Offset, p3: Offset, t: Float): Offset {
        val u = 1 - t
        return p0 * (u * u * u) + p1 * (3 * u * u * t) + p2 * (3 * u * t * t) + p3 * (t * t * t)
    }
}
