import kotlinx.coroutines.async
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
        val first = ImageCache(comic(), store).apply { wanted = listOf("a.png") }
        val image = assertNotNull(first.enhanced("a.png", sr))
        assertEquals(120 to 80, image.width to image.height)
        assertEquals(1, store.saved.size)
        // A new session on the same book: no computation, the store answers.
        val second = ImageCache(comic(), store).apply { wanted = emptyList() }
        assertNotNull(second.enhanced("a.png", sr), "taken from the store even when not wanted")
        assertEquals(1, store.saved.size)
        assertTrue(store.loads >= 2)
    }

    @Test
    fun aPageNoLongerShownIsNotComputed() = runBlocking {
        val store = MemoryStore()
        val cache = ImageCache(comic(), store).apply { wanted = listOf("b.png") }
        assertNull(cache.enhanced("a.png", Enhancement(EnhanceMode.SuperRes)))
        assertTrue(store.saved.isEmpty())
    }

    /** Left once started (owner, 2026-10-07: reading on, nothing was ever finished), it still ends and is saved. */
    @Test
    fun aStartedComputationFinishesWhenLeft() = runBlocking {
        val large = ByteArrayOutputStream().also { out ->
            val img = BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB)
            img.createGraphics().apply { color = java.awt.Color.WHITE; fillRect(0, 0, 400, 300); color = java.awt.Color.BLACK; drawLine(5, 5, 395, 295); dispose() }
            ImageIO.write(img, "png", out)
        }.toByteArray()
        val container = object : Container {
            override val paths = listOf("a.png", "b.png")
            override fun read(path: String) = large
        }
        val store = MemoryStore()
        val cache = ImageCache(Comic(AcbfDocument.create("T", listOf("a.png", "b.png")), container, "t.acbf", generated = false), store)
            .apply { wanted = listOf("a.png") }
        val reader = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async { cache.enhanced("a.png", Enhancement(EnhanceMode.SuperRes)) }
        while ((cache.superResProgress["a.png"] ?: 0f) <= 0f) kotlinx.coroutines.delay(20)
        // The reader moves on: another page shown, the waiting caller gone.
        cache.wanted = listOf("b.png")
        reader.cancel()
        val start = System.currentTimeMillis()
        while (store.saved.isEmpty() && System.currentTimeMillis() - start < 180_000) kotlinx.coroutines.delay(100)
        assertEquals(1, store.saved.size, "the computation left was not finished")
    }

    @Test
    fun highDefinitionPagesAreLeftAsTheyAre() = runBlocking {
        val big = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(2200, 2200, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        val container = object : Container {
            override val paths = listOf("a.png", "b.png")
            override fun read(path: String) = big
        }
        val store = MemoryStore()
        val cache = ImageCache(Comic(AcbfDocument.create("T", listOf("a.png", "b.png")), container, "t.acbf", generated = false), store)
            .apply { wanted = listOf("a.png") }
        assertNull(cache.enhanced("a.png", Enhancement(EnhanceMode.SuperRes)))
        assertNull(cache.enhanced("a.png", Enhancement(EnhanceMode.Restore)))
        assertTrue(cache.isHighDefinition("a.png"))
        assertTrue(store.saved.isEmpty(), "nothing computed")
        // Sharpening stays available: it keeps the size and is quick.
        assertNotNull(cache.enhanced("a.png", Enhancement(EnhanceMode.Sharpen, 0.5f)))
        Unit // a @Test returning a value is never run (it was skipped until 2026-10-07)
    }

    @Test
    fun thePageShownGoesFirst() = runBlocking {
        val store = MemoryStore()
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val logging = object : SuperResStore by store {
            override fun save(key: String, bytes: ByteArray) { order += key; store.save(key, bytes) }
        }
        // "a" has more tiles than there are cores, so it can give way between them.
        val pngA = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(1300, 130, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        val pngB = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(50, 30, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        val container = object : Container {
            override val paths = listOf("a.png", "b.png")
            override fun read(path: String) = if (path == "a.png") pngA else pngB
        }
        val cache = ImageCache(Comic(AcbfDocument.create("T", listOf("a.png", "b.png")), container, "t.acbf", generated = false), logging)
        cache.wanted = listOf("b.png", "a.png")
        val sr = Enhancement(EnhanceMode.SuperRes)
        // The page prepared ahead ("a") starts first; the page shown ("b") asks while it runs.
        val a = async { cache.enhanced("a.png", sr) }
        kotlinx.coroutines.delay(300)
        val b = async { cache.enhanced("b.png", sr) }
        a.await(); b.await()
        assertEquals(2, order.size)
        assertTrue(order[0].endsWith("-${pngB.size}"), "the page shown was computed first: $order")
    }

    @Test
    fun framesOfHighDefinitionPagesAreEnhancedOneByOne() = runBlocking {
        val big = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(2200, 2200, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        val container = object : Container {
            override val paths = listOf("a.png", "b.png")
            override fun read(path: String) = big
        }
        val store = MemoryStore()
        val cache = ImageCache(Comic(AcbfDocument.create("T", listOf("a.png", "b.png")), container, "t.acbf", generated = false), store)
        val frame = tessera.editor.Region(100, 200, 120, 80)
        cache.wanted = listOf(frame.id("a.png"))
        val restored = assertNotNull(cache.enhancedRegion("a.png", frame, Enhancement(EnhanceMode.Restore)))
        assertEquals(240 to 160, restored.width to restored.height)
        val sr = assertNotNull(cache.enhancedRegion("a.png", frame, Enhancement(EnhanceMode.SuperRes)))
        assertEquals(240 to 160, sr.width to sr.height)
        assertEquals(1, store.saved.size)
        assertTrue(store.saved.keys.single().endsWith("-r100_200_120_80"), "${store.saved.keys}")
    }
}
