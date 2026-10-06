import tessera.app.SuperResSidecar
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** Results beside the book and in the cache: a copy of the book elsewhere finds them (2026-10-07). */
class SuperResSidecarTest {
    @Test
    fun aCopiedBookFindsItsResults() {
        val root = Files.createTempDirectory("tessera-sidecar").toFile()
        try {
            val cache = File(root, "cache")
            val first = File(root, "a/Book.cbz").apply { parentFile.mkdirs(); writeText("book") }
            val copy = File(root, "b/Book.cbz").apply { parentFile.mkdirs(); writeText("book") }
            SuperResSidecar(first, cache).save("k", byteArrayOf(1, 2, 3))
            assertTrue(File(root, "a/Book.cbz.tessera/real-esrgan-x2/k.jpg").isFile, "beside the book")
            assertTrue(File(cache, "k.jpg").isFile, "in the cache")
            val elsewhere = SuperResSidecar(copy, cache)
            assertTrue(elsewhere.exists("k"))
            assertContentEquals(byteArrayOf(1, 2, 3), elsewhere.load("k"))
        } finally { root.deleteRecursively() }
    }
}
