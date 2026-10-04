package tessera.editor.enhance

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.updateAndGet
import kotlin.coroutines.coroutineContext

/** How many processors this machine has. */
expect val processorCount: Int

/** The bytes of a bundled resource (the network's weights), or null where there is none. */
expect fun loadResource(name: String): ByteArray?

/** One 3×3 convolution of the network: weights as [tap][in][out], bias, and PReLU slopes if any. */
internal class Conv(val cin: Int, val cout: Int, val w: FloatArray, val b: FloatArray, val slope: FloatArray?)

/**
 * Real-ESRGAN's compact anime network (realesr-animevideov3, BSD-3-Clause, by Xintao Wang et al.):
 * 17 convolutions of 64 channels with PReLU, a last one to 48 channels, a ×4 pixel shuffle, and
 * the input added back. Run in plain Kotlin, tile by tile on every core, with the same zero
 * padding at the image's edges as the reference implementation, so results match it.
 */
object RealEsrgan {
    private const val RESOURCE = "realesr-animevideov3.bin"
    private const val FEATURES = 64
    private const val SCALE = 4
    private const val TILE = 128

    internal val layers: List<Conv>? by lazy { loadResource(RESOURCE)?.let(::parse) }

    val available: Boolean get() = layers != null

    /**
     * Where tiles run: not the shared default threads, which the UI needs to decode pages and
     * thumbnails meanwhile, and one core left free so the window stays responsive.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val computeDispatcher = Dispatchers.IO.limitedParallelism(maxOf(1, processorCount - 1))

    /** Where the result of each tile goes: its 4×4 blocks, one per source pixel. */
    private fun interface Sink {
        /** [block] holds 48 values: colour c, row dy, column dx at c * 16 + dy * 4 + dx. */
        fun put(x: Int, y: Int, block: FloatArray, offset: Int, input: FloatArray, inputOffset: Int)
    }

    /**
     * The page enlarged ×4 by the network and brought back to ×2 (each 2×2 block averaged), as
     * ARGB. [progress] gets the fraction done. Cancellable between tiles.
     */
    /** Thrown when a computation gives way to a more urgent one (see [upscale2x]). */
    class Yielded : Exception("gave way to a more urgent page")

    suspend fun upscale2x(page: Argb, progress: (Float) -> Unit = {}, shouldYield: () -> Boolean = { false }): Argb {
        val w2 = page.width * 2
        val out = IntArray(w2 * page.height * 2)
        run(page, progress, shouldYield) { x, y, block, o, _, _ ->
            for (j in 0..1) for (i in 0..1) {
                var px = 0xFF shl 24
                for (c in 0 until 3) {
                    var sum = 0f
                    for (dy in 0..1) for (dx in 0..1) sum += block[o + c * 16 + (2 * j + dy) * 4 + 2 * i + dx]
                    px = px or (channel(sum / 4f) shl (16 - 8 * c))
                }
                out[(2 * y + j) * w2 + 2 * x + i] = px
            }
        }
        return Argb(w2, page.height * 2, out)
    }

    /** The full ×4 result, for small images (tests against the reference). */
    suspend fun upscale4x(page: Argb): Argb {
        val w4 = page.width * SCALE
        val out = IntArray(w4 * page.height * SCALE)
        run(page, {}, { false }) { x, y, block, o, _, _ ->
            for (dy in 0 until 4) for (dx in 0 until 4) {
                var px = 0xFF shl 24
                for (c in 0 until 3) px = px or (channel(block[o + c * 16 + dy * 4 + dx]) shl (16 - 8 * c))
                out[(4 * y + dy) * w4 + 4 * x + dx] = px
            }
        }
        return Argb(w4, page.height * SCALE, out)
    }

    private fun channel(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    /** [shouldYield] is asked before each tile: true stops the computation with [Yielded]. */
    private suspend fun run(page: Argb, progress: (Float) -> Unit, shouldYield: () -> Boolean, sink: Sink) = coroutineScope {
        val net = layers ?: error("Real-ESRGAN weights missing")
        val tiles = buildList { for (y in 0 until page.height step TILE) for (x in 0 until page.width step TILE) add(x to y) }
        val done = kotlinx.coroutines.flow.MutableStateFlow(0)
        tiles.map { (x, y) ->
            async(computeDispatcher) {
                coroutineContext.ensureActive()
                if (shouldYield()) throw Yielded()
                tile(net, page, x, y, minOf(TILE, page.width - x), minOf(TILE, page.height - y), sink)
                val n = done.updateAndGet { it + 1 }
                progress(n.toFloat() / tiles.size)
            }
        }.awaitAll()
    }

    private fun tile(net: List<Conv>, page: Argb, x0: Int, y0: Int, tw: Int, th: Int, sink: Sink) {
        val m = net.size // one pixel of reach per 3×3 convolution
        val p = tw + 2 * m
        val q = th + 2 * m
        val gx0 = x0 - m
        val gy0 = y0 - m
        // Layer 0: the page's colours; zero outside the page, as PyTorch pads.
        var a = FloatArray(p * q * FEATURES)
        val input = FloatArray(p * q * 3)
        for (y in 0 until q) {
            val gy = gy0 + y
            if (gy !in 0 until page.height) continue
            for (x in 0 until p) {
                val gx = gx0 + x
                if (gx !in 0 until page.width) continue
                val px = page.pixels[gy * page.width + gx]
                val s = (y * p + x) * 3
                input[s] = ((px shr 16) and 0xFF) / 255f
                input[s + 1] = ((px shr 8) and 0xFF) / 255f
                input[s + 2] = (px and 0xFF) / 255f
            }
        }
        var cin = 3
        var src = input
        var b = FloatArray(p * q * FEATURES)
        val scratch = Array(4) { FloatArray(FEATURES) }
        for ((k, conv) in net.withIndex()) {
            val lo = k + 1
            // Only what the next layer needs: the valid region shrinks by one pixel per layer.
            // Pixels outside the page are never written, so they read as the zeros PyTorch pads with.
            val xs = maxOf(lo, -gx0)
            val xe = minOf(p - lo, page.width - gx0)
            for (y in lo until q - lo) {
                if (gy0 + y !in 0 until page.height) continue
                var x = xs
                while (x + 4 <= xe) {
                    convolve4(conv, src, cin, p, x, y, b, scratch)
                    x += 4
                }
                while (x < xe) {
                    convolve1(conv, src, cin, p, x, y, b, scratch)
                    x++
                }
            }
            if (k == 0) {
                src = b; b = a; a = src
            } else {
                val t = src; src = b; b = t
            }
            cin = conv.cout
        }
        // The last layer's 48 values per pixel are its 4×4 blocks; add the input (nearest ×4).
        val block = FloatArray(48)
        for (y in 0 until th) for (x in 0 until tw) {
            val s = ((y + m) * p + x + m)
            for (c in 0 until 3) {
                val base = input[s * 3 + c]
                for (k in 0 until 16) block[c * 16 + k] = src[s * FEATURES + c * 16 + k] + base
            }
            sink.put(x0 + x, y0 + y, block, 0, input, s * 3)
        }
    }

    /**
     * Four neighbouring pixels at once: each weight is loaded once for four multiplications, and
     * the innermost loop (over output channels) is simple enough for the JIT to vectorise.
     */
    private fun convolve4(conv: Conv, src: FloatArray, cin: Int, p: Int, x: Int, y: Int, dst: FloatArray, scratch: Array<FloatArray>) {
        val cout = conv.cout
        val w = conv.w
        val a0 = scratch[0]; val a1 = scratch[1]; val a2 = scratch[2]; val a3 = scratch[3]
        conv.b.copyInto(a0); conv.b.copyInto(a1); conv.b.copyInto(a2); conv.b.copyInto(a3)
        for (ky in 0..2) for (kx in 0..2) {
            val s0 = ((y + ky - 1) * p + x + kx - 1) * cin
            var wi = (ky * 3 + kx) * cin * cout
            for (i in 0 until cin) {
                val v0 = src[s0 + i]; val v1 = src[s0 + cin + i]; val v2 = src[s0 + 2 * cin + i]; val v3 = src[s0 + 3 * cin + i]
                for (o in 0 until cout) {
                    val ww = w[wi + o]
                    a0[o] += v0 * ww; a1[o] += v1 * ww; a2[o] += v2 * ww; a3[o] += v3 * ww
                }
                wi += cout
            }
        }
        val d = (y * p + x) * FEATURES
        store(conv, a0, dst, d); store(conv, a1, dst, d + FEATURES); store(conv, a2, dst, d + 2 * FEATURES); store(conv, a3, dst, d + 3 * FEATURES)
    }

    private fun convolve1(conv: Conv, src: FloatArray, cin: Int, p: Int, x: Int, y: Int, dst: FloatArray, scratch: Array<FloatArray>) {
        val cout = conv.cout
        val w = conv.w
        val acc = scratch[0]
        conv.b.copyInto(acc)
        for (ky in 0..2) for (kx in 0..2) {
            val s = ((y + ky - 1) * p + x + kx - 1) * cin
            var wi = (ky * 3 + kx) * cin * cout
            for (i in 0 until cin) {
                val v = src[s + i]
                for (o in 0 until cout) acc[o] += v * w[wi + o]
                wi += cout
            }
        }
        store(conv, acc, dst, (y * p + x) * FEATURES)
    }

    /** PReLU (when the layer has one) and storage. */
    private fun store(conv: Conv, acc: FloatArray, dst: FloatArray, d: Int) {
        val slope = conv.slope
        if (slope == null) {
            acc.copyInto(dst, d, 0, conv.cout)
            return
        }
        for (o in 0 until conv.cout) {
            val v = acc[o]
            dst[d + o] = if (v >= 0f) v else v * slope[o]
        }
    }

    private fun parse(bytes: ByteArray): List<Conv> {
        var i = 0
        fun floats(n: Int) = FloatArray(n) {
            val v = (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                ((bytes[i + 2].toInt() and 0xFF) shl 16) or ((bytes[i + 3].toInt() and 0xFF) shl 24)
            i += 4
            Float.fromBits(v)
        }
        val shapes = listOf(3 to FEATURES) + List(16) { FEATURES to FEATURES } + listOf(FEATURES to 3 * SCALE * SCALE)
        return shapes.mapIndexed { k, (cin, cout) ->
            val w = floats(9 * cin * cout)
            val b = floats(cout)
            val slope = if (k < shapes.size - 1) floats(cout) else null
            Conv(cin, cout, w, b, slope)
        }.also { check(i == bytes.size) { "unexpected weights size" } }
    }
}
