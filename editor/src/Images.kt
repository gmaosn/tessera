package tessera.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tessera.acbf.Comic

/** Decodes JPEG, PNG, WebP, GIF or BMP; null when the bytes are not an image. */
expect fun decodeImage(bytes: ByteArray): ImageBitmap?

/** Decodes and scales down to [width] pixels wide, for page thumbnails. */
expect fun decodeThumbnail(bytes: ByteArray, width: Int): ImageBitmap?

/**
 * Page images, decoded off the main thread and cached: a few full pages (the current one and its
 * neighbours) and every thumbnail. Used from the UI thread only; decoding runs elsewhere.
 */
class ImageCache(var comic: Comic) {
    /** Least recently used first. */
    private val full = LinkedHashMap<String, ImageBitmap?>()
    private val thumbs = HashMap<String, ImageBitmap?>()

    fun cachedPage(href: String?): ImageBitmap? = href?.let { full[it] }

    suspend fun page(href: String?): ImageBitmap? {
        if (href == null) return null
        if (full.containsKey(href)) return full.remove(href).also { full[href] = it }
        val image = withContext(Dispatchers.Default) { comic.image(href)?.let(::decodeImage) }
        full[href] = image
        while (full.size > 5) full.remove(full.keys.first())
        return image
    }

    suspend fun thumbnail(href: String?): ImageBitmap? {
        if (href == null) return null
        if (thumbs.containsKey(href)) return thumbs[href]
        val image = withContext(Dispatchers.Default) { comic.image(href)?.let { decodeThumbnail(it, THUMB_WIDTH) } }
        thumbs[href] = image
        return image
    }

    companion object {
        /** Twice the strip's width, for sharp thumbnails on high-density screens. */
        const val THUMB_WIDTH = 168
    }
}

@Composable
fun rememberPageImage(cache: ImageCache, href: String?): State<ImageBitmap?> =
    produceState(cache.cachedPage(href), cache, href) { value = cache.page(href) }

@Composable
fun rememberThumbnail(cache: ImageCache, href: String?): State<ImageBitmap?> =
    produceState<ImageBitmap?>(null, cache, href) { value = cache.thumbnail(href) }
