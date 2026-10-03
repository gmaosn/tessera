import tessera.acbf.AcbfDocument
import tessera.acbf.Polygon
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real ACBF files from the official sample books (fixtures/acbf-xml). */
class CorpusTest {
    private val corpus: List<File> = run {
        var dir = File("").absoluteFile
        while (!File(dir, "project.yaml").exists()) dir = dir.parentFile
        File(dir, "fixtures/acbf-xml").listFiles { f -> f.name.endsWith(".acbf") }!!.sortedBy { it.name }
    }

    @Test
    fun everyFileIsWrittenBackByteForByte() {
        assertTrue(corpus.size >= 8, "corpus missing")
        for (f in corpus) {
            val bytes = f.readBytes()
            assertContentEquals(bytes, AcbfDocument.parse(bytes).write(), f.name)
        }
    }

    @Test
    fun readsPagesAndFrames() {
        val counts = corpus.associate { f ->
            val doc = AcbfDocument.parse(f.readBytes())
            f.name to (doc.pages.size to doc.pages.sumOf { it.frames.size })
        }
        // The cover counts as a page, as in ACBF Viewer.
        assertEquals(24 to 151, counts["Doctorow, Cory - Craphound-1.1.acbf"])
        assertEquals(63 to 0, counts["Almossawi, Ali - Illustrated Book of Bad Arguments.acbf"])
        assertEquals(360 to 0, counts["NYC2123.acbf"])
        for (f in corpus) {
            val doc = AcbfDocument.parse(f.readBytes())
            for (page in doc.pages) for (frame in page.frames) assertTrue(frame.polygon != null, "${f.name}: unreadable frame")
        }
    }

    @Test
    fun editingOneFrameChangesOnlyItsPointsAttribute() {
        val f = corpus.first { it.name.startsWith("Revoy") }
        val original = f.readText()
        val doc = AcbfDocument.parse(f.readBytes())
        val frame = doc.pages[1].frames[0]
        val before = frame.element["points"]!!
        frame.polygon = Polygon.rectangle(10, 20, 300, 400)
        val after = doc.write().decodeToString()
        assertEquals(original.replaceFirst("points=\"$before\"", "points=\"10,20 300,20 300,400 10,400\""), after)
    }

    @Test
    fun addedAndRemovedFramesLeaveTheRestIntact() {
        val f = corpus.first { it.name.startsWith("Revoy") }
        val original = f.readBytes()
        val doc = AcbfDocument.parse(original)
        val page = doc.pages[3]
        val n = page.frames.size
        val added = page.addFrame(Polygon.rectangle(1, 2, 3, 4))
        assertEquals(n + 1, page.frames.size)
        assertEquals(added, page.frames.last())
        // Reparse: the written file is valid and holds the new frame.
        val reparsed = AcbfDocument.parse(doc.write())
        assertEquals("1,2 3,2 3,4 1,4", reparsed.pages[3].frames.last().element["points"])
        page.removeFrame(added)
        assertContentEquals(original, doc.write())
    }

    @Test
    fun restoringAnEarlierStateGivesBackTheSameBytes() {
        for (name in listOf("Revoy", "Purple", "Doctorow, Cory - Craphound-1.1", "NYC2123")) {
            val f = corpus.first { it.name.startsWith(name) }
            val original = f.readBytes()
            val doc = AcbfDocument.parse(original)
            val page = doc.pages.first { it.frames.size >= 3 || name == "NYC2123" }
            val state = page.frameState()
            // A mix of everything the editor does.
            page.addFrame(Polygon.rectangle(1, 1, 9, 9), 0)
            page.frames.getOrNull(2)?.let { page.removeFrame(it) }
            page.frames.getOrNull(1)?.let { page.moveFrame(it, page.frames.size - 1) }
            page.frames.last().polygon = Polygon.rectangle(3, 3, 7, 7)
            val middle = page.frameState()
            page.restoreFrames(state)
            assertContentEquals(original, doc.write(), name)
            // And forward again (redo).
            page.restoreFrames(middle)
            assertEquals(middle.map { it.points }, page.frameState().map { it.points })
            page.restoreFrames(state)
            assertContentEquals(original, doc.write(), "$name after redo and undo")
        }
    }

    @Test
    fun framesCanBeAddedToAPageThatHasNone() {
        val f = corpus.first { it.name.startsWith("NYC2123") }
        val doc = AcbfDocument.parse(f.readBytes())
        val page = doc.pages[5]
        assertEquals(0, page.frames.size)
        page.addFrame(Polygon.rectangle(0, 0, 100, 100))
        page.addFrame(Polygon.rectangle(0, 100, 100, 200))
        page.addFrame(Polygon.rectangle(0, 200, 100, 300), index = 0)
        assertEquals(listOf(200, 0, 100), page.frames.map { it.polygon!!.minY })
        page.moveFrame(page.frames[0], 2)
        assertEquals(listOf(0, 100, 200), page.frames.map { it.polygon!!.minY })
        // New frames take the indentation of the page's first child.
        val text = page.element.toString()
        val indent = Regex("\n([ \t]*)<image").find(text)!!.groupValues[1]
        assertTrue("\n$indent<frame points=\"0,0 100,0 100,100 0,100\"/>\n$indent<frame" in text, text)
    }
}
