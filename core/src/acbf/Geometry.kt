package tessera.acbf

import kotlin.math.abs
import kotlin.math.roundToInt

/** A point in background-image pixels. */
data class Point(val x: Int, val y: Int)

/** A closed polygon, as used by frames, text areas and jumps. */
data class Polygon(val points: List<Point>) {
    init {
        require(points.isNotEmpty()) { "a polygon needs points" }
    }

    val minX: Int get() = points.minOf { it.x }
    val minY: Int get() = points.minOf { it.y }
    val maxX: Int get() = points.maxOf { it.x }
    val maxY: Int get() = points.maxOf { it.y }

    /** True when the polygon is an axis-aligned rectangle given by its four corners. */
    val isRectangle: Boolean
        get() = points.size == 4 && points.all { (it.x == minX || it.x == maxX) && (it.y == minY || it.y == maxY) } &&
            points.toSet().size == 4

    /** Signed shoelace area; positive or negative depending on winding. */
    val signedArea: Double
        get() {
            var sum = 0L
            for (k in points.indices) {
                val a = points[k]
                val b = points[(k + 1) % points.size]
                sum += a.x.toLong() * b.y - b.x.toLong() * a.y
            }
            return sum / 2.0
        }

    val area: Double get() = abs(signedArea)

    fun contains(x: Double, y: Double): Boolean {
        var inside = false
        var j = points.size - 1
        for (k in points.indices) {
            val a = points[k]
            val b = points[j]
            if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y).toDouble() + a.x) inside = !inside
            j = k
        }
        return inside
    }

    fun translated(dx: Int, dy: Int) = Polygon(points.map { Point(it.x + dx, it.y + dy) })

    /**
     * The ACBF `points` attribute: integers, one space between points, no trailing space.
     * ACBF Viewer splits on single spaces and calls int(), so nothing else is safe.
     */
    fun format(): String = points.joinToString(" ") { "${it.x},${it.y}" }

    companion object {
        fun rectangle(x0: Int, y0: Int, x1: Int, y1: Int): Polygon {
            val l = minOf(x0, x1)
            val r = maxOf(x0, x1)
            val t = minOf(y0, y1)
            val b = maxOf(y0, y1)
            return Polygon(listOf(Point(l, t), Point(r, t), Point(r, b), Point(l, b)))
        }

        /**
         * Reads a `points` attribute leniently: any whitespace or stray commas between pairs,
         * decimals rounded. Returns null when no point can be read.
         */
        fun parse(value: String?): Polygon? {
            if (value == null) return null
            val numbers = Regex("-?\\d+(?:\\.\\d+)?").findAll(value).map { it.value.toDouble().roundToInt() }.toList()
            if (numbers.size < 2) return null
            return Polygon(numbers.chunked(2).filter { it.size == 2 }.map { Point(it[0], it[1]) })
        }
    }
}
