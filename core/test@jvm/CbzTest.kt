import acbf.Comic
import acbf.ComicFiles
import acbf.Polygon
import acbf.zip.ByteArraySource
import acbf.zip.ByteSink
import acbf.zip.ZipArchive
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CbzTest {
    private val root: File = run {
        var dir = File("").absoluteFile
        while (!File(dir, "project.yaml").exists()) dir = dir.parentFile
        dir
    }

    /** The official sample books; fetched by tools/fetch-fixtures.sh, too large for git. */
    private val samples: List<File> =
        File(root, "fixtures/samples").listFiles { f -> f.extension == "cbz" }?.sortedBy { it.name }.orEmpty()

    private fun plainCbz(): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { z ->
            // Deflated entries from ZipOutputStream carry data descriptors.
            for (name in listOf("page10.jpg", "page2.jpg", "Bände/page1.png", "notes.txt")) {
                z.putNextEntry(ZipEntry(name)); z.write("image $name".encodeToByteArray()); z.closeEntry()
            }
            z.putNextEntry(ZipEntry("stored.jpg").apply {
                method = ZipEntry.STORED; size = 3; compressedSize = 3
                crc = java.util.zip.CRC32().apply { update(byteArrayOf(1, 2, 3)) }.value
            }); z.write(byteArrayOf(1, 2, 3)); z.closeEntry()
        }
    }.toByteArray()

    private fun rewrite(comic: Comic): ByteArray = ByteArrayOutputStream().also { out ->
        comic.writeCbz(object : ByteSink {
            override fun write(bytes: ByteArray) = out.write(bytes)
        })
    }.toByteArray()

    @Test
    fun aPlainCbzGetsADocumentInNaturalOrder() {
        val comic = Comic.openCbz(ZipArchive(ByteArraySource(plainCbz())), "My comic.cbz")
        assertTrue(comic.generated)
        assertEquals(listOf("Bände/page1.png", "page2.jpg", "page10.jpg", "stored.jpg"), comic.document.pages.map { it.imageHref })
        assertContentEquals("image page2.jpg".encodeToByteArray(), comic.image("page2.jpg"))
        assertContentEquals(byteArrayOf(1, 2, 3), comic.image("stored.jpg"))
        assertEquals("My comic", comic.document.titles[null])
    }

    @Test
    fun savingAddsTheDocumentAndCopiesEverythingElseRaw() {
        val original = plainCbz()
        val comic = Comic.openCbz(ZipArchive(ByteArraySource(original)), "c.cbz")
        comic.document.pages[1].addFrame(Polygon.rectangle(0, 0, 10, 10))
        val saved = rewrite(comic)
        // Readable by an independent ZIP implementation.
        val f = File.createTempFile("acbf", ".cbz").apply { writeBytes(saved); deleteOnExit() }
        ZipFile(f).use { z ->
            assertEquals(listOf("page10.jpg", "page2.jpg", "Bände/page1.png", "notes.txt", "stored.jpg", "c.acbf"), z.entries().toList().map { it.name })
            assertContentEquals("image notes.txt".encodeToByteArray(), z.getInputStream(z.getEntry("notes.txt")).readBytes())
            val acbf = z.getInputStream(z.getEntry("c.acbf")).readBytes().decodeToString()
            assertTrue("<frame points=\"0,0 10,0 10,10 0,10\"/>" in acbf, acbf)
        }
        // The copied entries are the very same bytes as before.
        val before = ZipArchive(ByteArraySource(original))
        val after = ZipArchive(ByteArraySource(saved))
        for (e in before.entries) assertContentEquals(before.read(e), after.read(after.entry(e.name)!!), e.name)
        val reopened = Comic.openCbz(after, "c.cbz")
        assertTrue(!reopened.generated)
        assertEquals(1, reopened.document.pages[1].frames.size)
    }

    @Test
    fun everySampleBookOpensWithAllItsImages() {
        if (samples.isEmpty()) return println("fixtures/samples missing: run tools/fetch-fixtures.sh")
        for (f in samples) {
            val comic = ComicFiles.open(f)
            assertTrue(!comic.generated, f.name)
            for (page in comic.document.pages) assertNotNull(comic.image(page.imageHref), "${f.name}: ${page.imageHref}")
        }
    }

    @Test
    fun aSampleBookSurvivesAnEditAndSave() {
        val source = samples.firstOrNull { it.name.startsWith("Revoy") } ?: return
        val copy = File.createTempFile("acbf", ".cbz").apply { deleteOnExit() }
        source.copyTo(copy, overwrite = true)
        val comic = ComicFiles.open(copy)
        val page = comic.document.pages[2]
        val n = page.frames.size
        page.addFrame(Polygon.rectangle(5, 5, 50, 50))
        val reopened = ComicFiles.save(comic, copy)
        assertEquals(n + 1, reopened.document.pages[2].frames.size)
        ZipFile(source).use { a ->
            ZipFile(copy).use { b ->
                assertEquals(a.entries().toList().map { it.name }, b.entries().toList().map { it.name })
                for (e in a.entries()) if (!e.name.endsWith(".acbf")) assertEquals(e.crc, b.getEntry(e.name).crc, e.name)
            }
        }
        // Saving again without changes keeps the document byte for byte.
        val first = copy.readBytes()
        ComicFiles.save(reopened, copy)
        val a = ZipArchive(ByteArraySource(first))
        val b = ZipArchive(ByteArraySource(copy.readBytes()))
        val name = a.entries.first { it.name.endsWith(".acbf") }.name
        assertContentEquals(a.read(a.entry(name)!!), b.read(b.entry(name)!!))
    }
}
