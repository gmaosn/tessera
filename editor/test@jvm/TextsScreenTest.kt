import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import kotlinx.coroutines.runBlocking
import tessera.acbf.ComicFiles
import tessera.acbf.textAreas
import tessera.editor.EditorScreen
import tessera.editor.ImageCache
import tessera.editor.Session
import tessera.editor.Strings
import tessera.editor.TesseraTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Texts tab and the ways to Book info, on a real sample book (skipped when samples are missing). */
@OptIn(ExperimentalTestApi::class)
class TextsScreenTest {
    private val root: File = run {
        var dir = File("").absoluteFile
        while (!File(dir, "project.yaml").exists()) dir = dir.parentFile
        dir
    }

    private fun book(prefix: String): File? = File(root, "fixtures/samples").listFiles()?.firstOrNull { it.name.startsWith(prefix) }

    @Test
    fun typingATranslationChangesOnlyThatTextAndUndoGivesTheFileBack() {
        val file = book("Doctorow, Cory - Craphound") ?: return println("fixtures/samples missing")
        val comic = ComicFiles.open(file)
        val session = Session(comic, file.name)
        session.goToPage(1)
        val before = session.document.write()
        val english = session.page.textAreas("en").first().text
        runDesktopComposeUiTest(1440, 1400) {
            setContent { TesseraTheme { EditorScreen(session, ImageCache(comic), onSave = { "" }, startMode = 1) } }
            waitForIdle()
            onNode(hasSetTextAction() and hasText(english)).performTextReplacement("Changed")
            waitForIdle()
            assertEquals("Changed", session.page.textAreas("en").first().text)
            onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.Z) } }
            waitForIdle()
            assertContentEquals(before, session.document.write())
        }
    }

    @Test
    fun bookInfoIsOneClickOrKeyAwayAndEscapeComesBack() {
        val file = book("Doctorow, Cory - Craphound") ?: return println("fixtures/samples missing")
        val comic = ComicFiles.open(file)
        val session = Session(comic, file.name)
        runDesktopComposeUiTest(1440, 1000) {
            setContent { TesseraTheme { EditorScreen(session, ImageCache(comic), onSave = { "" }) } }
            waitForIdle()
            // The book summary at the bottom of the inspector.
            onNodeWithText(Strings.editBookInfo).performClick()
            waitForIdle()
            onNodeWithText(Strings.infoBook).assertExists()
            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitForIdle()
            onNodeWithText(Strings.frames.uppercase()).assertExists()
            onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.Three) } }
            waitForIdle()
            onNodeWithText(Strings.infoBook).assertExists()
            onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.Two) } }
            waitForIdle()
            onNodeWithText(Strings.textAreas.uppercase()).assertExists()
            // The file name in the top bar opens it too.
            onNodeWithText(file.name).performClick()
            waitForIdle()
            onNodeWithText(Strings.infoBook).assertExists()
        }
    }

    @Test
    fun aDeepZoomLeavesThePageStripUntouched() {
        val file = book("Doctorow, Cory - Craphound") ?: return println("fixtures/samples missing")
        val comic = ComicFiles.open(file)
        val session = Session(comic, file.name)
        val images = ImageCache(comic)
        runBlocking {
            for (p in session.pages.take(8)) images.thumbnail(p.imageHref)
            images.page(session.page.imageHref)
        }
        runDesktopComposeUiTest(1200, 800) {
            setContent { TesseraTheme { EditorScreen(session, images, onSave = { "" }) } }
            waitForIdle()
            fun strip() = onRoot().captureToImage().toAwtImage().let { img ->
                val w = (110 * density.density).toInt()
                val top = (60 * density.density).toInt()
                img.getRGB(0, top, w, img.height - 2 * top, null, 0, w)
            }
            val before = strip()
            repeat(14) { onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.Equals) } } }
            waitForIdle()
            val after = strip()
            val differing = before.indices.count { before[it] != after[it] }
            assertTrue(differing == 0, "$differing strip pixels changed when zooming")
        }
    }
}
