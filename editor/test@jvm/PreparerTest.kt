import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import tessera.acbf.AcbfDocument
import tessera.acbf.Comic
import tessera.acbf.Container
import tessera.acbf.Polygon
import tessera.editor.ImageCache
import tessera.editor.Preparer
import tessera.editor.Session
import tessera.editor.enhance.SuperResStore
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreparerTest {
    private class MemoryStore : SuperResStore {
        val saved = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
        override fun load(key: String) = saved[key]
        override fun save(key: String, bytes: ByteArray) { saved[key] = bytes }
        override val place = "memory"
    }

    private fun png(w: Int, h: Int, seed: Int) = ByteArrayOutputStream().also { out ->
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        img.setRGB(seed % w, 1, 0xFFFFFF)
        ImageIO.write(img, "png", out)
    }.toByteArray()

    @Test
    fun preparesTheWholeBookInTheStore() {
        val pages = mapOf("c.png" to png(40, 30, 1), "p1.png" to png(40, 30, 2), "p2.png" to png(40, 30, 3))
        val container = object : Container {
            override val paths = pages.keys.toList()
            override fun read(path: String) = pages[path]
        }
        val doc = AcbfDocument.create("T", listOf("c.png", "p1.png", "p2.png"))
        doc.pages[1].addFrame(Polygon.rectangle(0, 0, 20, 30))
        doc.pages[1].addFrame(Polygon.rectangle(20, 0, 40, 30))
        val comic = Comic(doc, container, "t.acbf", generated = false)
        val store = MemoryStore()
        val ui = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        runBlocking {
            withContext(ui) {
                val preparer = Preparer(ImageCache(comic, store), Session(comic, "t.cbz"), ui)
                preparer.book()
                // Cover 1 + page with two frames 2 + page 1: four frames, three whole pages.
                assertEquals(4, preparer.total)
                assertTrue(preparer.wholeBook)
            }
            var waited = 0
            while (store.saved.size < 3 && waited < 600) { Thread.sleep(100); waited++ }
            assertEquals(3, store.saved.size, "every page computed once")
        }
        ui.close()
    }

    @Test
    fun stopsOnRequest() {
        val big = png(1300, 130, 5)
        val container = object : Container {
            override val paths = listOf("c.png", "p.png")
            override fun read(path: String) = big
        }
        val comic = Comic(AcbfDocument.create("T", listOf("c.png", "p.png")), container, "t.acbf", generated = false)
        val ui = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        runBlocking {
            withContext(ui) {
                val preparer = Preparer(ImageCache(comic, MemoryStore()), Session(comic, "t.cbz"), ui)
                preparer.book()
                assertTrue(preparer.active)
                preparer.stop()
                assertFalse(preparer.active)
            }
        }
        ui.close()
    }
}
