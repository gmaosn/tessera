import androidx.compose.ui.geometry.Offset
import tessera.acbf.Point
import tessera.acbf.Polygon
import tessera.editor.Outline
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutlineTest {
    private fun near(a: Offset, b: Offset) = (a - b).getDistance() < 1e-2f

    /** A 1000 × 1500 frame whose right side bulges round a balloon of radius 200, centred at (1000, 750). */
    private fun balloonFrame(): Polygon {
        val arc = (0..12).map { k ->
            val a = -PI / 2 + PI * k / 12
            Point((1000 + 200 * cos(a)).roundToInt(), (750 + 200 * sin(a)).roundToInt())
        }
        return Polygon(listOf(Point(0, 0), Point(1000, 0)) + arc + listOf(Point(1000, 1500), Point(0, 1500)))
    }

    @Test
    fun rectanglesKeepTheirCornersExactly() {
        val r = Polygon.rectangle(10, 20, 410, 920)
        val out = Outline.of(r)
        assertEquals(Outline.POINTS, out.size)
        assertTrue(near(out[0], Offset(10f, 20f)), "starts at the top-left corner")
        for (c in listOf(Offset(410f, 20f), Offset(410f, 920f), Offset(10f, 920f))) assertTrue(out.any { near(it, c) }, "corner $c")
        // Every point on the sides: no cut corners.
        assertTrue(out.all { abs(it.x - 10f) < 1e-2f || abs(it.x - 410f) < 1e-2f || abs(it.y - 20f) < 1e-2f || abs(it.y - 920f) < 1e-2f })
    }

    @Test
    fun slantedSidesStayStraight() {
        // Turns of 45° only, but long sides: corners all the same.
        val p = Polygon(listOf(Point(300, 0), Point(700, 0), Point(1000, 300), Point(1000, 700), Point(700, 1000), Point(300, 1000), Point(0, 700), Point(0, 300)))
        val out = Outline.of(p)
        for (c in p.points) assertTrue(out.any { near(it, Offset(c.x.toFloat(), c.y.toFloat())) }, "corner $c")
    }

    @Test
    fun balloonsBecomeRound() {
        val out = Outline.of(balloonFrame())
        val centre = Offset(1000f, 750f)
        val onArc = out.filter { it.x > 1030f }
        assertTrue(onArc.size > 30)
        // Closer to the circle than the 13 traced points' own rounding and chords allow.
        val worst = onArc.maxOf { abs((it - centre).getDistance() - 200f) }
        assertTrue(worst < 1.5f, "worst gap to the circle: $worst")
    }

    @Test
    fun inwardCornersGetAFillet() {
        val out = Outline.of(balloonFrame())
        for (junction in listOf(Offset(1000f, 550f), Offset(1000f, 950f))) {
            val gap = out.minOf { (it - junction).getDistance() }
            assertTrue(gap in 2f..12f, "fillet at $junction: $gap px from the corner")
        }
        // Outward corners stay sharp.
        assertTrue(out.any { near(it, Offset(1000f, 1500f)) })
    }

    @Test
    fun sameCountForEveryShape() {
        val shapes = listOf(
            Polygon(listOf(Point(5, 5))),
            Polygon(listOf(Point(0, 0), Point(100, 0))),
            Polygon(listOf(Point(0, 0), Point(0, 0), Point(100, 0), Point(100, 80), Point(0, 80), Point(0, 0))),
            Polygon.rectangle(0, 0, 400, 300).let { Polygon(it.points.reversed()) },
            balloonFrame(),
        )
        for (s in shapes) assertEquals(Outline.POINTS, Outline.of(s).size, "$s")
    }
}
