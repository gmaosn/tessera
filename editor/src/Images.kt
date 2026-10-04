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
import tessera.editor.enhance.EnhanceMode
import tessera.editor.enhance.RealEsrgan
import tessera.editor.enhance.SuperResStore
import tessera.editor.enhance.decodeArgb
import tessera.editor.enhance.encodeJpeg
import tessera.zip.crc32
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.update
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
class ImageCache(var comic: Comic, var store: SuperResStore? = null) {
    /** Real-ESRGAN's progress (0–1) for each page image being computed. */
    val superResProgress = mutableStateMapOf<String, Float>()

    /** What the UI shows now (a page or a frame), most urgent first. */
    var shown: List<String> = emptyList()

    /** What the [Preparer] is computing ahead. */
    var preparing: List<String> = emptyList()

    /**
     * Everything wanted, most urgent first: what is shown, then what is prepared. Computations
     * run in this order; a queued one for anything else is dropped. Setting it sets [shown].
     */
    var wanted: List<String>
        get() = shown + preparing
        set(value) { shown = value }

    /** Pages waiting for their turn at Real-ESRGAN. */
    private val waiting = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())

    private val superResJobs = HashMap<String, Deferred<Argb?>>()
    private val superResScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // One page at a time: the network already uses every core.
    private val superResLock = Mutex()

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
        if (settings.mode != EnhanceMode.Sharpen && isHighDefinition(href)) return null
        val key = href to settings
        if (enhanced.containsKey(key)) return enhanced.remove(key).also { enhanced[key] = it }
        val page = page(href) ?: return null
        if (settings.mode != EnhanceMode.Sharpen && page.width.toLong() * page.height > MAX_ENHANCED_PIXELS) return null
        val sr = if (settings.mode == EnhanceMode.SuperRes) superRes(href) ?: return null else null
        val result = withContext(Dispatchers.Default) {
            val px = IntArray(page.width * page.height)
            page.readPixels(px)
            val argb = Argb(page.width, page.height, px)
            val out = if (sr != null) Enhancer.finish(argb, sr, settings) else Enhancer.enhance(argb, settings)
            imageFromArgb(out.pixels, out.width, out.height)
        }
        enhanced[key] = result
        while (enhanced.size > 4) enhanced.remove(enhanced.keys.first())
        return result
    }

    /** Enhanced regions (frames of high-definition pages), by identity and settings. */
    private val enhancedRegions = LinkedHashMap<Pair<String, Enhancement>, ImageBitmap?>()

    /**
     * One region of a page (the frame being read) improved for display, ×2: how a high-definition
     * page still gains when a frame fills the screen, at a fraction of the page's cost. Null when
     * off, for sharpening (done on the whole page), or while computing.
     */
    suspend fun enhancedRegion(href: String?, region: Region, settings: Enhancement, computeIfMissing: Boolean = true): ImageBitmap? {
        if (href == null || settings.mode != EnhanceMode.Restore && settings.mode != EnhanceMode.SuperRes) return null
        if (region.width.toLong() * region.height > MAX_ENHANCED_PIXELS) return null
        val key = region.id(href) to settings
        if (enhancedRegions.containsKey(key)) return enhancedRegions.remove(key).also { enhancedRegions[key] = it }
        val page = page(href) ?: return null
        val sr = if (settings.mode == EnhanceMode.SuperRes) superRes(href, region, computeIfMissing) ?: return null else null
        val result = withContext(Dispatchers.Default) {
            val px = IntArray(region.width * region.height)
            page.readPixels(px, region.x, region.y, region.width, region.height)
            val crop = Argb(region.width, region.height, px)
            val out = if (sr != null) Enhancer.finish(crop, sr, settings) else Enhancer.enhance(crop, settings)
            imageFromArgb(out.pixels, out.width, out.height)
        }
        enhancedRegions[key] = result
        while (enhancedRegions.size > 24) enhancedRegions.remove(enhancedRegions.keys.first())
        return result
    }

    /**
     * Makes sure Real-ESRGAN's result for a page (or one of its regions) is in the store,
     * computing it if needed; for the [Preparer]. True when it is there.
     */
    suspend fun ensureSuperRes(href: String, region: Region? = null): Boolean {
        val bytes = comic.image(href) ?: return false
        val store = store
        if (store != null && store.exists(storeKey(bytes, region))) return true
        return superRes(href, region) != null
    }

    private fun storeKey(bytes: ByteArray, region: Region?) =
        "${crc32(bytes).toString(16).padStart(8, '0')}-${bytes.size}" + (region?.let { "-r${it.x}_${it.y}_${it.width}_${it.height}" } ?: "")

    /**
     * Real-ESRGAN's ×2 result for a page image: from the store when it was computed before,
     * otherwise computed (minutes) and saved there. A computation, once started, finishes even
     * if the page is left, so that coming back is instant.
     */
    private suspend fun superRes(href: String, region: Region? = null, computeIfMissing: Boolean = true): Argb? {
        if (!RealEsrgan.available) return null
        val id = region?.id(href) ?: href
        superResJobs[id]?.let { return it.await() }
        val bytes = comic.image(href) ?: return null
        val key = storeKey(bytes, region)
        val store = store
        if (!computeIfMissing) {
            return store?.runCatching { load(key) }?.getOrNull()?.let { withContext(Dispatchers.Default) { decodeArgb(it) } }
        }
        val job = superResScope.async {
            store?.runCatching { load(key) }?.getOrNull()?.let { decodeArgb(it) }?.let { return@async it }
            // Give way to a more urgent page between tiles, then take the turn again.
            while (true) {
                takeTurn(id)
                try {
                    if (id !in wanted) return@async null
                    val decoded = decodeArgb(bytes) ?: return@async null
                    val page = region?.let { decoded.crop(it) } ?: decoded
                    superResProgress[id] = 0f
                    val sr = RealEsrgan.upscale2x(page, { superResProgress[id] = it }, shouldYield = { moreUrgentWaiting(id) })
                    store?.runCatching { save(key, encodeJpeg(sr, 92)) }
                    return@async sr
                } catch (e: RealEsrgan.Yielded) {
                    continue
                } finally {
                    superResProgress.remove(id)
                    superResLock.unlock()
                }
            }
            @Suppress("UNREACHABLE_CODE") null
        }
        superResJobs[id] = job
        return try { job.await() } finally { superResJobs.remove(id) }
    }

    /**
     * Waits until no computation runs and no more urgent page (earlier in [wanted]) is waiting,
     * then holds the lock: the page shown always goes before the one prepared ahead.
     */
    private suspend fun takeTurn(href: String) {
        waiting.update { it + href }
        try {
            while (true) {
                superResLock.lock()
                val first = wanted.firstOrNull { it in waiting.value }
                if (first == null || first == href || href !in wanted) return
                superResLock.unlock()
                kotlinx.coroutines.delay(150)
            }
        } finally {
            waiting.update { it - href }
        }
    }

    /** True when a page ahead of [href] in [wanted] is waiting, or [href] is no longer wanted. */
    private fun moreUrgentWaiting(href: String): Boolean {
        val at = wanted.indexOf(href)
        if (at < 0) return true
        val queued = waiting.value
        return wanted.subList(0, at).any { it in queued }
    }

    /**
     * True for a page already in high definition: enlarging it would take many minutes and lots
     * of memory for nothing visible, so Restore and Super-res leave it as it is.
     */
    fun isHighDefinition(href: String?): Boolean =
        cachedPage(href)?.let { it.width.toLong() * it.height > MAX_ENHANCED_PIXELS } ?: false

    companion object {
        /** About 1700 × 2500 pixels: beyond, a page is already sharp at any useful zoom. */
        const val MAX_ENHANCED_PIXELS = 4_300_000L

        /** Twice the strip's width, for sharp thumbnails on high-density screens. */
        const val THUMB_WIDTH = 168
    }
}

/** A rectangle of a page image, in its pixels. */
data class Region(val x: Int, val y: Int, val width: Int, val height: Int) {
    fun id(href: String) = "$href#$x,$y,${width}x$height"

    companion object {
        /** A polygon's bounding box plus [margin] pixels, kept inside a [w]×[h] page. */
        fun around(poly: tessera.acbf.Polygon, w: Int, h: Int, margin: Int = 8): Region {
            val x0 = (poly.minX - margin).coerceIn(0, w - 1)
            val y0 = (poly.minY - margin).coerceIn(0, h - 1)
            val x1 = (poly.maxX + margin).coerceIn(x0 + 1, w)
            val y1 = (poly.maxY + margin).coerceIn(y0 + 1, h)
            return Region(x0, y0, x1 - x0, y1 - y0)
        }
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
