import tessera.acbf.Point
import tessera.acbf.Polygon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeometryTest {
    @Test
    fun parsesPointsLeniently() {
        // The 1.1 specification itself shows a stray comma and double spaces.
        assertEquals(listOf(Point(10, 10), Point(20, 10), Point(10, 50), Point(20, 50)), Polygon.parse("10,10 20,10, 10,50 20,50")!!.points)
        assertEquals(listOf(Point(850, 75), Point(958, 137)), Polygon.parse(" 850,75  958,137 ")!!.points)
        assertEquals(listOf(Point(2, -3)), Polygon.parse("1.6,-2.6")!!.points)
        assertNull(Polygon.parse(""))
        assertNull(Polygon.parse(null))
    }

    @Test
    fun formatsForAcbfViewer() {
        assertEquals("1,2 3,2 3,4 1,4", Polygon.rectangle(3, 4, 1, 2).format())
    }

    @Test
    fun measuresAndHitTests() {
        val r = Polygon.rectangle(0, 0, 10, 20)
        assertTrue(r.isRectangle)
        assertEquals(200.0, r.area)
        assertTrue(r.contains(5.0, 5.0))
        assertFalse(r.contains(11.0, 5.0))
        assertFalse(Polygon(listOf(Point(0, 0), Point(10, 0), Point(5, 9))).isRectangle)
    }
}
