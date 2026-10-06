import androidx.compose.ui.geometry.Offset
import tessera.acbf.AcbfDocument
import tessera.acbf.Comic
import tessera.acbf.EmptyContainer
import tessera.acbf.textAreas
import tessera.acbf.textLayer
import tessera.editor.FrameTool
import tessera.editor.Session
import tessera.editor.TextShapes
import tessera.editor.Tool
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TextToolTest {
    private fun FrameTool.dragRect(x0: Float, y0: Float, x1: Float, y1: Float) {
        select(Tool.Rectangle); press(Offset(x0, y0), 1f); move(Offset(x1, y1), 1f); release(1f)
    }

    @Test
    fun drawsMovesTypesAndUndoesTextAreasLeavingFramesAlone() {
        val doc = AcbfDocument.create("Test", listOf("cover.jpg", "p1.jpg"))
        val session = Session(Comic(doc, EmptyContainer, "t.acbf", generated = false), "t.cbz")
        val original = doc.write()
        val frames = FrameTool(session).apply { imageWidth = 1000; imageHeight = 1500 }
        val texts = FrameTool(session, TextShapes(session) { "fr" }).apply { imageWidth = 1000; imageHeight = 1500 }

        frames.dragRect(5f, 5f, 500f, 700f)
        texts.dragRect(100f, 100f, 300f, 200f)
        assertEquals(1, session.page.frames.size)
        assertEquals("100,100 300,100 300,200 100,200", session.page.textAreas("fr").single().element["points"])
        assertEquals(1, texts.created)
        assertEquals(0, texts.selected)

        // Typing a text, letter by letter, is one undo step.
        for (t in listOf("B", "Bo", "Bon", "Bonjour")) session.editTexts("t") { it.textAreas("fr")[0].setText(t) }
        assertEquals("Bonjour", session.page.textAreas("fr")[0].text)

        // Moving the area: the frame snaps nothing of it away, the area moves.
        texts.select(Tool.Select)
        texts.press(Offset(150f, 150f), 1f); texts.move(Offset(160f, 170f), 1f); texts.release(1f)
        assertEquals("110,120 310,120 310,220 110,220", session.page.textAreas("fr")[0].element["points"])

        session.undo() // the move
        assertEquals("100,100 300,100 300,200 100,200", session.page.textAreas("fr")[0].element["points"])
        session.undo() // the typing
        assertEquals("", session.page.textAreas("fr")[0].text)
        session.redo(); assertEquals("Bonjour", session.page.textAreas("fr")[0].text)
        session.undo(); session.undo() // the text and the area
        assertNull(session.page.textLayer("fr"))
        assertEquals(1, session.page.frames.size)
        session.undo() // the frame
        assertContentEquals(original, doc.write())
    }

    @Test
    fun deletingTheLastAreaRemovesTheLayer() {
        val doc = AcbfDocument.create("Test", listOf("cover.jpg", "p1.jpg"))
        val session = Session(Comic(doc, EmptyContainer, "t.acbf", generated = false), "t.cbz")
        val texts = FrameTool(session, TextShapes(session) { "en" }).apply { imageWidth = 1000; imageHeight = 1500 }
        texts.dragRect(100f, 100f, 300f, 200f)
        texts.delete()
        assertNull(session.page.textLayer("en"))
        session.undo()
        assertEquals(1, session.page.textAreas("en").size)
    }
}
