package tessera.editor.enhance

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** How pages are shown: as they are, sharpened, or restored and enlarged by Anime4K. */
enum class EnhanceMode { Off, Sharpen, Restore }

/**
 * Display enhancement settings. [sharpness] (0–1) drives contrast-adaptive sharpening, 0 turning
 * it off; [strength] (0–1) scales what Anime4K adds in [EnhanceMode.Restore].
 */
data class Enhancement(val mode: EnhanceMode = EnhanceMode.Off, val sharpness: Float = 0.5f, val strength: Float = 1f) {
    val active: Boolean get() = mode == EnhanceMode.Restore || (mode == EnhanceMode.Sharpen && sharpness > 0f)
}

/** An ARGB image, as pixels move between bitmaps and the enhancer. */
class Argb(val width: Int, val height: Int, val pixels: IntArray)

/**
 * Improves a page for display only; files are never touched. Sharpen applies AMD's
 * contrast-adaptive sharpening (CAS); Restore runs Anime4K's Restore CNN, then its ×2 Upscale CNN,
 * then CAS. Everything is plain Kotlin, off the UI thread.
 */
object Enhancer {
    suspend fun enhance(page: Argb, settings: Enhancement): Argb = withContext(Dispatchers.Default) {
        when (settings.mode) {
            EnhanceMode.Off -> page
            EnhanceMode.Sharpen -> if (settings.sharpness > 0f) sharpen(page, settings.sharpness) else page
            EnhanceMode.Restore -> {
                val restored = Cnn.run(Anime4KModels.RESTORE_M, toImage(page), settings.strength)
                val enlarged = Cnn.run(Anime4KModels.UPSCALE_X2_M, restored, settings.strength)
                val out = toArgb(enlarged)
                if (settings.sharpness > 0f) sharpen(out, settings.sharpness) else out
            }
        }
    }

    fun toImage(a: Argb): Image4 {
        val img = Image4(a.width, a.height)
        val d = img.data
        for (i in a.pixels.indices) {
            val p = a.pixels[i]
            d[i * 4] = ((p shr 16) and 0xFF) / 255f
            d[i * 4 + 1] = ((p shr 8) and 0xFF) / 255f
            d[i * 4 + 2] = (p and 0xFF) / 255f
            d[i * 4 + 3] = 1f
        }
        return img
    }

    fun toArgb(img: Image4): Argb {
        val d = img.data
        val px = IntArray(img.width * img.height) { i ->
            fun ch(c: Int) = (d[i * 4 + c] * 255f + 0.5f).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
        }
        return Argb(img.width, img.height, px)
    }

    /**
     * AMD FidelityFX contrast-adaptive sharpening, at the same size: each pixel is sharpened
     * against its four neighbours, less where local contrast is already high, so edges get
     * crisper without halos. [sharpness] 0–1 maps to CAS's own sharpness.
     */
    fun sharpen(a: Argb, sharpness: Float): Argb {
        val w = a.width
        val h = a.height
        val src = a.pixels
        val out = IntArray(src.size)
        val peak = -1f / (8f + (5f - 8f) * sharpness.coerceIn(0f, 1f))
        fun at(x: Int, y: Int) = src[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
        for (y in 0 until h) for (x in 0 until w) {
            val pa = at(x - 1, y - 1); val pb = at(x, y - 1); val pc = at(x + 1, y - 1)
            val pd = at(x - 1, y); val pe = src[y * w + x]; val pf = at(x + 1, y)
            val pg = at(x - 1, y + 1); val ph = at(x, y + 1); val pi = at(x + 1, y + 1)
            var result = 0xFF shl 24
            for (shift in intArrayOf(16, 8, 0)) {
                fun c(p: Int) = ((p shr shift) and 0xFF) / 255f
                val b = c(pb); val d = c(pd); val e = c(pe); val f = c(pf); val hh = c(ph)
                // Soft minimum and maximum: the cross plus the whole 3×3 block.
                var mn = min(min(min(b, d), min(e, f)), hh)
                mn += min(mn, min(min(c(pa), c(pc)), min(c(pg), c(pi))))
                var mx = max(max(max(b, d), max(e, f)), hh)
                mx += max(mx, max(max(c(pa), c(pc)), max(c(pg), c(pi))))
                val amp = if (mx > 0f) sqrt((min(mn, 2f - mx) / mx).coerceIn(0f, 1f)) else 0f
                val wgt = amp * peak
                val v = ((b + d + f + hh) * wgt + e) / (1f + 4f * wgt)
                result = result or ((v * 255f + 0.5f).toInt().coerceIn(0, 255) shl shift)
            }
            out[y * w + x] = result
        }
        return Argb(w, h, out)
    }
}
