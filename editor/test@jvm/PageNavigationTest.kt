import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import tessera.acbf.ComicFiles
import tessera.editor.EditorScreen
import tessera.editor.ImageCache
import tessera.editor.Session
import tessera.editor.TesseraTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PageNavigationTest {
    private val root: File = run {
        var dir = File("").absoluteFile
        while (!File(dir, "project.yaml").exists()) dir = dir.parentFile
        dir
    }

    @Test
    fun thumbnailsAndKeysChangeThePage() {
        val file = File(root, "fixtures/samples").listFiles()?.firstOrNull { it.name.startsWith("Doctorow, Cory - Craphound") } ?: return
        val comic = ComicFiles.open(file)
        val session = Session(comic, file.name)
        runDesktopComposeUiTest(1440, 900) {
            setContent { TesseraTheme { EditorScreen(session, ImageCache(comic), onSave = { "" }) } }
            waitForIdle()
            assertEquals(1, session.pageIndex)
            onAllNodesWithText("4")[0].performClick()
            waitForIdle()
            assertEquals(3, session.pageIndex, "thumbnail click")
            onRoot().performKeyInput { pressKey(Key.PageDown) }
            waitForIdle()
            assertEquals(4, session.pageIndex, "Page Down")
            onRoot().performKeyInput { pressKey(Key.DirectionUp) }
            waitForIdle()
            assertEquals(3, session.pageIndex, "arrow up")
            // With a frame selected, arrows move the frame; ⌥ arrows still turn the page.
            session.goToPage(1); waitForIdle()
            onAllNodesWithText("Rectangle")[0].performClick(); waitForIdle()
            onRoot().performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.DirectionRight) } }
            waitForIdle()
            assertEquals(2, session.pageIndex, "Alt + right arrow")
            onAllNodesWithText("›")[0].performClick(); waitForIdle()
            assertEquals(3, session.pageIndex, "next-page button")
            session.goToPage(3)
            Thread.sleep(1500); waitForIdle()
            val out = File(root, "build/screens").apply { mkdirs() }
            javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out.resolve("11-after-page-change.png"))
        }
    }

    @Test
    fun readingGoesOnFromPageToPage() {
        val file = File(root, "fixtures/samples").listFiles()?.firstOrNull { it.name.startsWith("Doctorow, Cory - Craphound") } ?: return
        val comic = ComicFiles.open(file)
        val session = Session(comic, file.name)
        runDesktopComposeUiTest(1440, 900) {
            setContent { TesseraTheme { EditorScreen(session, ImageCache(comic), onSave = { "" }, startPreviewing = true) } }
            waitForIdle()
            onNodeWithText("Page 2 of 24 · Frame 1 / 6").assertExists()
            repeat(6) { onRoot().performKeyInput { pressKey(Key.DirectionRight) }; waitForIdle() }
            onNodeWithText("Page 3 of 24 · Frame 1 / 5").assertExists()
            onRoot().performKeyInput { pressKey(Key.DirectionLeft) }; waitForIdle()
            onNodeWithText("Page 2 of 24 · Frame 6 / 6").assertExists()
            repeat(6) { onRoot().performKeyInput { pressKey(Key.DirectionRight) }; waitForIdle() }
            onRoot().performKeyInput { pressKey(Key.Escape) }; waitForIdle()
            assertEquals(3, session.pageIndex, "the editor follows the reading (6 steps from page 2 frame 6: page 3 frames 1-5, then page 4)")
        }
    }
}
