import tessera.acbf.AcbfDocument
import tessera.acbf.Author
import tessera.acbf.ContentRating
import tessera.acbf.Genre
import tessera.acbf.Metadata
import tessera.acbf.Section
import tessera.acbf.Sequence
import java.io.File
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetadataTest {
    private val root = run {
        var dir = File("").absoluteFile
        while (!File(dir, "project.yaml").exists()) dir = dir.parentFile
        dir
    }
    private val corpus = File(root, "fixtures/acbf-xml").listFiles { f -> f.name.endsWith(".acbf") }!!.sortedBy { it.name }
    private fun book(prefix: String) = corpus.first { it.name.startsWith(prefix) }

    /** Writes every field back with the value it has: the file must not change at all. */
    private fun rewriteEverything(m: Metadata) {
        for (s in Section.entries) {
            for (local in listOf("book-title", "keywords", "publisher", "city", "isbn", "license", "id", "version"))
                m.texts(s, local).forEach { (lang, text) -> m.setText(s, local, text, lang) }
            for (local in listOf("publish-date", "creation-date")) m.date(s, local).let { (t, v) -> if (t.isNotEmpty() || v.isNotEmpty()) m.setDate(s, local, t, v) }
            for (local in listOf("annotation", "source", "history"))
                m.texts(s, local).keys.forEach { lang -> m.setParagraphs(s, local, m.paragraphs(s, local, lang), lang) }
            m.authors(s).forEach { m.writeAuthor(it, m.readAuthor(it)) }
        }
        m.setGenres(m.genres()); m.setCharacters(m.characters()); m.setSequences(m.sequences())
        m.setDatabaseRefs(m.databaseRefs()); m.setContentRatings(m.contentRatings())
    }

    @Test
    fun writingBackEveryFieldChangesNothing() {
        for (f in corpus) {
            val bytes = f.readBytes()
            val doc = AcbfDocument.parse(bytes)
            rewriteEverything(Metadata(doc))
            assertContentEquals(bytes, doc.write(), f.name)
        }
    }

    @Test
    fun aChangedFieldTouchesOnlyItsElement() {
        val f = book("Purple")
        val original = f.readText()
        val doc = AcbfDocument.parse(f.readBytes())
        val m = Metadata(doc)
        m.setText(Section.Publish, "publisher", "Minoan & Co")
        m.setText(Section.Book, "book-title", "La Griffe pourpre", "fr")
        m.writeAuthor(m.authors(Section.Book)[1], m.readAuthor(m.authors(Section.Book)[1]).copy(lastName = "Browne"))
        val text = doc.write().decodeToString()
        val expected = original
            .replace("<publisher>Minoan Publishing Corp.</publisher>", "<publisher>Minoan &amp; Co</publisher>")
            .replace("<last-name>Brown</last-name>", "<last-name>Browne</last-name>")
        // The French title is new: it goes after the existing titles, on its own line with the
        // indentation of book-info's children.
        assertEquals(expected, text.replace("\n   <book-title lang=\"fr\">La Griffe pourpre</book-title>", ""))
        assertEquals("La Griffe pourpre", Metadata(AcbfDocument.parse(doc.write())).text(Section.Book, "book-title", "fr"))
    }

    @Test
    fun sectionsAndFieldsAreCreatedWhereTheyBelongAndStayValid() {
        val doc = AcbfDocument.create("Sans titre", listOf("c.jpg", "p.jpg"))
        val m = Metadata(doc)
        m.addAuthor(Section.Book, Author(firstName = "Ana", lastName = "Lima", activity = "Artist"))
        m.setGenres(listOf(Genre("humor"), Genre("adventure", "30")))
        m.setParagraphs(Section.Book, "annotation", listOf("Un.", "Deux."), "fr")
        m.setCharacters(listOf("Pépé", "Mémé"))
        m.setSequences(listOf(Sequence("Saga", "2", "7")))
        m.setContentRatings(listOf(ContentRating("PEGI", "7")))
        m.setText(Section.Publish, "publisher", "Moi")
        m.setDate(Section.Publish, "publish-date", "Octobre 2026", "2026-10-01")
        m.setText(Section.Publish, "city", "Genève")
        m.setParagraphs(Section.Document, "history", listOf("1.0 – Cases tracées"))
        val schema = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(File(root, "fixtures/schema/acbf-1.1.xsd"))
        schema.newValidator().validate(StreamSource(doc.write().inputStream()))
        val text = doc.write().decodeToString()
        // Spec order inside book-info: author, title, genre, characters, annotation, …, sequence.
        val positions = listOf("<author", "<book-title", "<genre>humor", "<characters>", "<annotation", "<coverpage>", "<sequence", "<content-rating").map { text.indexOf(it) }
        assertEquals(positions.sorted(), positions, text)
        assertTrue("    <publish-info>\n      <publisher>Moi</publisher>\n      <publish-date value=\"2026-10-01\">Octobre 2026</publish-date>\n      <city>Genève</city>\n    </publish-info>" in text, text)
    }

    @Test
    fun removingWhatWasAddedGivesBackTheSameBytes() {
        val f = book("Doctorow, Cory - Craphound-1.1")
        val bytes = f.readBytes()
        val doc = AcbfDocument.parse(bytes)
        val m = Metadata(doc)
        val added = m.addAuthor(Section.Book, Author(nickname = "Someone"))
        m.removeAuthor(added)
        m.setGenres(m.genres() + Genre("western")); m.setGenres(m.genres().dropLast(1))
        assertContentEquals(bytes, doc.write())
    }

    @Test
    fun snapshotsRestoreSectionsExactlyAndKeepTheCoverPage() {
        for (f in corpus) {
            val bytes = f.readBytes()
            val doc = AcbfDocument.parse(bytes)
            val m = Metadata(doc)
            val cover = doc.pages.first().element
            val snaps = Section.entries.associateWith { m.snapshot(it) }
            m.setText(Section.Book, "book-title", "X")
            m.addAuthor(Section.Document, Author(nickname = "Y"))
            m.setText(Section.Publish, "isbn", "123")
            m.setParagraphs(Section.Book, "annotation", listOf("Z"), "en")
            for ((s, snap) in snaps) m.restore(s, snap)
            assertContentEquals(bytes, doc.write(), f.name)
            assertTrue(doc.pages.first().element === cover, "${f.name}: cover page element kept")
        }
    }
}
