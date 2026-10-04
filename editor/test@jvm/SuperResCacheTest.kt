import kotlinx.coroutines.runBlocking
import tessera.acbf.AcbfDocument
import tessera.acbf.Comic
import tessera.acbf.Container
import tessera.editor.ImageCache
import tessera.editor.enhance.EnhanceMode
import tessera.editor.enhance.Enhancement
import tessera.editor.enhance.SuperResStore
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SuperResCacheTest {
    private class MemoryStore : SuperResStore {
        val saved = HashMap<String, ByteArray>()
        var loads = 0
        override fun load(key: String) = saved[key].also { loads++ }
        override fun save(key: String, bytes: ByteArray) { saved[key] = bytes }
        override val place = "memory"
    }

    private val png = ByteArrayOutputStream().also { out ->
        val img = BufferedImage(60, 40, BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply { color = java.awt.Color.WHITE; fillRect(0, 0, 60, 40); color = java.awt.Color.BLACK; drawLine(5, 5, 55, 35); dispose() }
        ImageIO.write(img, "png", out)
    }.toByteArray()

    private fun comic(): Comic {
        val container = object : Container {
            override val paths = listOf("a.png", "b.png")
            override fun read(path: String) = png
        }
        return Comic(AcbfDocument.create("T", listOf("a.png", "b.png")), container, "t.acbf", generated = false)
    }

    @Test
    fun computedOnceThenTakenFromTheStore() = runBlocking {
        val store = MemoryStore()
        val sr = Enhancement(EnhanceMode.SuperRes, 0f, 1f)
        val first = ImageCache(comic(), store).apply { wanted = setOf("a.png") }
        val image = assertNotNull(first.enhanced("a.png", sr))
        assertEquals(120 to 80, image.width to image.height)
        assertEquals(1, store.saved.size)
        // A new session on the same book: no computation, the store answers.
        val second = ImageCache(comic(), store).apply { wanted = emptySet() }
        assertNotNull(second.enhanced("a.png", sr), "taken from the store even when not wanted")
        assertEquals(1, store.saved.size)
        assertTrue(store.loads >= 2)
    }

    @Test
    fun aPageNoLongerShownIsNotComputed() = runBlocking {
        val store = MemoryStore()
        val cache = ImageCache(comic(), store).apply { wanted = setOf("b.png") }
        assertNull(cache.enhanced("a.png", Enhancement(EnhanceMode.SuperRes)))
        assertTrue(store.saved.isEmpty())
    }
}
