import tessera.acbf.AcbfDocument
import tessera.acbf.Metadata
import tessera.acbf.Point
import tessera.acbf.Polygon
import tessera.acbf.TextState
import tessera.acbf.addTextArea
import tessera.acbf.removeTextArea
import tessera.acbf.textAreas
import tessera.acbf.textLanguages
import tessera.acbf.textLayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextsTest {
    private val source = """
        <?xml version='1.0' encoding='UTF-8'?>
        <ACBF xmlns="http://www.acbf.info/xml/acbf/1.1">
          <meta-data>
            <book-info>
              <book-title>T</book-title>
              <languages>
                <text-layer lang="en" show="False"/>
              </languages>
            </book-info>
          </meta-data>
          <body>
            <page>
              <image href="p1.jpg"/>
              <text-layer lang="en">
                <text-area points="10,10 90,10 90,40 10,40" type="speech">
                  <p>Hello <emphasis>you</emphasis></p>
                  <p>there</p>
                </text-area>
              </text-layer>
              <frame points="0,0 100,0 100,100 0,100"/>
            </page>
            <page>
              <image href="p2.jpg"/>
              <frame points="0,0 100,0 100,100 0,100"/>
            </page>
          </body>
        </ACBF>
    """.trimIndent() + "\n"

    private val rect = Polygon(listOf(Point(1, 2), Point(30, 2), Point(30, 20), Point(1, 20)))

    @Test
    fun readsLayersAndAreas() {
        val doc = AcbfDocument.parse(source.encodeToByteArray())
        val page = doc.pages[0]
        val area = page.textAreas("en").single()
        assertEquals("Hello you\nthere", area.text)
        assertEquals("speech", area.type)
        assertEquals(listOf("en"), doc.textLanguages)
        assertTrue(page.textAreas("fr").isEmpty())
    }

    @Test
    fun aNewLayerGoesAfterTheImageWithTheFilesIndentation() {
        val doc = AcbfDocument.parse(source.encodeToByteArray())
        val page = doc.pages[1]
        page.addTextArea("fr", rect).setText("Bonjour\n\ntoi")
        val expected = """
            |    <page>
            |      <image href="p2.jpg"/>
            |      <text-layer lang="fr">
            |        <text-area points="1,2 30,2 30,20 1,20">
            |          <p>Bonjour</p>
            |          <p>toi</p>
            |        </text-area>
            |      </text-layer>
            |      <frame points="0,0 100,0 100,100 0,100"/>
            |    </page>
        """.trimMargin()
        assertTrue(expected in doc.write().decodeToString(), doc.write().decodeToString())
    }

    @Test
    fun unchangedParagraphsKeepTheirMarkup() {
        val doc = AcbfDocument.parse(source.encodeToByteArray())
        val area = doc.pages[0].textAreas("en").single()
        area.setText("Hello you\nover there")
        val out = doc.write().decodeToString()
        assertTrue("<p>Hello <emphasis>you</emphasis></p>" in out)
        assertTrue("<p>over there</p>" in out)
        area.setText("Hello you\nover there")
        assertEquals(out, doc.write().decodeToString())
    }

    @Test
    fun removingTheLastAreaRemovesItsLayer() {
        val doc = AcbfDocument.parse(source.encodeToByteArray())
        val page = doc.pages[0]
        page.removeTextArea(page.textAreas("en").single())
        assertNull(page.textLayer("en"))
        assertTrue("text-layer lang=\"en\">" !in doc.write().decodeToString())
    }

    @Test
    fun flagsAreWrittenOnlyWhenTheirMeaningChanges() {
        val doc = AcbfDocument.parse(source.replace("type=\"speech\"", "type=\"speech\" inverted=\"True\"").encodeToByteArray())
        val area = doc.pages[0].textAreas("en").single()
        val before = doc.write().decodeToString()
        area.inverted = true
        assertEquals(before, doc.write().decodeToString())
        area.inverted = false
        area.transparent = true
        area.rotation = 450
        val out = doc.write().decodeToString()
        assertTrue("inverted" !in out)
        assertTrue("transparent=\"true\"" in out && "text-rotation=\"90\"" in out)
    }

    @Test
    fun aTextStateBringsThePageBackByteForByte() {
        val doc = AcbfDocument.parse(source.encodeToByteArray())
        val page = doc.pages[0]
        val frame = page.frames.single().element
        val state = TextState.of(page)
        page.addTextArea("fr", rect).setText("Salut")
        page.textAreas("en").single().setText("changed")
        page.textAreas("en").single().inverted = true
        state.restore(page)
        assertEquals(source, doc.write().decodeToString())
        // Frames are the very same elements, so frame undo steps stay valid.
        assertTrue(page.frames.single().element === frame)
    }

    @Test
    fun declaringALanguageAddsAShownLayer() {
        val doc = AcbfDocument.parse(source.encodeToByteArray())
        Metadata(doc).declareLanguage("fr")
        Metadata(doc).declareLanguage("en")
        val out = doc.write().decodeToString()
        assertTrue("    <text-layer lang=\"en\" show=\"False\"/>\n        <text-layer lang=\"fr\" show=\"true\"/>\n" in out, out)
        Metadata(doc).setLanguageShown("en", false)
        assertEquals(out, doc.write().decodeToString())
        Metadata(doc).setLanguageShown("en", true)
        assertTrue("<text-layer lang=\"en\" show=\"true\"/>" in doc.write().decodeToString())
    }
}
