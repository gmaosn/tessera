import androidx.compose.ui.graphics.toComposeImageBitmap
import tessera.acbf.Point
import tessera.acbf.Polygon
import tessera.editor.Hyphenator
import tessera.editor.TextFit
import tessera.editor.groundColour
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HyphenationTest {
    private fun cut(lang: String, word: String): String {
        val points = Hyphenator.of(lang)!!.points(word)
        return buildString { word.forEachIndexed { i, c -> if (i in points) append('-'); append(c) } }
    }

    @Test
    fun cutsBetweenSyllablesByEachLanguagesRules() {
        assertEquals("in-cons-ciente", cut("fr", "inconsciente"))
        assertEquals("per-sonne", cut("fr", "personne"))
        assertEquals("ma-gni-fique!", cut("fr", "magnifique!"))
        assertEquals("hy-phen-ation", cut("en", "hyphenation"))
        assertEquals("Sil-ben-tren-nung", cut("de", "Silbentrennung"))
        // A word already holding a hyphen is cut only there.
        assertEquals(listOf(3), Hyphenator.of("fr")!!.points("là-en"))
        assertNull(Hyphenator.of("ja"))
    }

    private val balloon = Polygon((0 until 24).map { k ->
        val a = 2 * PI * k / 24
        Point((200 + 110 * cos(a)).roundToInt(), (300 + 200 * sin(a)).roundToInt())
    })

    @Test
    fun aLongWordIsCutWithAHyphenRatherThanAnywhere() {
        val fr = Hyphenator.of("fr")!!
        val layout = TextFit.layout(
            "Oh mais il y a une personne inconsciente là-en bas!", balloon, 0, { it.length * 55f }, 28f,
            hyphenate = fr::points, hyphenWidth = 33f,
        )!!
        val joined = layout.lines.joinToString(" ") { it.text }
        // Every cut is at a syllable, marked by a hyphen, and nothing is lost.
        assertEquals("Oh mais il y a une personne inconsciente là-en bas!", joined.replace(Regex("(?<=[a-zà])- (?=[a-z])"), "").replace("- ", "-"))
        for (line in layout.lines) assertTrue(line.text.split(' ').all { w -> w.endsWith('-').not() || w.dropLast(1) + "-" == w })
    }

    @Test
    fun chineseIsCutBetweenCharacters() {
        val box = Polygon(listOf(Point(0, 0), Point(300, 0), Point(300, 400), Point(0, 400)))
        val layout = TextFit.layout("这是一个很长的句子没有空格", box, 0, { it.length * 100f }, 30f)!!
        assertTrue(layout.lines.size > 1)
        assertEquals("这是一个很长的句子没有空格", layout.lines.joinToString("") { it.text })
    }

    @Test
    fun theGroundIsTheColourBehindTheLettersNotTheirAverage() {
        val img = java.awt.image.BufferedImage(200, 100, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = java.awt.Color(250, 240, 200); g.fillRect(0, 0, 200, 100)
        g.color = java.awt.Color.BLACK; g.font = java.awt.Font("SansSerif", java.awt.Font.BOLD, 30); g.drawString("HELLO", 30, 60)
        g.dispose()
        val area = Polygon(listOf(Point(10, 10), Point(190, 10), Point(190, 90), Point(10, 90)))
        assertEquals("#faf0c8", groundColour(img.toComposeImageBitmap(), area))
        // A screentone (half black, half white) gives its average grey.
        for (y in 0 until 100) for (x in 0 until 200) img.setRGB(x, y, if ((x + y) % 2 == 0) 0 else 0xffffff)
        val grey = groundColour(img.toComposeImageBitmap(), area)!!.substring(1, 3).toInt(16)
        assertTrue(grey in 120..135, "grey $grey")
    }

    @Test
    fun frenchPunctuationStaysWithItsWord() {
        val narrow = Polygon(listOf(Point(0, 0), Point(260, 0), Point(260, 2000), Point(0, 2000)))
        val layout = TextFit.layout("« Quelle chose brillante ? »", narrow, 0, { it.length * 55f }, 28f)!!
        assertTrue(layout.lines.none { it.text.trim() in setOf("?", "»", "«", "? »") }, layout.lines.joinToString(" | ") { it.text })
        assertTrue(layout.lines.first().text.startsWith("«\u00a0Quelle"))
    }
}
