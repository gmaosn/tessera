package tessera.app

import tessera.editor.enhance.SuperResStore
import java.io.File

/**
 * Keeps Real-ESRGAN's results beside the book, in "Book.cbz.tessera/real-esrgan-x2/", one JPEG
 * per page image, named after its content (so renaming or reordering pages does not matter), and
 * also in the user's cache folder: a book moved or copied elsewhere finds what was computed for
 * any copy of it (owner, 2026-10-07). Either place may be unwritable; the other one serves.
 */
class SuperResSidecar(
    book: File,
    private val cache: File = File(
        System.getProperty("user.home"),
        if (System.getProperty("os.name").lowercase().contains("mac")) "Library/Caches/Tessera/real-esrgan-x2" else ".cache/tessera/real-esrgan-x2",
    ),
) : SuperResStore {
    private val beside = File(book.absoluteFile.parentFile, "${book.name}.tessera/real-esrgan-x2")

    override val place: String = "${book.name}.tessera"

    override fun load(key: String): ByteArray? =
        listOf(beside, cache).map { File(it, "$key.jpg") }.firstOrNull { it.isFile }?.readBytes()

    override fun exists(key: String): Boolean = listOf(beside, cache).any { File(it, "$key.jpg").isFile }

    override fun save(key: String, bytes: ByteArray) {
        for (dir in listOf(beside, cache)) {
            if (File(dir, "$key.jpg").isFile) continue
            runCatching {
                dir.mkdirs()
                val temp = File.createTempFile(".$key.", ".tmp", dir)
                temp.writeBytes(bytes)
                if (!temp.renameTo(File(dir, "$key.jpg"))) { temp.delete(); error("rename") }
            }
        }
    }
}
