import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import tessera.acbf.ComicFiles
import tessera.editor.EditorPrefs
import tessera.editor.EditorScreen
import tessera.editor.ImageCache
import tessera.editor.Language
import tessera.editor.Session
import tessera.editor.Strings
import tessera.editor.TesseraTheme
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Opening a CBZ without ACBF document asks for the book's title and authors. */
@OptIn(ExperimentalTestApi::class)
class BookInfoDialogTest {
    private val root: File = run {
        var dir = File("").absoluteFile
        while (!File(dir, "project.yaml").exists()) dir = dir.parentFile
        dir
    }

    /** A CBZ of a few Craphound images and no ACBF document. */
    private fun plainCbz(): File? {
        val source = File(root, "fixtures/samples").listFiles()?.firstOrNull { it.name.startsWith("Doctorow, Cory - Craphound") } ?: return null
        val out = File.createTempFile("Craphound plain ", ".cbz").apply { deleteOnExit() }
        ZipFile(source).use { z ->
            ZipOutputStream(out.outputStream()).use { o ->
                for (name in listOf("cover.jpg", "page1.jpg", "page2.jpg", "page3.jpg")) {
                    o.putNextEntry(ZipEntry(name)); z.getInputStream(z.getEntry(name)).copyTo(o); o.closeEntry()
                }
            }
        }
        return out
    }

    @Test
    fun theDialogFillsTheNewDocument() {
        val file = plainCbz() ?: return
        val comic = ComicFiles.open(file)
        val session = Session(comic, file.name)
        EditorPrefs.creator = ""
        Strings.language = Language.French
        try {
            runDesktopComposeUiTest(1440, 900) {
                setContent { TesseraTheme { EditorScreen(session, ImageCache(comic), onSave = { "" }) } }
                waitForIdle()
                val fields = onAllNodes(hasSetTextAction())
                fields[0].performTextReplacement("Craphound")
                fields[1].performTextInput("Cory Doctorow, Bob")
                fields[2].performTextInput("Un extraterrestre chineur.")
                fields[3].performTextInput("Alex Martin")
                waitForIdle()
                javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(root, "build/screens").apply { mkdirs() }.resolve("12-book-info-fr.png"))
                onNodeWithText(Strings.validate).performClick()
                waitForIdle()
            }
        } finally {
            Strings.language = Language.English
        }
        val text = session.document.write().decodeToString()
        assertEquals("Craphound", session.document.titles["fr"])
        assertTrue("<first-name>Cory</first-name>" in text && "<last-name>Doctorow</last-name>" in text && "<nickname>Bob</nickname>" in text, text)
        assertTrue("<p>Un extraterrestre chineur.</p>" in text, text)
        assertTrue("<first-name>Alex</first-name>" in text && "<last-name>Martin</last-name>" in text, text)
        assertEquals("Alex Martin", EditorPrefs.creator)
        assertEquals(4, session.pages.size)
        assertTrue(session.dirty)
    }
}
