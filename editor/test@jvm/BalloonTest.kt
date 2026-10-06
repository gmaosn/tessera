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
    private fun page(open: Int = 0): BufferedImage {
        val img = BufferedImage(1000, 800, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.WHITE; g.fillRect(0, 0, 1000, 800)
        g.color = Color(150, 150, 150); g.fillRect(50, 50, 550, 700)
        g.color = Color.BLACK; g.stroke = BasicStroke(5f); g.drawRect(50, 50, 550, 700)
        g.color = Color.WHITE; g.fillOval(420, 140, 360, 220)
        g.color = Color.BLACK; g.stroke = BasicStroke(6f)
        if (open > 0) g.drawArc(420, 140, 360, 220, open / 2, 360 - open) else g.drawOval(420, 140, 360, 220)
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
    fun aBalloonOpenOnANarrowSideIsClosedThereAndAWideOpenOneIsNotFound() {
        // 40° of the outline missing on the right (a 75 px opening, as across a gutter): closed off.
        val narrow = assertNotNull(Balloon.find(page(open = 40).toComposeImageBitmap(), 600, 300))
        assertTrue(narrow.inner.maxX in 760..800, "reaches ${narrow.inner.maxX}")
        assertTrue(narrow.inner.minY > 120 && narrow.inner.maxY < 380)
        // Half the outline missing: no balloon to speak of.
        assertNull(Balloon.find(page(open = 180).toComposeImageBitmap(), 600, 300))
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

    /**
     * A wide balloon across two stacked frames, mostly in the lower one; the upper frame's bottom
     * edge was traced by hand round the balloon's top, wobbling. One click gives regular outlines.
     */
    @Test
    fun handTracingAlongTheBalloonIsReplacedByItsOutline() {
        val img = BufferedImage(1000, 800, BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            color = Color.WHITE; fillRect(0, 0, 1000, 800)
            color = Color(0xE0C080); fillRect(50, 50, 900, 350)
            color = Color(0x80B0D0); fillRect(50, 415, 900, 365)
            color = Color.WHITE; fillOval(200, 350, 600, 240)
            color = Color.BLACK; stroke = BasicStroke(5f); drawOval(200, 350, 600, 240)
            font = java.awt.Font("SansSerif", java.awt.Font.BOLD, 30); drawString("WHERE DOES THIS GO?", 320, 470)
            dispose()
        }
        val bitmap = img.toComposeImageBitmap()
        // The upper frame: its bottom edge dips round the balloon's top, by hand, ±6 px off.
        val random = java.util.Random(7)
        val dip = (0..16).map { k ->
            val a = PI + PI * (k + 1) / 18 // the top half of the ellipse, left to right
            Point((500 + 304 * kotlin.math.cos(a) + random.nextInt(13) - 6).toInt(), (470 + 124 * kotlin.math.sin(a) + random.nextInt(13) - 6).toInt())
        }.filter { it.y < 400 }
        val upper = Polygon(listOf(Point(50, 50), Point(950, 50), Point(950, 400)) + dip.reversed() + listOf(Point(50, 400)))
        val lower = Polygon(listOf(Point(50, 415), Point(950, 415), Point(950, 780), Point(50, 780)))
        val doc = AcbfDocument.create("Test", listOf("cover.png", "p1.png"))
        val session = Session(Comic(doc, EmptyContainer, "t.acbf", generated = false), "t.cbz")
        session.edit { it.addFrame(upper); it.addFrame(lower) }
        val tool = FrameTool(session).apply { imageWidth = 1000; imageHeight = 800; balloonAt = { p -> Balloon.find(bitmap, p.x, p.y) } }
        tool.select(Tool.Balloon)
        tool.press(Offset(500f, 520f), 1f)
        val found = Balloon.find(bitmap, 500, 520)!!
        val (top, bottom) = session.page.frames.map { it.polygon!! }
        for (c in listOf(Point(50, 50), Point(950, 50), Point(950, 400), Point(50, 400))) assertTrue(c in top.points, "$c kept")
        for (c in lower.points) assertTrue(c in bottom.points, "$c kept")
        // No hand-traced point is left: every other point of the upper frame lies on the cut round the balloon.
        val others = top.points.filter { it !in upper.points.take(3) && it != Point(50, 400) }
        assertTrue(others.none { it in dip }, "hand points left: ${others.filter { it in dip }}")
        assertEquals(0, found.outer.points.count { top.contains(it.x.toDouble(), it.y.toDouble()) })
        // The lower frame holds the whole balloon.
        assertTrue(found.inner.points.all { p -> found.inner.inward(p).let { (x, y) -> bottom.contains(x, y) } })
    }

    /** A scanned outline with light gaps a pixel or two wide is still a closed balloon. */
    @Test
    fun smallGapsInAScannedOutlineAreBridged() {
        val img = page()
        // Gaps across the outline, 2 px wide, as a scan or JPEG leaves them.
        for ((x, y) in listOf(600 to 140, 420 to 250, 700 to 349)) for (dx in 0..1) for (dy in -6..6) {
            img.setRGB(x + dx, y + dy, 0xFFFFFF); img.setRGB(x + dy, y + dx, 0xFFFFFF)
        }
        val found = assertNotNull(Balloon.find(img.toComposeImageBitmap(), 600, 300))
        val expected = PI * (180 - 3) * (110 - 3)
        assertTrue(kotlin.math.abs(found.inner.area - expected) < expected * 0.08, "inner ${found.inner.area} vs $expected")
    }

    /** A second click on the same balloon gives it to the other frame. */
    @Test
    fun clickingAgainGivesTheBalloonToTheOtherFrame() {
        val img = page().toComposeImageBitmap()
        val doc = AcbfDocument.create("Test", listOf("cover.png", "p1.png"))
        val session = Session(Comic(doc, EmptyContainer, "t.acbf", generated = false), "t.cbz")
        val left = Polygon(listOf(Point(50, 50), Point(600, 50), Point(600, 750), Point(50, 750)))
        val right = Polygon(listOf(Point(615, 50), Point(950, 50), Point(950, 750), Point(615, 750)))
        session.edit { it.addFrame(left); it.addFrame(right) }
        val tool = FrameTool(session).apply { imageWidth = 1000; imageHeight = 800; balloonAt = { p -> Balloon.find(img, p.x, p.y) } }
        tool.select(Tool.Balloon)
        tool.press(Offset(560f, 300f), 1f)
        assertEquals(0, tool.selected)
        tool.press(Offset(560f, 300f), 1f)
        assertEquals(1, tool.selected)
        val (a, b) = session.page.frames.map { it.polygon!! }
        val found = Balloon.find(img, 600, 300)!!
        assertEquals(0, found.outer.points.count { a.contains(it.x.toDouble(), it.y.toDouble()) }, "left frame leaves it out")
        assertTrue(found.inner.points.all { p -> found.inner.inward(p).let { (x, y) -> b.contains(x, y) } }, "right frame holds it")
        for (c in left.points) assertTrue(c in a.points, "$c kept")
        for (c in right.points) assertTrue(c in b.points, "$c kept")
        // And back again.
        tool.press(Offset(560f, 300f), 1f)
        assertEquals(0, tool.selected)
    }
}
