package tessera.acbf

import tessera.xml.XmlElement
import tessera.xml.XmlParser
import tessera.xml.insertOrdered
import tessera.xml.newChild
import tessera.xml.removeElement
import tessera.xml.replaceWith
import tessera.xml.setText

/** The three parts of `meta-data`, each with its elements in the order of the specification. */
enum class Section(val local: String, val order: List<String>) {
    Book(
        "book-info",
        listOf("author", "book-title", "genre", "characters", "annotation", "keywords", "coverpage", "languages", "sequence", "databaseref", "content-rating", "reading-direction"),
    ),
    Publish("publish-info", listOf("publisher", "publish-date", "city", "isbn", "license")),
    Document("document-info", listOf("author", "creation-date", "source", "id", "version", "history")),
}

/** An author as ACBF describes one. Blank fields are not written. */
data class Author(
    val firstName: String = "",
    val middleName: String = "",
    val lastName: String = "",
    val nickname: String = "",
    val homePage: String = "",
    val email: String = "",
    val activity: String? = null,
    val lang: String? = null,
) {
    val displayName: String
        get() = listOf(firstName, middleName, lastName).filter { it.isNotBlank() }.joinToString(" ").ifBlank { nickname }

    companion object {
        val ACTIVITIES = listOf(
            "Writer", "Adapter", "Artist", "Penciller", "Inker", "Colorist", "Letterer", "CoverArtist", "Photographer",
            "Editor", "AssistantEditor", "Translator", "Other",
        )
    }
}

data class Genre(val name: String, val match: String? = null)
data class Sequence(val title: String, val volume: String = "", val number: String = "")
data class DatabaseRef(val dbname: String, val type: String = "", val value: String = "")
data class ContentRating(val type: String = "", val value: String = "")

/**
 * Reading and editing a document's metadata in place. Every setter changes only what differs:
 * setting a field to its current value leaves the file byte for byte the same, and a changed
 * field touches only its own element. Missing elements are created where the specification
 * places them, with the document's indentation. A field emptied keeps its (empty) element, so
 * that clearing and retyping a value does not move it.
 */
class Metadata(val doc: AcbfDocument) {
    private companion object {
        val AUTHOR_ORDER = listOf("first-name", "middle-name", "last-name", "nickname", "home-page", "email")
        val NAMES = setOf("first-name", "last-name", "nickname")
    }

    private val lineBreak get() = doc.xml.lineBreak
    private val root get() = doc.root

    fun section(s: Section): XmlElement? = root.element("meta-data")?.element(s.local)

    private fun sectionOrCreate(s: Section): XmlElement {
        section(s)?.let { return it }
        val meta = root.element("meta-data") ?: root.newChild("meta-data").also { root.insertOrdered(it, listOf("style", "meta-data"), lineBreak) }
        return meta.newChild(s.local).also { meta.insertOrdered(it, Section.entries.map { e -> e.local }, lineBreak) }
    }

    private fun XmlElement.child(local: String, lang: String?): XmlElement? =
        elements(local).firstOrNull { it["lang"] == lang }

    private fun XmlElement.childOrCreate(s: Section, local: String, lang: String?): XmlElement =
        child(local, lang) ?: newChild(local).also { e ->
            if (lang != null) e["lang"] = lang
            insertOrdered(e, s.order, lineBreak)
        }

    // ----- Plain texts, optionally per language -----

    /** The text of [local] in [s] for [lang] (null: the element without a lang attribute). */
    fun text(s: Section, local: String, lang: String? = null): String =
        section(s)?.child(local, lang)?.textContent?.trim().orEmpty()

    /** Every language variant of [local]: lang (null when none) to text. */
    fun texts(s: Section, local: String): Map<String?, String> =
        section(s)?.elements(local)?.associate { it["lang"] to it.textContent.trim() }.orEmpty()

    fun setText(s: Section, local: String, value: String, lang: String? = null) {
        val existing = section(s)?.child(local, lang)
        if (existing == null && value.isBlank()) return
        if (existing != null && existing.textContent.trim() == value.trim()) return
        (existing ?: sectionOrCreate(s).childOrCreate(s, local, lang)).setText(value.trim())
    }

    // ----- Dates: a readable text and an ISO value -----

    fun date(s: Section, local: String): Pair<String, String> =
        section(s)?.element(local)?.let { it.textContent.trim() to (it["value"] ?: "") } ?: ("" to "")

    fun setDate(s: Section, local: String, text: String, iso: String) {
        val existing = section(s)?.element(local)
        if (existing == null && text.isBlank() && iso.isBlank()) return
        val e = existing ?: sectionOrCreate(s).childOrCreate(s, local, null)
        if (e.textContent.trim() != text.trim()) e.setText(text.trim())
        e["value"] = iso.ifBlank { null }
    }

    // ----- Paragraph blocks: annotation (per language), source, history -----

    fun paragraphs(s: Section, local: String, lang: String? = null): List<String> =
        section(s)?.child(local, lang)?.elements("p")?.map { it.textContent.trim() }?.toList().orEmpty()

    /**
     * Sets the paragraphs of [local]. Paragraphs whose text is unchanged keep their inline
     * markup (emphasis, links); changed ones become plain text. An empty list keeps one empty
     * paragraph, as the schema requires one.
     */
    fun setParagraphs(s: Section, local: String, paragraphs: List<String>, lang: String? = null) {
        val wanted = paragraphs.map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("") }
        val existing = section(s)?.child(local, lang)
        if (existing == null && wanted == listOf("")) return
        if (existing != null && paragraphs(s, local, lang).ifEmpty { listOf("") } == wanted) return
        val block = existing ?: sectionOrCreate(s).childOrCreate(s, local, lang)
        val ps = block.elements("p").toList()
        wanted.forEachIndexed { i, text ->
            val p = ps.getOrNull(i)
            if (p != null) {
                if (p.textContent.trim() != text) p.setText(text)
            } else {
                block.insertOrdered(block.newChild("p").also { it.setText(text) }, listOf("p"), lineBreak)
            }
        }
        for (p in ps.drop(wanted.size)) block.removeElement(p)
    }

    // ----- Authors -----

    fun authors(s: Section): List<XmlElement> = section(s)?.elements("author")?.toList().orEmpty()

    fun readAuthor(e: XmlElement): Author = Author(
        firstName = e.element("first-name")?.textContent?.trim().orEmpty(),
        middleName = e.element("middle-name")?.textContent?.trim().orEmpty(),
        lastName = e.element("last-name")?.textContent?.trim().orEmpty(),
        nickname = e.element("nickname")?.textContent?.trim().orEmpty(),
        homePage = e.element("home-page")?.textContent?.trim().orEmpty(),
        email = e.element("email")?.textContent?.trim().orEmpty(),
        activity = e["activity"],
        lang = e["lang"],
    )

    /**
     * Writes [a] into the author element [e]: changed names are updated, emptied ones removed,
     * new ones added in the schema's order. An author always keeps a name element (an empty
     * nickname if need be), as the schema requires one.
     */
    fun writeAuthor(e: XmlElement, a: Author) {
        if (readAuthor(e) == a && e.elements.any { it.localName in NAMES }) return
        for ((local, value) in AUTHOR_ORDER.zip(listOf(a.firstName, a.middleName, a.lastName, a.nickname, a.homePage, a.email))) {
            val child = e.element(local)
            when {
                child != null && child.textContent.trim() == value.trim() -> {}
                child != null && value.isBlank() -> e.removeElement(child)
                child != null -> child.setText(value.trim())
                value.isNotBlank() -> e.insertOrdered(e.newChild(local).also { it.setText(value.trim()) }, AUTHOR_ORDER, lineBreak)
            }
        }
        if (e.elements.none { it.localName in NAMES }) e.insertOrdered(e.newChild("nickname"), AUTHOR_ORDER, lineBreak)
        e["activity"] = a.activity?.ifBlank { null }
        e["lang"] = a.lang?.ifBlank { null }
    }

    fun addAuthor(s: Section, a: Author): XmlElement {
        val sec = sectionOrCreate(s)
        val e = sec.newChild("author")
        sec.insertOrdered(e, s.order, lineBreak)
        writeAuthor(e, a)
        return e
    }

    fun removeAuthor(e: XmlElement) {
        e.parent?.removeElement(e)
    }

    // ----- Repeated simple elements -----

    /** Matches [items] to the existing [local] elements by position: changed ones are updated, extras removed, new ones added after them. */
    private fun <T> setRepeated(parent: XmlElement, local: String, order: List<String>, items: List<T>, read: (XmlElement) -> T, write: (XmlElement, T) -> Unit) {
        val existing = parent.elements(local).toList()
        items.forEachIndexed { i, item ->
            val e = existing.getOrNull(i)
            if (e != null) {
                if (read(e) != item) write(e, item)
            } else {
                val n = parent.newChild(local)
                write(n, item)
                parent.insertOrdered(n, order, lineBreak)
            }
        }
        for (e in existing.drop(items.size)) parent.removeElement(e)
    }

    fun genres(): List<Genre> = section(Section.Book)?.elements("genre")?.map { Genre(it.textContent.trim(), it["match"]) }?.toList().orEmpty()

    fun setGenres(genres: List<Genre>) {
        if (genres == genres()) return
        setRepeated(sectionOrCreate(Section.Book), "genre", Section.Book.order, genres, { Genre(it.textContent.trim(), it["match"]) }) { e, g ->
            e.setText(g.name); e["match"] = g.match?.ifBlank { null }
        }
    }

    fun characters(): List<String> = section(Section.Book)?.element("characters")?.elements("name")?.map { it.textContent.trim() }?.toList().orEmpty()

    fun setCharacters(names: List<String>) {
        val wanted = names.map { it.trim() }.filter { it.isNotEmpty() }
        if (wanted == characters()) return
        val existing = section(Section.Book)?.element("characters")
        if (wanted.isEmpty()) {
            existing?.let { it.parent?.removeElement(it) }
            return
        }
        val block = existing ?: sectionOrCreate(Section.Book).childOrCreate(Section.Book, "characters", null)
        setRepeated(block, "name", listOf("name"), wanted, { it.textContent.trim() }) { e, n -> e.setText(n) }
    }

    fun sequences(): List<Sequence> = section(Section.Book)?.elements("sequence")?.map(::readSequence)?.toList().orEmpty()

    private fun readSequence(e: XmlElement) = Sequence(e["title"].orEmpty(), e["volume"].orEmpty(), e.textContent.trim())

    fun setSequences(items: List<Sequence>) {
        if (items == sequences()) return
        setRepeated(sectionOrCreate(Section.Book), "sequence", Section.Book.order, items, ::readSequence) { e, q ->
            e["title"] = q.title; e["volume"] = q.volume.ifBlank { null }
            if (e.textContent.trim() != q.number) e.setText(q.number)
        }
    }

    fun databaseRefs(): List<DatabaseRef> = section(Section.Book)?.elements("databaseref")?.map(::readRef)?.toList().orEmpty()

    private fun readRef(e: XmlElement) = DatabaseRef(e["dbname"].orEmpty(), e["type"].orEmpty(), e.textContent.trim())

    fun setDatabaseRefs(items: List<DatabaseRef>) {
        if (items == databaseRefs()) return
        setRepeated(sectionOrCreate(Section.Book), "databaseref", Section.Book.order, items, ::readRef) { e, r ->
            e["dbname"] = r.dbname; e["type"] = r.type.ifBlank { null }
            if (e.textContent.trim() != r.value) e.setText(r.value)
        }
    }

    fun contentRatings(): List<ContentRating> = section(Section.Book)?.elements("content-rating")?.map(::readRating)?.toList().orEmpty()

    private fun readRating(e: XmlElement) = ContentRating(e["type"].orEmpty(), e.textContent.trim())

    fun setContentRatings(items: List<ContentRating>) {
        if (items == contentRatings()) return
        setRepeated(sectionOrCreate(Section.Book), "content-rating", Section.Book.order, items, ::readRating) { e, r ->
            e["type"] = r.type.ifBlank { null }
            if (e.textContent.trim() != r.value) e.setText(r.value)
        }
    }

    // ----- Languages used for titles and annotations -----

    /** Every language the book's texts use: titles, annotations, keywords, declared text layers. */
    fun textLanguages(): List<String?> {
        val book = section(Section.Book) ?: return listOf(null)
        val langs = LinkedHashSet<String?>()
        for (local in listOf("book-title", "annotation", "keywords")) book.elements(local).forEach { langs += it["lang"] }
        book.element("languages")?.elements("text-layer")?.forEach { it["lang"]?.let { l -> langs += l } }
        return langs.toList().ifEmpty { listOf(null) }
    }

    // ----- Undo: section snapshots -----

    /** The section's exact text, or null when absent. */
    fun snapshot(s: Section): String? = section(s)?.toString()

    /**
     * Brings a section back to a [snapshot]. The cover page element is kept as the same object,
     * so that undo steps on the cover's frames stay valid.
     */
    fun restore(s: Section, snapshot: String?) {
        val live = section(s)
        if (snapshot == null) {
            live?.let { it.parent?.removeElement(it) }
            return
        }
        if (live?.toString() == snapshot) return
        val restored = XmlParser.parse(snapshot).root
        val liveCover = live?.element("coverpage")
        val oldCover = restored.element("coverpage")
        if (liveCover != null && oldCover != null) {
            val i = restored.indexOf(oldCover)
            restored.remove(oldCover)
            live.remove(liveCover)
            restored.insert(i, liveCover)
        }
        if (live != null) {
            live.replaceWith(restored)
        } else {
            val meta = root.element("meta-data") ?: return
            meta.insertOrdered(restored, Section.entries.map { it.local }, lineBreak)
        }
    }
}
