package tessera.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tessera.acbf.Comic
import tessera.editor.enhance.Argb
import tessera.editor.enhance.Enhancement
import tessera.editor.enhance.Enhancer

/** Decodes JPEG, PNG, WebP, GIF or BMP; null when the bytes are not an image. */
expect fun decodeImage(bytes: ByteArray): ImageBitmap?

/** Decodes and scales down to [width] pixels wide, for page thumbnails. */
expect fun decodeThumbnail(bytes: ByteArray, width: Int): ImageBitmap?

/** An opaque bitmap from ARGB pixels (the enhancer's output). */
expect fun imageFromArgb(pixels: IntArray, width: Int, height: Int): ImageBitmap

/**
 * Page images, decoded off the main thread and cached: a few full pages (the current one and its
 * neighbours) and every thumbnail. Used from the UI thread only; decoding runs elsewhere.
 */
class ImageCache(var comic: Comic) {
    /** Least recently used first. */
    private val full = LinkedHashMap<String, ImageBitmap?>()
    private val thumbs = HashMap<String, ImageBitmap?>()

    fun cachedPage(href: String?): ImageBitmap? = href?.let { full[it] }

    /** True when [href] was already looked up, found or not. */
    fun isKnown(href: String): Boolean = full.containsKey(href)

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

    /** Enhanced pages, by image and settings; recomputed only when either changes. */
    private val enhanced = LinkedHashMap<Pair<String, Enhancement>, ImageBitmap?>()

    /** The page at [href] improved for display with [settings], or null when off or missing. */
    suspend fun enhanced(href: String?, settings: Enhancement): ImageBitmap? {
        if (href == null || !settings.active) return null
        val key = href to settings
        if (enhanced.containsKey(key)) return enhanced.remove(key).also { enhanced[key] = it }
        val page = page(href) ?: return null
        val result = withContext(Dispatchers.Default) {
            val px = IntArray(page.width * page.height)
            page.readPixels(px)
            val out = Enhancer.enhance(Argb(page.width, page.height, px), settings)
            imageFromArgb(out.pixels, out.width, out.height)
        }
        enhanced[key] = result
        while (enhanced.size > 4) enhanced.remove(enhanced.keys.first())
        return result
    }

    companion object {
        /** Twice the strip's width, for sharp thumbnails on high-density screens. */
        const val THUMB_WIDTH = 168
    }
}

/** A page image as the canvas sees it: still loading, decoded, or missing (null once loaded). */
data class PageImage(val bitmap: ImageBitmap?, val loading: Boolean)

/**
 * The image of the page at [href]. Changing page never shows the previous page's image under the
 * new page's frames: until the new one is decoded, it reports [PageImage.loading].
 */
@Composable
fun rememberPageImage(cache: ImageCache, href: String?): State<PageImage> {
    val state = remember(cache, href) {
        val cached = cache.cachedPage(href)
        mutableStateOf(PageImage(cached, loading = cached == null && href != null && !cache.isKnown(href)))
    }
    LaunchedEffect(cache, href) {
        Trace.log { "image $href loading=${state.value.loading}" }
        state.value = PageImage(cache.page(href), loading = false)
        Trace.log { "image $href ready=${state.value.bitmap != null}" }
    }
    return state
}

/** The enhanced page, or null while it is computed (show the plain page meanwhile) or when off. */
@Composable
fun rememberEnhanced(cache: ImageCache, href: String?, settings: Enhancement): State<ImageBitmap?> =
    produceState<ImageBitmap?>(null, cache, href, settings) { value = cache.enhanced(href, settings) }

@Composable
fun rememberThumbnail(cache: ImageCache, href: String?): State<ImageBitmap?> =
    produceState<ImageBitmap?>(null, cache, href) { value = cache.thumbnail(href) }
