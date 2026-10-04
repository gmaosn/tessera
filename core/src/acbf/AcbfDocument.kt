package tessera.acbf

import tessera.xml.XmlDocument
import tessera.xml.XmlElement
import tessera.xml.XmlParser
import tessera.xml.appendElement
import tessera.xml.insertElement
import tessera.xml.removeElement

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

        /** A new document for a comic that has only images, with nothing but its title. */
        fun create(title: String, images: List<String>): AcbfDocument = create(NewBook(title = title), images)

        /**
         * A new document for a comic that has only images: the first image is the cover, the others
         * are pages. Only what [book] gives is written, so nothing is invented; the result is valid
         * against the ACBF 1.1 schema. Laid out the way lxml's pretty printer (ACBF Editor) writes it.
         */
        @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
        fun create(book: NewBook, images: List<String>): AcbfDocument {
            fun attr(s: String) = tessera.xml.escapeAttribute(s, '"')
            fun text(s: String) = tessera.xml.escapeText(s)
            fun StringBuilder.author(a: Person, indent: String) {
                append(indent).append("<author")
                a.activity?.let { append(" activity=\"${attr(it)}\"") }
                append(">\n")
                if (a.firstName.isNotBlank() && a.lastName.isNotBlank()) {
                    append("$indent  <first-name>${text(a.firstName)}</first-name>\n")
                    append("$indent  <last-name>${text(a.lastName)}</last-name>\n")
                } else {
                    append("$indent  <nickname>${text(a.nickname.ifBlank { a.firstName + a.lastName })}</nickname>\n")
                }
                append(indent).append("</author>\n")
            }
            val lang = book.language.takeIf { it.isNotBlank() }?.let { " lang=\"${attr(it)}\"" }.orEmpty()
            val xml = buildString {
                append("<?xml version='1.0' encoding='UTF-8'?>\n")
                append("<ACBF xmlns=\"${AcbfNamespaces.DEFAULT}\">\n")
                append("  <meta-data>\n")
                append("    <book-info>\n")
                book.authors.filter { !it.isEmpty }.forEach { author(it, "      ") }
                append("      <book-title$lang>${text(book.title)}</book-title>\n")
                append("      <genre>${text(book.genre)}</genre>\n")
                if (book.annotation.isNotBlank()) {
                    append("      <annotation$lang>\n")
                    book.annotation.split('\n').filter { it.isNotBlank() }.forEach { append("        <p>${text(it.trim())}</p>\n") }
                    append("      </annotation>\n")
                }
                append("      <coverpage>\n")
                images.firstOrNull()?.let { append("        <image href=\"${attr(it)}\"/>\n") }
                append("      </coverpage>\n")
                append("    </book-info>\n")
                if (book.publisher.isNotBlank() || book.publishDate.isNotBlank()) {
                    append("    <publish-info>\n")
                    if (book.publisher.isNotBlank()) append("      <publisher>${text(book.publisher)}</publisher>\n")
                    if (book.publishDate.isNotBlank()) append("      <publish-date value=\"${attr(book.publishDate)}\">${text(book.publishDate)}</publish-date>\n")
                    append("    </publish-info>\n")
                }
                append("    <document-info>\n")
                book.documentAuthor?.takeIf { !it.isEmpty }?.let { author(it, "      ") }
                if (book.creationDate.isNotBlank()) append("      <creation-date value=\"${attr(book.creationDate)}\">${text(book.creationDate)}</creation-date>\n")
                append("      <id>${kotlin.uuid.Uuid.random()}</id>\n")
                append("      <version>1.0</version>\n")
                append("    </document-info>\n")
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

/** A person in book-info or document-info: first and last name, or a nickname alone. */
data class Person(
    val firstName: String = "",
    val lastName: String = "",
    val nickname: String = "",
    /** One of ACBF's activities (Writer, Artist, …), or null. */
    val activity: String? = null,
) {
    val isEmpty: Boolean get() = firstName.isBlank() && lastName.isBlank() && nickname.isBlank()

    companion object {
        /** "Ali Almossawi" gives first and last name; a single word is kept as a nickname. */
        fun fromName(name: String, activity: String? = null): Person {
            val n = name.trim().replace(Regex("\\s+"), " ")
            val cut = n.lastIndexOf(' ')
            return if (cut > 0) Person(n.substring(0, cut), n.substring(cut + 1), activity = activity) else Person(nickname = n, activity = activity)
        }
    }
}

/** What the user tells about a comic that had no ACBF document. Blank fields are left out. */
data class NewBook(
    val title: String,
    val authors: List<Person> = emptyList(),
    /** One of [GENRES]. */
    val genre: String = "other",
    val annotation: String = "",
    /**
     * ISO 639-1 code of the book's language, or blank: set on the title and annotation. No
     * `languages` block is written before there are text layers; ACBF Viewer requires `show`.
     */
    val language: String = "",
    val publisher: String = "",
    /** YYYY-MM-DD, or blank. */
    val publishDate: String = "",
    /** Whoever made this ACBF document. */
    val documentAuthor: Person? = null,
    /** YYYY-MM-DD: today, given by the caller. */
    val creationDate: String = "",
) {
    companion object {
        /** The genres of the ACBF 1.1 schema. */
        val GENRES = listOf(
            "science_fiction", "fantasy", "adventure", "horror", "mystery", "crime", "military", "real_life", "superhero",
            "humor", "western", "manga", "politics", "caricature", "sports", "history", "biography", "education", "computer",
            "religion", "romance", "children", "non-fiction", "adult", "alternative", "other",
        )
    }
}

/** A frame element and its `points` text, as remembered for undo. */
class FrameState(val element: XmlElement, val points: String?)

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
        require(index in 0..frames.size)
        val e = XmlElement(qualified("frame"))
        e["points"] = polygon.format()
        placeFrame(e, index)
        return AcbfFrame(this, e)
    }

    fun removeFrame(frame: AcbfFrame) = element.removeElement(frame.element)

    /** Moves a frame to reading position [to]. */
    fun moveFrame(frame: AcbfFrame, to: Int) {
        element.removeElement(frame.element)
        placeFrame(frame.element, to)
    }

    /** What [restoreFrames] needs to bring the frames back: each element with its points. */
    fun frameState(): List<FrameState> = element.elements("frame").map { FrameState(it, it["points"]) }.toList()

    /**
     * Brings the frames back to an earlier [frameState]: frames that were added since are
     * removed, removed ones come back as the same elements, order and points are restored.
     * Frames that did not move keep their place and their text untouched.
     */
    fun restoreFrames(state: List<FrameState>) {
        val wanted = state.map { it.element }.toSet()
        for (e in element.elements("frame").toList()) if (e !in wanted) element.removeElement(e)
        state.forEachIndexed { i, s ->
            val current = element.elements("frame").toList()
            if (current.getOrNull(i) !== s.element) {
                if (s.element.parent === element) element.removeElement(s.element)
                placeFrame(s.element, i)
            }
            s.element["points"] = s.points
        }
    }

    /** Inserts a detached frame element at reading position [index], with matching indentation. */
    private fun placeFrame(e: XmlElement, index: Int) {
        val current = element.elements("frame").toList()
        val lineBreak = document.xml.lineBreak
        when {
            current.isEmpty() -> element.appendElement(e, lineBreak)
            index >= current.size -> element.insertElement(e, current.last(), lineBreak)
            else -> {
                // Before frame [index]: after its previous element sibling.
                val target = current[index.coerceAtLeast(0)]
                val before = element.elements.takeWhile { it !== target }.lastOrNull()
                element.insertElement(e, before, lineBreak)
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
