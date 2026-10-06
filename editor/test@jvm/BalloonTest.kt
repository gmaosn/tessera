import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toComposeImageBitmap
import tessera.acbf.AcbfDocument
import tessera.acbf.Comic
import tessera.acbf.EmptyContainer
import tessera.acbf.Point
import tessera.acbf.Polygon
import tessera.acbf.textAreas
import tessera.editor.Balloon
import tessera.editor.FrameTool
import tessera.editor.Session
import tessera.editor.TextShapes
import tessera.editor.Tool
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BalloonTest {
    /** [p] moved [by] pixels towards the polygon's centre: allows for the outline's simplification. */
    private fun Polygon.inward(p: Point, by: Double = 3.0): Pair<Double, Double> {
        val cx = (minX + maxX) / 2.0
        val cy = (minY + maxY) / 2.0
        val d = kotlin.math.hypot(p.x - cx, p.y - cy).coerceAtLeast(1.0)
        return (p.x - (p.x - cx) / d * by) to (p.y - (p.y - cy) / d * by)
    }

    /**
     * A page 1000 × 800: a frame (50,50)–(600,750) with a screentone-ish grey inside, and a balloon
     * centred (600, 250), 360 × 220, outline 6 px, spilling over the frame's right edge, with letters.
     */
    private fun page(open: Boolean = false): BufferedImage {
        val img = BufferedImage(1000, 800, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.WHITE; g.fillRect(0, 0, 1000, 800)
        g.color = Color(150, 150, 150); g.fillRect(50, 50, 550, 700)
        g.color = Color.BLACK; g.stroke = BasicStroke(5f); g.drawRect(50, 50, 550, 700)
        g.color = Color.WHITE; g.fillOval(420, 140, 360, 220)
        g.color = Color.BLACK; g.stroke = BasicStroke(6f)
        if (open) g.drawArc(420, 140, 360, 220, 20, 320) else g.drawOval(420, 140, 360, 220)
        g.font = java.awt.Font("SansSerif", java.awt.Font.BOLD, 28)
        g.drawString("HELLO THERE", 510, 240); g.drawString("FRIEND!", 540, 280)
        g.dispose()
        return img
    }

    @Test
    fun findsTheWholeInsideAndTheOutlinesWidth() {
        val found = assertNotNull(Balloon.find(page().toComposeImageBitmap(), 600, 300))
        assertTrue(found.stroke in 4..8, "stroke ${found.stroke}")
        // The inside: the ellipse within the outline (half-axes less half the outline).
        val expected = PI * (180 - 3) * (110 - 3)
        assertTrue(kotlin.math.abs(found.inner.area - expected) < expected * 0.06, "inner ${found.inner.area} vs $expected")
        assertTrue(found.outer.area > found.inner.area)
        // Fewer points than pixels: simplified.
        assertTrue(found.inner.points.size in 12..200, "${found.inner.points.size} points")
        // A click on a letter finds it too.
        assertNotNull(Balloon.find(page().toComposeImageBitmap(), 515, 232))
    }

    @Test
    fun anOpenBalloonIsNotFound() {
        assertNull(Balloon.find(page(open = true).toComposeImageBitmap(), 600, 300))
    }

    @Test
    fun theFrameGoesRoundTheSpillingPartAndKeepsItsOtherCorners() {
        val found = Balloon.find(page().toComposeImageBitmap(), 600, 300)!!
        val frame = Polygon(listOf(Point(50, 50), Point(600, 50), Point(600, 750), Point(50, 750)))
        val grown = assertNotNull(Balloon.around(frame, found.outer))
        for (corner in listOf(Point(50, 50), Point(600, 750), Point(50, 750), Point(600, 50))) assertTrue(corner in grown.points, "$corner kept")
        // Everything of the balloon is in the grown frame, nothing much beyond it.
        val outside = found.inner.points.count { p -> found.inner.inward(p).let { (x, y) -> !grown.contains(x, y) } }
        assertEquals(0, outside)
        assertTrue(grown.maxX in 775..786, "reaches ${grown.maxX}")
        // A balloon wholly inside the frame changes nothing.
        assertNull(Balloon.around(Polygon(listOf(Point(0, 0), Point(999, 0), Point(999, 799), Point(0, 799))), found.outer))
    }

    @Test
    fun theToolGrowsTheFrameAndFitsTextAreas() {
        val img = page().toComposeImageBitmap()
        val doc = AcbfDocument.create("Test", listOf("cover.png", "p1.png"))
        val session = Session(Comic(doc, EmptyContainer, "t.acbf", generated = false), "t.cbz")
        session.edit { it.addFrame(Polygon(listOf(Point(50, 50), Point(600, 50), Point(600, 750), Point(50, 750)))) }
        val finder = { p: Point -> Balloon.find(img, p.x, p.y) }
        val frames = FrameTool(session).apply { imageWidth = 1000; imageHeight = 800; balloonAt = finder }
        frames.select(Tool.Balloon)
        frames.press(Offset(700f, 300f), 1f) // in the part that spills over: the frame holding most of it is found
        assertTrue(session.page.frames.single().polygon!!.maxX > 770)
        session.undo()
        assertEquals(600, session.page.frames.single().polygon!!.maxX)

        val texts = FrameTool(session, TextShapes(session) { "fr" }).apply { imageWidth = 1000; imageHeight = 800; balloonAt = finder }
        texts.select(Tool.Rectangle); texts.press(Offset(500f, 200f), 1f); texts.move(Offset(560f, 260f), 1f); texts.release(1f)
        val drawn = session.page.textAreas("fr").single().polygon!!.area
        texts.select(Tool.Balloon)
        texts.press(Offset(530f, 230f), 1f) // inside the drawn area: it is fitted to the balloon
        assertEquals(1, session.page.textAreas("fr").size)
        assertTrue(session.page.textAreas("fr").single().polygon!!.area > drawn * 5)
        texts.press(Offset(100f, 400f), 1f) // the grey frame: no balloon
        assertEquals(tessera.editor.Strings.noBalloonHere, texts.message)
    }

    @Test
    fun aBalloonIntrudingOnTheNextFrameIsCutOutOfIt() {
        val img = page().toComposeImageBitmap()
        val doc = AcbfDocument.create("Test", listOf("cover.png", "p1.png"))
        val session = Session(Comic(doc, EmptyContainer, "t.acbf", generated = false), "t.cbz")
        val left = Polygon(listOf(Point(50, 50), Point(600, 50), Point(600, 750), Point(50, 750)))
        val right = Polygon(listOf(Point(615, 50), Point(950, 50), Point(950, 750), Point(615, 750)))
        session.edit { it.addFrame(left); it.addFrame(right) }
        val tool = FrameTool(session).apply { imageWidth = 1000; imageHeight = 800; balloonAt = { p -> Balloon.find(img, p.x, p.y) } }
        tool.select(Tool.Balloon)
        tool.press(Offset(560f, 300f), 1f) // most of the balloon is in the left frame: it owns it
        val (a, b) = session.page.frames.map { it.polygon!! }
        val balloon = Balloon.find(img, 600, 300)!!
        assertTrue(balloon.inner.points.all { p -> balloon.inner.inward(p).let { (x, y) -> a.contains(x, y) } })
        // The right frame keeps its corners and no longer shows any of the balloon or its outline.
        for (corner in right.points) assertTrue(corner in b.points, "$corner kept")
        assertEquals(0, balloon.outer.points.count { b.contains(it.x.toDouble(), it.y.toDouble()) })
        assertTrue(b.area < right.area && b.area > right.area * 0.7)
        assertEquals(0, tool.selected)
        // One click, one undo step.
        session.undo()
        assertEquals(listOf(left, right), session.page.frames.map { it.polygon })
    }
}
