import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import tessera.app.ImportDialog
import tessera.app.ImportProgress
import tessera.app.PdfImportOptions
import tessera.app.PdfInfo
import tessera.editor.Language
import tessera.editor.Strings
import tessera.editor.TesseraTheme
import java.io.File
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ImportUiTest {
    @Test
    fun screens() {
        var dir = File("").absoluteFile
        while (!File(dir, "project.yaml").exists()) dir = dir.parentFile
        val out = File(dir, "build/screens").apply { mkdirs() }
        Strings.language = Language.French
        try {
            runDesktopComposeUiTest(1100, 800) {
                setContent {
                    TesseraTheme {
                        ImportDialog(File("Chevalier Ardent 1.pdf"), PdfInfo(66, "", "", "", ""), PdfImportOptions(), File("Chevalier Ardent 1.cbz"), {}, {}, {}, {})
                    }
                }
                waitForIdle()
                javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out.resolve("18-import-fr.png"))
            }
            runDesktopComposeUiTest(1100, 500) {
                setContent { TesseraTheme { ImportProgress(File("Chevalier Ardent 1.pdf"), 24, 66) {} } }
                waitForIdle()
                javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out.resolve("19-import-progress-fr.png"))
            }
        } finally {
            Strings.language = Language.English
        }
    }
}
