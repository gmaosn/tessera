package acbf

import acbf.xml.XmlDocument
import acbf.xml.XmlElement
import acbf.xml.XmlParser
import acbf.xml.appendElement
import acbf.xml.insertElement
import acbf.xml.removeElement

/** The ACBF namespaces met in real files. Each document keeps the one it was written with. */
object AcbfNamespaces {
    const val V1_0 = "http://www.fictionbook-lib.org/xml/acbf/1.0"
    /** Used by ACBF Editor 1.1x before the official namespace was settled; common in the wild. */
    const val V1_1_LEGACY = "http://www.fictionbook-lib.org/xml/acbf/1.1"
    const val V1_1 = "http://www.acbf.info/xml/acbf/1.1"
    /** Written by the GTK4 ACBF Editor for the unreleased 1.2 proposal. */
    const val V1_2_LEGACY = "http://www.fictionbook-lib.org/xml/acbf/1.2"

    /** The namespace given to documents created from scratch: the latest released one. */
    const val DEFAULT = V1_1
}

/**
 * An ACBF document: typed access over a lossless XML tree. Every edit goes straight to the tree,
 * so whatever this class does not know about is kept untouched.
 */
class AcbfDocument(val xml: XmlDocument) {
    val root: XmlElement get() = xml.root

    init {
        require(root.localName == "ACBF") { "not an ACBF document: root element is <${root.name}>" }
    }

    val namespace: String? get() = root["xmlns"]

    /** "1.0", "1.1", "1.2" from the namespace; null when unrecognised. */
    val version: String? get() = namespace?.let { Regex("/(1\\.\\d+)/?$").find(it)?.groupValues?.get(1) }

    val bookInfo: XmlElement? get() = root.path("meta-data/book-info")
    val body: XmlElement? get() = root.element("body")

    /** The cover first, then every body page: the order ACBF readers show them in. */
    val pages: List<AcbfPage>
        get() = buildList {
            bookInfo?.element("coverpage")?.let { add(AcbfPage(this@AcbfDocument, it, isCover = true)) }
            body?.elements("page")?.forEach { add(AcbfPage(this@AcbfDocument, it, isCover = false)) }
        }

    /** Book titles by language; the key is null for a title without a lang attribute. */
    val titles: Map<String?, String>
        get() = bookInfo?.elements("book-title")?.associate { it["lang"] to it.textContent.trim() }.orEmpty()

    /** Text layers declared in book-info/languages, in order. */
    val languages: List<LanguageLayer>
        get() = bookInfo?.element("languages")?.elements("text-layer")
            ?.map { LanguageLayer(it["lang"].orEmpty(), it["show"]?.equals("false", ignoreCase = true) != true) }
            ?.toList().orEmpty()

    /** "LTR" or "RTL" (ACBF 1.2 proposal); LTR when missing. */
    val readingDirection: String
        get() = bookInfo?.element("reading-direction")?.textContent?.trim()?.uppercase()?.takeIf { it == "RTL" } ?: "LTR"

    /** Embedded binaries (`<data><binary id=…>`), by id. */
    val binaries: Map<String, XmlElement>
        get() = root.element("data")?.elements("binary")?.mapNotNull { b -> b["id"]?.let { it to b } }?.toMap().orEmpty()

    fun write(): ByteArray = xml.toBytes()

    companion object {
        fun parse(bytes: ByteArray): AcbfDocument = AcbfDocument(XmlParser.parse(bytes))

        /**
         * A new document for a comic that has only images: the first image is the cover, the
         * others are pages. Laid out the way lxml's pretty printer (ACBF Editor) writes it.
         */
        fun create(title: String, images: List<String>): AcbfDocument {
            fun attr(s: String) = acbf.xml.escapeAttribute(s, '"')
            fun text(s: String) = acbf.xml.escapeText(s)
            val xml = buildString {
                append("<?xml version='1.0' encoding='UTF-8'?>\n")
                append("<ACBF xmlns=\"${AcbfNamespaces.DEFAULT}\">\n")
                append("  <meta-data>\n")
                append("    <book-info>\n")
                append("      <book-title>${text(title)}</book-title>\n")
                append("      <coverpage>\n")
                images.firstOrNull()?.let { append("        <image href=\"${attr(it)}\"/>\n") }
                append("      </coverpage>\n")
                append("    </book-info>\n")
                append("    <publish-info/>\n")
                append("    <document-info/>\n")
                append("  </meta-data>\n")
                append("  <body>\n")
                for (img in images.drop(1)) {
                    append("    <page>\n")
                    append("      <image href=\"${attr(img)}\"/>\n")
                    append("    </page>\n")
                }
                append("  </body>\n")
                append("</ACBF>\n")
            }
            return AcbfDocument(XmlParser.parse(xml))
        }
    }
}

data class LanguageLayer(val lang: String, val show: Boolean)

class AcbfPage internal constructor(val document: AcbfDocument, val element: XmlElement, val isCover: Boolean) {
    val imageHref: String? get() = element.element("image")?.get("href")

    val bgcolor: String? get() = element["bgcolor"]

    /** Chapter titles by language. */
    val titles: Map<String?, String>
        get() = element.elements("title").associate { it["lang"] to it.textContent.trim() }

    val frames: List<AcbfFrame>
        get() = element.elements("frame").map { AcbfFrame(this, it) }.toList()

    /**
     * Adds a frame at reading position [index] (default: last). A new frame goes after the
     * existing ones; on a page without frames, after the last child, as ACBF Editor does.
     */
    fun addFrame(polygon: Polygon, index: Int = frames.size): AcbfFrame {
        val current = element.elements("frame").toList()
        require(index in 0..current.size)
        val e = XmlElement(qualified("frame"))
        e["points"] = polygon.format()
        val lineBreak = document.xml.lineBreak
        when {
            current.isEmpty() -> element.appendElement(e, lineBreak)
            index == current.size -> element.insertElement(e, current.last(), lineBreak)
            else -> {
                // Before frame [index]: after its previous element sibling.
                val target = current[index]
                val before = element.elements.takeWhile { it !== target }.lastOrNull()
                element.insertElement(e, before, lineBreak)
            }
        }
        return AcbfFrame(this, e)
    }

    fun removeFrame(frame: AcbfFrame) = element.removeElement(frame.element)

    /** Moves a frame to reading position [to]. */
    fun moveFrame(frame: AcbfFrame, to: Int) {
        val moved = frame.element
        element.removeElement(moved)
        val remaining = element.elements("frame").toList()
        val lineBreak = document.xml.lineBreak
        when {
            remaining.isEmpty() -> element.appendElement(moved, lineBreak)
            to >= remaining.size -> element.insertElement(moved, remaining.last(), lineBreak)
            else -> {
                val target = remaining[to.coerceAtLeast(0)]
                val before = element.elements.takeWhile { it !== target }.lastOrNull()
                element.insertElement(moved, before, lineBreak)
            }
        }
    }

    /** New elements take the parent's prefix, if it uses one. */
    private fun qualified(local: String): String =
        if (':' in element.name) element.name.substringBefore(':') + ":" + local else local
}

class AcbfFrame internal constructor(val page: AcbfPage, val element: XmlElement) {
    /** Null when the points attribute is missing or unreadable; such frames are kept as they are. */
    var polygon: Polygon?
        get() = Polygon.parse(element["points"])
        set(value) {
            if (value != null && value != polygon) element["points"] = value.format()
        }

    var bgcolor: String?
        get() = element["bgcolor"]
        set(value) {
            element["bgcolor"] = value
        }

    override fun equals(other: Any?) = other is AcbfFrame && other.element === element
    override fun hashCode() = element.hashCode()
}
