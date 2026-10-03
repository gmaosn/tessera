import tessera.xml.XmlComment
import tessera.xml.XmlElement
import tessera.xml.XmlParseException
import tessera.xml.XmlParser
import tessera.xml.XmlText
import tessera.xml.appendElement
import tessera.xml.insertElement
import tessera.xml.removeElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class XmlRoundTripTest {
    private fun same(source: String) = assertEquals(source, XmlParser.parse(source).write())

    @Test
    fun writesUntouchedDocumentsBackExactly() {
        same("<?xml version='1.0' encoding='utf-8'?>\n<a/>")
        same("﻿<?xml version=\"1.0\"?>\r\n<!-- note -->\r\n<a  x = 'one'\ty=\"two\" ><b/>\r\n</a >\r\n")
        same("<?xml-stylesheet type=\"text/css\" href=\"default.css\"?><ACBF xmlns=\"urn:x\"><p>a &amp; b &lt; c &gt; d &#233; &eacute;</p></ACBF>")
        same("<!DOCTYPE a [ <!ENTITY e \"x\"> ]><a><![CDATA[ <raw> & ]]><?pi data?></a>")
        same("<a b=\"&quot;q&quot;\" c='it&apos;s'>\n\t<x:y xmlns:x=\"urn:y\"/>\n</a>\n")
    }

    @Test
    fun decodesTextAndAttributes() {
        val root = XmlParser.parse("<a t=\"1 &lt; 2\">x &amp; y &#x1F600; &unknown;</a>").root
        assertEquals("1 < 2", root["t"])
        assertEquals("x & y 😀 &unknown;", (root.children[0] as XmlText).text)
    }

    @Test
    fun editsChangeOnlyWhatChanged() {
        val doc = XmlParser.parse("<a  x = 'one'\ty=\"two\" ><!--c--><b k='v'/></a>")
        doc.root["y"] = "3 & 4"
        assertEquals("<a  x = 'one'\ty=\"3 &amp; 4\" ><!--c--><b k='v'/></a>", doc.write())
        doc.root["z"] = "new"
        assertEquals("<a  x = 'one'\ty=\"3 &amp; 4\" z=\"new\" ><!--c--><b k='v'/></a>", doc.write())
        doc.root["x"] = null
        assertEquals("<a\ty=\"3 &amp; 4\" z=\"new\" ><!--c--><b k='v'/></a>", doc.write())
        (doc.root.children[0] as XmlComment).content = "d"
        doc.root.element("b")!!.append(XmlText("t<"))
        assertEquals("<a\ty=\"3 &amp; 4\" z=\"new\" ><!--d--><b k='v'>t&lt;</b></a>", doc.write())
    }

    @Test
    fun insertsWithTheSurroundingIndentation() {
        val doc = XmlParser.parse("<page>\n   <image href=\"a.jpg\"/>\n   <frame points=\"1,1\"/>\n  </page>")
        val page = doc.root
        page.insertElement(XmlElement("frame").also { it["points"] = "2,2" }, page.elements("frame").last(), "\n")
        assertEquals("<page>\n   <image href=\"a.jpg\"/>\n   <frame points=\"1,1\"/>\n   <frame points=\"2,2\"/>\n  </page>", doc.write())
        page.removeElement(page.elements("frame").first())
        assertEquals("<page>\n   <image href=\"a.jpg\"/>\n   <frame points=\"2,2\"/>\n  </page>", doc.write())
    }

    @Test
    fun appendsIntoAnEmptyElementOneLevelDeeper() {
        val doc = XmlParser.parse("<body>\r\n  <page/>\r\n</body>")
        val page = doc.root.element("page")!!
        page.appendElement(XmlElement("frame"), "\r\n")
        assertEquals("<body>\r\n  <page>\r\n    <frame/>\r\n  </page>\r\n</body>", doc.write())
    }

    @Test
    fun reportsWhereTheDocumentIsBroken() {
        val e = assertFailsWith<XmlParseException> { XmlParser.parse("<a>\n  <b></c>\n</a>") }
        assertEquals(2, e.line)
        assertFailsWith<XmlParseException> { XmlParser.parse("<a x='1' x='2'/>") }
        assertFailsWith<XmlParseException> { XmlParser.parse("<a>") }
        assertFailsWith<XmlParseException> { XmlParser.parse("<a/><b/>") }
    }
}
