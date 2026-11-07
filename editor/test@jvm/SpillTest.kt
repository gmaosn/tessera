import androidx.compose.ui.geometry.Offset
import tessera.acbf.Point
import tessera.acbf.Polygon
import tessera.editor.Outline
import tessera.editor.Spill
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpillTest {
    private val left = Polygon.rectangle(60, 60, 620, 840)
    private val right = Polygon.rectangle(660, 60, 1140, 840)

    /**
     * A page 1200 × 900, white paper: two frames with 5 px borders; a balloon centred (620, 250),
     * 360 × 200, outline 6 px, mostly in the left frame and spilling into the right one; a caption
     * box (100, 20)–(500, 130) spilling above the left frame into the margin; a dark tuft of hair
     * poking out of the left frame's bottom into the margin; and a white sail with an outline but
     * no lettering, inside the left frame.
     */
    private fun page(): IntArray {
        val img = BufferedImage(1200, 900, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.WHITE; g.fillRect(0, 0, 1200, 900)
        g.color = Color(0x8FB8C8); g.fillRect(60, 60, 560, 780)
        g.color = Color(0xD9B8C4); g.fillRect(660, 60, 480, 780)
        g.color = Color.BLACK; g.stroke = BasicStroke(5f); g.drawRect(60, 60, 560, 780); g.drawRect(660, 60, 480, 780)
        g.color = Color.WHITE; g.fillOval(200, 400, 150, 200)
        g.color = Color.BLACK; g.stroke = BasicStroke(4f); g.drawOval(200, 400, 150, 200)
        g.color = Color(0x303030); g.fillOval(290, 815, 80, 60)
        g.color = Color.WHITE; g.fillOval(440, 150, 360, 200)
        g.color = Color.BLACK; g.stroke = BasicStroke(6f); g.drawOval(440, 150, 360, 200)
        g.color = Color.WHITE; g.fillRect(100, 20, 400, 110)
        g.color = Color.BLACK; g.stroke = BasicStroke(5f); g.drawRect(100, 20, 400, 110)
        g.font = java.awt.Font("SansSerif", java.awt.Font.BOLD, 28)
        g.drawString("AREN'T WE SUPPOSED", 490, 240); g.drawString("TO REPORT IT?", 530, 280)
        g.drawString("THE NEXT MORNING,", 130, 65); g.drawString("AT THE HARBOUR.", 130, 105)
        g.dispose()
        return IntArray(1200 * 900).also { img.getRGB(0, 0, 1200, 900, it, 0, 1200) }
    }

    private fun List<Offset>.contains(x: Float, y: Float): Boolean {
        var inside = false
        var j = size - 1
        for (k in indices) {
            val a = this[k]
            val b = this[j]
            if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = k
        }
        return inside
    }

    private fun List<Offset>.hasCorner(p: Point) = any { kotlin.math.abs(it.x - p.x) < 0.01f && kotlin.math.abs(it.y - p.y) < 0.01f }

    @Test
    fun onlyBalloonsWithLetteringAreFound() {
        val ink = Spill.analyse(page(), 1200, 900)
        assertEquals(2, ink.balloons.size, ink.balloons.map { "${it.inner.minX},${it.inner.minY}-${it.inner.maxX},${it.inner.maxY}" }.toString())
        val balloon = ink.balloons.single { it.inner.minY > 100 }
        val caption = ink.balloons.single { it.inner.minY < 100 }
        assertTrue(balloon.stroke in 4..8 && caption.stroke in 3..7)
        assertTrue(balloon.letters >= 10 && caption.letters >= 10)
        // The halo runs outside the outline with paper round it; the inside just onto the outline.
        assertTrue(balloon.halo.maxX in 804..820, "halo reaches ${balloon.halo.maxX}")
        assertTrue(balloon.inner.maxX in 795..800)
        assertTrue(balloon.halo.points.size > 150 && balloon.halo.points.size > 3 * balloon.clear.points.size, "traced closely: ${balloon.halo.points.size} points")
        // The paper: the margins, not the frames' grounds.
        assertTrue(ink.paper[10 * 1200 + 10] && !ink.paper[300 * 1200 + 300] && !ink.paper[300 * 1200 + 900])
    }

    @Test
    fun framesAreCutWithWhatSpillsOutAndWithoutWhatIntrudes() {
        val ink = Spill.analyse(page(), 1200, 900)
        val (a, b) = Spill.cuts(ink, listOf(left, right))
        assertEquals(Outline.POINTS, a.outline.size)
        assertTrue(!a.plain && !b.plain)
        // The left frame: the balloon's spilling part, the caption's top, the hair, each with paper round it.
        assertTrue(a.maxX in 806f..822f, "balloon reaches ${a.maxX}")
        assertTrue(a.minY in 0f..16f, "caption reaches ${a.minY}")
        assertTrue(a.maxY in 876f..892f, "hair reaches ${a.maxY}")
        assertTrue(a.outline.contains(700f, 250f) && a.outline.contains(300f, 30f) && a.outline.contains(330f, 865f))
        // Its free corners stay exact; the corner under the caption's box side is gone.
        for (c in listOf(Point(620, 60), Point(620, 840), Point(60, 840))) assertTrue(a.outline.hasCorner(c), "corner $c")
        assertTrue(a.outline.hasCorner(Point(60, 60)))
        // The right frame: nothing of the balloon, corners kept, the rest untouched.
        assertTrue(!b.outline.contains(700f, 250f) && !b.outline.contains(665f, 250f))
        assertTrue(b.outline.contains(900f, 250f) && b.outline.contains(700f, 500f))
        for (c in right.points) assertTrue(b.outline.hasCorner(c), "corner $c")
        assertTrue(b.minX >= 659f && b.maxX <= 1141f && b.minY >= 59f && b.maxY <= 841f)
        // The balloon's cut follows the ellipse at a steady distance: outline plus paper, about 10 px.
        val centre = Offset(620f, 250f)
        val arc = a.outline.filter { it.x > 640f && it.y in 170f..330f }
        assertTrue(arc.size > 20)
        for (p in arc) {
            val d = p - centre
            val r = kotlin.math.sqrt(d.x * d.x / (190f * 190f) + d.y * d.y / (110f * 110f))
            assertTrue(r in 0.97f..1.06f, "at $p: $r of the ellipse")
        }
    }

    @Test
    fun nothingSpillingLeavesTheFrameAsItIs() {
        val ink = Spill.analyse(page(), 1200, 900)
        // A frame holding everything inside it, far from the balloon and the margins' ink.
        val quiet = Polygon.rectangle(700, 400, 1100, 800)
        val (cut) = Spill.cuts(ink, listOf(quiet))
        assertTrue(cut.plain)
        assertEquals(Outline.of(quiet), cut.outline)
        // The whole page as one frame: nothing outside it.
        assertTrue(Spill.cuts(ink, listOf(Polygon.rectangle(0, 0, 1200, 900))).single().plain)
        // No image: the frames as they are.
        assertTrue(Spill.cuts(null, listOf(left, right)).all { it.plain })
    }
}
