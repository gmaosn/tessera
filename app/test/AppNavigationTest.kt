import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import tessera.acbf.ComicFiles
import tessera.app.fileDrop
import tessera.editor.EditorScreen
import tessera.editor.ImageCache
import tessera.editor.Session
import tessera.editor.TesseraTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AppNavigationTest {
    @Test
    fun pagesChangeInsideTheAppLayout() {
        var dir = File("").absoluteFile
        while (!File(dir, "project.yaml").exists()) dir = dir.parentFile
        val file = File(dir, "fixtures/samples").listFiles()?.firstOrNull { it.name.startsWith("Doctorow, Cory - Craphound") } ?: return
        val comic = ComicFiles.open(file)
        val session = Session(comic, file.name)
        val images = ImageCache(comic)
        runDesktopComposeUiTest(1440, 900) {
            setContent { TesseraTheme { Box(Modifier.fillMaxSize().fileDrop { }) { EditorScreen(session, images, onSave = { "" }) } } }
            waitForIdle()
            onAllNodesWithText("4")[0].performClick()
            waitForIdle()
            assertEquals(3, session.pageIndex, "thumbnail click")
            onRoot().performKeyInput { pressKey(Key.PageDown) }
            waitForIdle()
            assertEquals(4, session.pageIndex, "Page Down")
        }
    }
}
