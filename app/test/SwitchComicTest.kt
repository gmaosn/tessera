import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import tessera.acbf.ComicFiles
import tessera.app.EditorFor
import tessera.editor.ImageCache
import tessera.editor.Session
import tessera.editor.TesseraTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** After opening another comic, every thumbnail must act on the new one (it did not, once). */
@OptIn(ExperimentalTestApi::class)
class SwitchComicTest {
    @Test
    fun thumbnailsActOnTheComicNowOpen() {
        var dir = File("").absoluteFile
        while (!File(dir, "project.yaml").exists()) dir = dir.parentFile
        val samples = File(dir, "fixtures/samples")
        val first = samples.listFiles()?.firstOrNull { it.name.startsWith("Doctorow, Cory - Craphound") } ?: return
        val second = samples.listFiles()?.firstOrNull { it.name.startsWith("Vision Machine") } ?: return
        val a = Session(ComicFiles.open(first), first.name)
        val b = Session(ComicFiles.open(second), second.name)
        var current by mutableStateOf(a)
        val images = mapOf(a to ImageCache(a.comic), b to ImageCache(b.comic))
        runDesktopComposeUiTest(1440, 900) {
            setContent { TesseraTheme { EditorFor(current, images.getValue(current), onSave = { "" }) } }
            waitForIdle()
            onAllNodesWithText("4")[0].performClick(); waitForIdle()
            assertEquals(3, a.pageIndex)
            current = b
            waitForIdle()
            for (n in listOf(4, 3, 5)) {
                onAllNodesWithText("$n")[0].performClick(); waitForIdle()
                assertEquals(n - 1, b.pageIndex, "thumbnail $n of the second comic")
            }
            assertEquals(3, a.pageIndex, "the first comic is left alone")
        }
    }
}
