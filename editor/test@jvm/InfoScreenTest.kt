import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import tessera.acbf.AcbfDocument
import tessera.acbf.Comic
import tessera.acbf.EmptyContainer
import tessera.acbf.Metadata
import tessera.acbf.Section
import tessera.editor.EditorScreen
import tessera.editor.ImageCache
import tessera.editor.Session
import tessera.editor.TesseraTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class InfoScreenTest {
    private val file = run {
        var dir = File("").absoluteFile
        while (!File(dir, "project.yaml").exists()) dir = dir.parentFile
        File(dir, "fixtures/acbf-xml/Purple Claw 01.acbf")
    }

    @Test
    fun editingFieldsChangesTheFileAndUndoGivesItBack() {
        val bytes = file.readBytes()
        val comic = Comic(AcbfDocument.parse(bytes), EmptyContainer, file.name, generated = false)
        val session = Session(comic, file.name)
        runDesktopComposeUiTest(1440, 2300) {
            setContent { TesseraTheme { EditorScreen(session, ImageCache(comic), onSave = { "" }, startMode = 2) } }
            waitForIdle()
            onNode(hasSetTextAction() and hasText("Minoan Publishing Corp.")).performTextReplacement("Minoan")
            onNode(hasSetTextAction() and hasText("Brown")).performTextReplacement("Browne")
            waitForIdle()
            val m = Metadata(session.document)
            assertEquals("Minoan", m.text(Section.Publish, "publisher"))
            assertEquals("Browne", m.readAuthor(m.authors(Section.Book)[1]).lastName)
            assertEquals(true, session.dirty)
            repeat(2) { onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.Z) } }; waitForIdle() }
            assertContentEquals(bytes, session.document.write())
            // The fields show the restored values.
            onNode(hasSetTextAction() and hasText("Minoan Publishing Corp.")).assertExists()
        }
    }
}
