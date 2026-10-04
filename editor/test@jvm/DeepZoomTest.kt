import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import tessera.acbf.AcbfDocument
import tessera.acbf.Comic
import tessera.acbf.Container
import tessera.editor.EditorScreen
import tessera.editor.ImageCache
import tessera.editor.Session
import tessera.editor.TesseraTheme
import java.io.ByteArrayOutputStream
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.Test

/** A large page (300 dpi A4) zoomed to the maximum must not crash the layout (it did). */
@OptIn(ExperimentalTestApi::class)
class DeepZoomTest {
    @Test
    fun aHugePageCanBeZoomedAllTheWay() {
        val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(2480, 3508, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        val container = object : Container {
            override val paths = listOf("a.png", "b.png")
            override fun read(path: String) = png
        }
        val comic = Comic(AcbfDocument.create("Big", listOf("a.png", "b.png")), container, "big.acbf", generated = false)
        val session = Session(comic, "big.cbz")
        runDesktopComposeUiTest(1440, 900) {
            // A Retina screen: twice the pixels per dp, as on the machine where it crashed.
            setContent { androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(2f)) { TesseraTheme { EditorScreen(session, ImageCache(comic), onSave = { "" }, askBookInfo = false) } } }
            Thread.sleep(500); waitForIdle()
            onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { repeat(40) { pressKey(Key.Equals) } } }
            waitForIdle()
        }
    }
}
