import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import tessera.acbf.ComicFiles
import tessera.editor.EditorScreen
import tessera.editor.FrameTool
import tessera.editor.Language
import tessera.editor.Strings
import tessera.editor.Tool
import tessera.editor.ImageCache
import tessera.editor.Session
import tessera.editor.TesseraTheme
import java.io.File
import kotlin.math.roundToInt
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
        name: String, prefix: String, page: Int, dark: Boolean = false, previewing: Boolean = false, mode: Int = 0,
        keys: List<Key> = emptyList(),
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
            setContent { TesseraTheme(dark) { EditorScreen(session, images, onSave = { "" }, tool = tool, startPreviewing = previewing, startMode = mode) } }
            waitForIdle()
            for (k in keys) onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(k) } }
            prepare(session, tool)
            waitForIdle()
            if (previewing) mainClock.advanceTimeBy(1000)
            javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out.resolve("$name.png"))
        }
    }

    @Test
    fun craphoundPage() = shot("01-craphound", "Doctorow, Cory - Craphound", 1)

    @Test
    fun craphoundFrench() {
        Strings.language = Language.French
        try {
            shot("10-craphound-fr", "Doctorow, Cory - Craphound", 1) { _, tool -> tool.selected = 3 }
        } finally {
            Strings.language = Language.English
        }
    }

    @Test
    fun textsTab() = shot("14-texts", "Doctorow, Cory - Craphound", 1, mode = 1)

    @Test
    fun textsTabFrench() {
        Strings.language = Language.French
        try {
            shot("15-texts-fr", "Doctorow, Cory - Craphound", 1, mode = 1)
        } finally {
            Strings.language = Language.English
        }
    }

    /** Zoomed far in: the page stays inside its canvas, the thumbnails and inspector untouched. */
    @Test
    fun deepZoomStaysInItsCanvas() = shot("16-deep-zoom", "Doctorow, Cory - Craphound", 1, keys = List(12) { Key.Equals })

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

    /** The reading preview halfway between frames 4 and 5: one continuous move, no blank. */
    @Test
    fun readingPreviewTransition() {
        val file = book("Doctorow, Cory - Craphound") ?: return
        val comic = ComicFiles.open(file)
        val session = Session(comic, file.name).apply { goToPage(1) }
        val images = ImageCache(comic)
        runBlocking { images.page(session.page.imageHref) }
        runDesktopComposeUiTest(1440, 900) {
            mainClock.autoAdvance = false
            setContent { TesseraTheme { EditorScreen(session, images, onSave = { "" }, startPreviewing = true) } }
            mainClock.advanceTimeBy(800)
            repeat(3) { onRoot().performKeyInput { pressKey(Key.DirectionRight) }; mainClock.advanceTimeBy(800) }
            onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            for ((k, ms) in listOf(100L, 200L, 300L).withIndex()) {
                mainClock.advanceTimeBy(if (k == 0) ms else 100L)
                javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out.resolve("09-preview-move-$ms.png"))
            }
        }
    }

    /**
     * A made-up page, nothing from a real book: a tall frame whose outline goes round a balloon
     * spilling over its right side, as frames are often traced. Written as build/screens/Exemple.cbz.
     */
    private fun balloonBook(): File {
        val w = 1200
        val h = 1700
        fun png(draw: (java.awt.Graphics2D) -> Unit): ByteArray {
            val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = java.awt.Color.WHITE; g.fillRect(0, 0, w, h)
            draw(g)
            return java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(img, "png", it) }.toByteArray()
        }
        fun balloon(g: java.awt.Graphics2D, cx: Int, cy: Int, rx: Int, ry: Int, lines: Int) {
            g.color = java.awt.Color.WHITE; g.fillOval(cx - rx, cy - ry, 2 * rx, 2 * ry)
            g.color = java.awt.Color.BLACK; g.stroke = java.awt.BasicStroke(4f); g.drawOval(cx - rx, cy - ry, 2 * rx, 2 * ry)
            g.color = java.awt.Color(0x9A9A9A)
            for (k in 0 until lines) {
                val y = cy - (lines - 1) * 18 + k * 36
                val half = (rx * 0.75 * kotlin.math.sqrt(1.0 - ((y - cy).toDouble() / ry).let { it * it })).toInt()
                g.fillRoundRect(cx - half, y - 8, 2 * half, 16, 8, 8)
            }
        }
        val page = png { g ->
            fun panel(x0: Int, y0: Int, x1: Int, y1: Int, sky: Int, ground: Int) {
                g.color = java.awt.Color(sky); g.fillRect(x0, y0, x1 - x0, y1 - y0)
                g.color = java.awt.Color(ground); g.fillRect(x0, y0 + (y1 - y0) * 2 / 3, x1 - x0, (y1 - y0) / 3)
                g.color = java.awt.Color.BLACK; g.stroke = java.awt.BasicStroke(5f); g.drawRect(x0, y0, x1 - x0, y1 - y0)
            }
            panel(60, 60, 760, 1640, 0x8FB8C8, 0xC9A27A)
            g.color = java.awt.Color(0xE8D7A8); g.fillRect(110, 500, 200, 640); g.fillRect(380, 700, 260, 440)
            g.color = java.awt.Color(0x5E7F8C); g.fillRect(150, 560, 120, 160); g.fillRect(430, 770, 70, 110)
            g.color = java.awt.Color(0xF2D27A); g.fillOval(520, 180, 110, 110)
            panel(820, 60, 1140, 600, 0xB9C9A0, 0x7E9A62)
            panel(820, 1200, 1140, 1640, 0xD9B8C4, 0x8F6A7A)
            balloon(g, 330, 210, 220, 120, 3)
            balloon(g, 900, 900, 230, 210, 7)
        }
        // Frame 1 traced round the right balloon, a little outside its outline, as by hand.
        val reach = kotlin.math.acos((760.0 - 900) / 236) // the angle where the traced ellipse meets x = 760
        val arc = (0..10).map { k ->
            val a = -reach + k * 2 * reach / 10
            "${(900 + 236 * kotlin.math.cos(a)).roundToInt()},${(900 + 216 * kotlin.math.sin(a)).roundToInt()}"
        }
        val frames = listOf(
            "60,60 760,60 ${arc.joinToString(" ")} 760,1640 60,1640",
            "820,60 1140,60 1140,600 820,600",
            "820,1200 1140,1200 1140,1640 820,1640",
        )
        val acbf = """
            <?xml version='1.0' encoding='UTF-8'?>
            <ACBF xmlns="http://www.acbf.info/xml/acbf/1.1">
              <meta-data>
                <book-info>
                  <author><nickname>Tessera</nickname></author>
                  <book-title>Exemple</book-title>
                  <genre>other</genre>
                  <coverpage><image href="cover.png"/></coverpage>
                </book-info>
                <document-info>
                  <author><nickname>Tessera</nickname></author>
                  <id>tessera-exemple</id>
                  <version>1.0</version>
                </document-info>
              </meta-data>
              <body>
                <page>
                  <image href="page1.png"/>
            ${frames.joinToString("\n") { "      <frame points=\"$it\"/>" }}
                </page>
              </body>
            </ACBF>
        """.trimIndent()
        val file = out.resolve("Exemple.cbz")
        java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in listOf("Exemple.acbf" to acbf.toByteArray(), "cover.png" to page, "page1.png" to page)) {
                zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        return file
    }

    /** The made-up page in the editor (smoothed outline dashed) and in the reader. */
    @Test
    fun smoothedCut() {
        val file = balloonBook()
        for ((name, previewing) in listOf("20-cut-editor" to false, "21-cut-reader" to true)) {
            val comic = ComicFiles.open(file)
            val session = Session(comic, file.name).apply { goToPage(1) }
            val images = ImageCache(comic)
            runBlocking { images.page(session.page.imageHref) }
            runDesktopComposeUiTest(1440, 900) {
                setContent { TesseraTheme { EditorScreen(session, images, onSave = { "" }, startPreviewing = previewing) } }
                waitForIdle()
                if (previewing) mainClock.advanceTimeBy(1000)
                javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out.resolve("$name.png"))
            }
        }
    }

    private fun infoShot(name: String, prefix: String) {
        val file = book(prefix) ?: return
        val comic = ComicFiles.open(file)
        val session = Session(comic, file.name)
        runDesktopComposeUiTest(1440, 2300) {
            setContent { TesseraTheme { EditorScreen(session, ImageCache(comic), onSave = { "" }, startMode = 2) } }
            waitForIdle()
            javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out.resolve("$name.png"))
        }
    }

    @Test
    fun bookInfoPurpleClaw() = infoShot("13-info-purple", "Purple Claw")

    @Test
    fun bookInfoFrench() {
        Strings.language = Language.French
        try { infoShot("14-info-craphound-fr", "Doctorow, Cory - Craphound") } finally { Strings.language = Language.English }
    }

    /** The editor zoomed on a frame, plain and restored, then the reader restored with its panel. */
    @Test
    fun enhancedDisplay() {
        val file = book("Doctorow, Cory - Craphound") ?: return
        val comic = ComicFiles.open(file)
        val restore = tessera.editor.enhance.Enhancement(tessera.editor.enhance.EnhanceMode.Restore, 0.4f, 1f)
        for ((name, settings) in listOf("15-zoom-plain" to tessera.editor.enhance.Enhancement(), "16-zoom-restored" to restore)) {
            val session = Session(comic, file.name).apply { goToPage(1) }
            val images = ImageCache(comic)
            runBlocking { images.page(session.page.imageHref); images.enhanced(session.page.imageHref, settings) }
            tessera.editor.EnhancePrefs.editor = settings
            runDesktopComposeUiTest(1440, 900) {
                val tool = FrameTool(session)
                setContent { TesseraTheme { EditorScreen(session, images, onSave = { "" }, tool = tool) } }
                waitForIdle()
                // Zoom ×4 around the robot's eye in frame 4.
                onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { repeat(6) { pressKey(Key.Equals) } } }
                waitForIdle()
                javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out.resolve("$name.png"))
                if (settings.active) {
                    onRoot().performKeyInput { keyDown(Key.C) }
                    waitForIdle()
                    javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out.resolve("16b-comparing.png"))
                    onRoot().performKeyInput { keyUp(Key.C) }
                }
            }
        }
        val session = Session(comic, file.name).apply { goToPage(1) }
        val images = ImageCache(comic)
        runBlocking { images.page(session.page.imageHref); images.enhanced(session.page.imageHref, restore) }
        tessera.editor.EnhancePrefs.reader = restore
        try {
            runDesktopComposeUiTest(1440, 900) {
                setContent { TesseraTheme { EditorScreen(session, images, onSave = { "" }, startPreviewing = true) } }
                waitForIdle()
                repeat(3) { onRoot().performKeyInput { pressKey(Key.DirectionRight) }; waitForIdle() }
                mainClock.advanceTimeBy(800)
                onNodeWithText("✦ Enhanced display").performClick()
                waitForIdle()
                javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out.resolve("17-reader-restored.png"))
            }
        } finally {
            tessera.editor.EnhancePrefs.editor = tessera.editor.enhance.Enhancement()
            tessera.editor.EnhancePrefs.reader = tessera.editor.enhance.Enhancement()
        }
    }
}
