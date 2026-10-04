import tessera.acbf.AcbfDocument
import tessera.acbf.NewBook
import tessera.acbf.Person
import java.io.File
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Documents Tessera creates must be valid against the official ACBF 1.1 schema. */
class SchemaTest {
    private val schema = run {
        var dir = File("").absoluteFile
        while (!File(dir, "project.yaml").exists()) dir = dir.parentFile
        SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(File(dir, "fixtures/schema/acbf-1.1.xsd"))
    }

    private fun validate(doc: AcbfDocument) = schema.newValidator().validate(StreamSource(doc.write().inputStream()))

    @Test
    fun aDocumentWithOnlyATitleIsValid() = validate(AcbfDocument.create("Untitled", listOf("a.jpg", "b.jpg")))

    @Test
    fun aFullDocumentIsValidAndReadsBack() {
        val doc = AcbfDocument.create(
            NewBook(
                title = "L’Été & les <cases>",
                authors = listOf(Person.fromName("Ali Almossawi", "Writer"), Person.fromName("Alejandro")),
                genre = "humor",
                annotation = "First line.\nSecond line.",
                language = "fr",
                publisher = "Moi",
                publishDate = "2026-10-04",
                documentAuthor = Person.fromName("Alex Martin"),
                creationDate = "2026-10-04",
            ),
            listOf("cover.jpg", "1.jpg"),
        )
        validate(doc)
        assertEquals("L’Été & les <cases>", doc.titles["fr"])
        val text = doc.write().decodeToString()
        assertTrue("<first-name>Ali</first-name>" in text && "<nickname>Alejandro</nickname>" in text, text)
        assertTrue("<creation-date value=\"2026-10-04\">" in text, text)
        assertEquals(2, doc.pages.size)
    }

    @Test
    fun genresMatchTheSchema() {
        val xsd = File(File("").absoluteFile.let { var d = it; while (!File(d, "project.yaml").exists()) d = d.parentFile; d }, "fixtures/schema/acbf-1.1.xsd").readText()
        val section = xsd.substringAfter("name=\"genreType\"").substringBefore("</xs:simpleType>")
        assertEquals(Regex("value=\"([^\"]+)\"").findAll(section).map { it.groupValues[1] }.toList(), NewBook.GENRES)
    }
}
