package tessera.editor.enhance

/**
 * Where Real-ESRGAN's results are kept, so that each page is computed once: on the desktop, a
 * folder beside the book ("Book.cbz.tessera"). Keys identify a page's image by its content.
 */
interface SuperResStore {
    fun load(key: String): ByteArray?
    fun save(key: String, bytes: ByteArray)

    /** True when [key] is stored; cheaper than [load] when the bytes are not needed. */
    fun exists(key: String): Boolean = load(key) != null

    /** A short description of where results go, for the settings panel. */
    val place: String
}

/** A JPEG of [image] at the given quality, for the store. */
expect fun encodeJpeg(image: Argb, quality: Int): ByteArray

/** Decodes a JPEG (or any image) back into pixels. */
expect fun decodeArgb(bytes: ByteArray): Argb?
