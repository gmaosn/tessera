import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import tessera.acbf.ComicFiles
import tessera.editor.EditorScreen
import tessera.editor.FrameTool
import tessera.editor.Tool
import tessera.editor.ImageCache
import tessera.editor.Session
import tessera.editor.TesseraTheme
import java.io.File
import kotlin.test.Test

/**
 * Renders the editor on real sample books as PNG files in build/screens, for the visual check of
 * every screen. Skipped when the sample books are missing (tools/fetch-fixtures.sh).
 */
@OptIn(ExperimentalTestApi::class)
class ScreensTest {
    private val root: File = run {
        var dir = File("").absoluteFile
        while (!File(dir, "project.yaml").exists()) dir = dir.parentFile
        dir
    }
    private val out = File(root, "build/screens").apply { mkdirs() }

    private fun book(prefix: String): File? = File(root, "fixtures/samples").listFiles()?.firstOrNull { it.name.startsWith(prefix) }

    private fun shot(
        name: String, prefix: String, page: Int, dark: Boolean = false, previewing: Boolean = false,
        prepare: (Session, FrameTool) -> Unit = { _, _ -> },
    ) {
        val file = book(prefix) ?: return println("fixtures/samples missing")
        val comic = ComicFiles.open(file)
        val session = Session(comic, file.name)
        val images = ImageCache(comic)
        session.goToPage(page)
        val tool = FrameTool(session)
        runBlocking {
            for (p in session.pages.take(12)) images.thumbnail(p.imageHref)
            images.page(session.page.imageHref)
        }
        runDesktopComposeUiTest(1440, 900) {
            setContent { TesseraTheme(dark) { EditorScreen(session, images, onSave = { "" }, tool = tool, startPreviewing = previewing) } }
            waitForIdle()
            prepare(session, tool)
            waitForIdle()
            if (previewing) mainClock.advanceTimeBy(1000)
            javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out.resolve("$name.png"))
        }
    }

    @Test
    fun craphoundPage() = shot("01-craphound", "Doctorow, Cory - Craphound", 1)

    @Test
    fun craphoundDark() = shot("02-craphound-dark", "Doctorow, Cory - Craphound", 1, dark = true)

    @Test
    fun pageWithoutFrames() = shot("03-nyc2123-empty", "NYC2123", 4)

    @Test
    fun pepperAndCarrotTallPage() = shot("04-pepper", "Revoy", 2)

    @Test
    fun selectedFrameWithHandles() = shot("05-selected", "Doctorow, Cory - Craphound", 1) { _, tool -> tool.selected = 3 }

    @Test
    fun orderMode() = shot("06-order", "Doctorow, Cory - Craphound", 1) { _, tool ->
        tool.select(Tool.Order)
        tool.press(Offset(300f, 1250f), 1f)
        tool.press(Offset(800f, 1250f), 1f)
    }

    @Test
    fun drawingAPolygon() = shot("07-polygon", "NYC2123", 4) { _, tool ->
        tool.select(Tool.Polygon)
        for (p in listOf(Offset(100f, 100f), Offset(1500f, 80f), Offset(1520f, 900f))) tool.press(p, 0.5f)
        tool.move(Offset(300f, 950f), 0.5f)
    }

    @Test
    fun readingPreview() = shot("08-preview", "Doctorow, Cory - Craphound", 1, previewing = true)
}
