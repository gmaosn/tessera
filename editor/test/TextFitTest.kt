import tessera.acbf.Point
import tessera.acbf.Polygon
import tessera.editor.TextFit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextFitTest {
    /** A tall oval balloon, centre (300, 400), half-axes 150 × 220, as hand-clicked points. */
    private val balloon = Polygon((0 until 24).map { k ->
        val a = 2 * PI * k / 24
        Point((300 + 150 * cos(a)).roundToInt(), (400 + 220 * sin(a)).roundToInt())
    })

    /** Every character 0.55 of the font size wide, as a plain sans-serif roughly is. */
    private val width = { w: String -> w.length * 55f }

    /** Half the oval's width at height y. */
    private fun halfWidth(y: Float): Float {
        val t = (y - 400) / 220
        return if (t * t >= 1f) 0f else 150 * sqrt(1 - t * t)
    }

    @Test
    fun everyLineStaysInsideTheShapeAtItsHeight() {
        val layout = TextFit.layout("Quelle est cette chose brillante?", balloon, 0, width, 30f)!!
        assertEquals("Quelle est cette chose brillante?", layout.lines.joinToString(" ") { it.text })
        val lh = layout.fontSize * TextFit.LEADING
        for (line in layout.lines) {
            val w = line.text.length * 0.55f * layout.fontSize
            // The narrowest part of the oval over the line's height.
            val room = minOf(halfWidth(line.top), halfWidth(line.top + lh)) * 2
            assertTrue(w <= room, "\"${line.text}\" is $w wide where the balloon has $room")
            assertTrue(line.top >= 180 && line.top + lh <= 620)
        }
        // And it is not timidly small: the text uses a fair part of the balloon.
        assertTrue(layout.fontSize > 40f, "font size ${layout.fontSize}")
    }

    @Test
    fun paragraphsStartNewLinesAndRotationTurnsTheRoom() {
        val wide = Polygon(listOf(Point(0, 0), Point(600, 0), Point(600, 100), Point(0, 100)))
        val flat = TextFit.layout("un\ndeux", wide, 0, width, 30f)!!
        assertEquals(listOf("un", "deux"), flat.lines.map { it.text })
        // Turned a quarter, the 600 px side becomes the height: the text can be much larger per line count.
        val upright = TextFit.layout("un deux trois quatre", wide, 90, width, 30f)!!
        assertTrue(upright.lines.size > 1)
    }

    @Test
    fun aWordWiderThanTheShapeStillComesOut() {
        val tiny = Polygon(listOf(Point(0, 0), Point(10, 0), Point(10, 10), Point(0, 10)))
        val layout = TextFit.layout("Anticonstitutionnellement", tiny, 0, width, 30f)
        assertEquals("Anticonstitutionnellement", layout!!.lines.single().text)
    }
}
