import androidx.compose.ui.geometry.Offset
import tessera.acbf.AcbfDocument
import tessera.acbf.Comic
import tessera.acbf.EmptyContainer
import tessera.acbf.Polygon
import tessera.editor.FrameTool
import tessera.editor.Session
import tessera.editor.Tool
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrameToolTest {
    private fun setup(): Pair<Session, FrameTool> {
        val doc = AcbfDocument.create("Test", listOf("cover.jpg", "p1.jpg", "p2.jpg"))
        val session = Session(Comic(doc, EmptyContainer, "t.acbf", generated = false), "t.cbz")
        val tool = FrameTool(session).apply { imageWidth = 1000; imageHeight = 1500 }
        return session to tool
    }

    private fun FrameTool.dragRect(x0: Float, y0: Float, x1: Float, y1: Float, scale: Float = 1f) {
        select(Tool.Rectangle); press(Offset(x0, y0), scale); move(Offset(x1, y1), scale); release(scale)
    }

    @Test
    fun drawsRectanglesThatSnapToTheImageEdge() {
        val (session, tool) = setup()
        tool.dragRect(5f, 4f, 480.4f, 700.6f)
        assertEquals("0,0 480,0 480,701 0,701", session.page.frames.single().element["points"])
        assertEquals(Tool.Select, tool.tool)
        assertEquals(0, tool.selected)
        // A second frame snaps onto the first one's edges.
        tool.dragRect(486f, 3f, 995f, 697f)
        assertEquals("480,0 1000,0 1000,701 480,701", session.page.frames[1].element["points"])
    }

    @Test
    fun ignoresTinyAccidentalDrags() {
        val (session, tool) = setup()
        tool.dragRect(100f, 100f, 103f, 400f)
        assertTrue(session.page.frames.isEmpty())
        assertFalse(session.dirty)
    }

    @Test
    fun drawsPolygonsPointByPoint() {
        val (session, tool) = setup()
        tool.select(Tool.Polygon)
        for (p in listOf(Offset(100f, 100f), Offset(400f, 120f), Offset(380f, 500f), Offset(90f, 480f))) tool.press(p, 1f)
        tool.delete() // the last point goes
        tool.press(Offset(120f, 470f), 1f)
        tool.press(Offset(103f, 104f), 1f) // on the first point: closes
        assertEquals("100,100 400,120 380,500 120,470", session.page.frames.single().element["points"])
    }

    @Test
    fun movesAFrameAndUndoesInOneStep() {
        val (session, tool) = setup()
        tool.dragRect(100f, 100f, 300f, 300f)
        val original = session.document.write()
        tool.press(Offset(200f, 200f), 1f)
        for (k in 1..10) tool.move(Offset(200f + k * 10, 200f + k * 5), 1f)
        tool.release(1f)
        assertEquals(Polygon.rectangle(200, 150, 400, 350), session.page.frames[0].polygon)
        session.undo()
        assertContentEquals(original, session.document.write())
        session.redo()
        assertEquals(Polygon.rectangle(200, 150, 400, 350), session.page.frames[0].polygon)
    }

    @Test
    fun dragsCornersAddsAndRemovesPoints() {
        val (session, tool) = setup()
        tool.dragRect(100f, 100f, 300f, 300f)
        // Corner drag at half zoom: handles are hit within 7 screen pixels = 14 image pixels.
        tool.press(Offset(310f, 108f), 0.5f); tool.move(Offset(350f, 80f), 0.5f); tool.release(0.5f)
        assertEquals("100,100 350,80 300,300 100,300", session.page.frames[0].element["points"])
        // The middle of the bottom side adds a point and drags it.
        tool.press(Offset(200f, 300f), 1f); tool.move(Offset(200f, 340f), 1f); tool.release(1f)
        assertEquals(5, session.page.frames[0].polygon!!.points.size)
        // Alt-click removes it again.
        tool.press(Offset(200f, 340f), 1f, alt = true)
        assertEquals("100,100 350,80 300,300 100,300", session.page.frames[0].element["points"])
    }

    @Test
    fun ordersByClickingAndAutomatically() {
        val (session, tool) = setup()
        tool.dragRect(520f, 20f, 980f, 400f)   // top right
        tool.dragRect(20f, 20f, 480f, 400f)    // top left
        tool.dragRect(20f, 450f, 980f, 900f)   // bottom
        val tops = { session.page.frames.map { it.polygon!!.minX to it.polygon!!.minY } }
        tool.autoOrder()
        assertEquals(listOf(20 to 20, 520 to 20, 20 to 450), tops())
        tool.rightToLeft = true
        tool.autoOrder()
        assertEquals(listOf(520 to 20, 20 to 20, 20 to 450), tops())
        tool.select(Tool.Order)
        tool.press(Offset(500f, 600f), 1f) // bottom first
        tool.press(Offset(700f, 100f), 1f) // then the top-right frame (now first)
        tool.confirm()
        assertEquals(listOf(20 to 450, 520 to 20, 20 to 20), tops())
        // Each reordering is one undo step.
        session.undo()
        assertEquals(listOf(520 to 20, 20 to 20, 20 to 450), tops())
    }

    @Test
    fun deletesAndNudges() {
        val (session, tool) = setup()
        tool.dragRect(100f, 100f, 300f, 300f)
        tool.nudge(10, -1)
        assertEquals(Polygon.rectangle(110, 99, 310, 299), session.page.frames[0].polygon)
        tool.delete()
        assertTrue(session.page.frames.isEmpty())
        session.undo()
        assertEquals(1, session.page.frames.size)
    }
}
