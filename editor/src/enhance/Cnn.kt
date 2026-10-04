package tessera.editor.enhance

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.floor

/** An image of four float channels per pixel (red, green, blue, 1), values from 0 to 1. */
class Image4(val width: Int, val height: Int, val data: FloatArray = FloatArray(width * height * 4))

/** One term of a pass: a source (-1: the page, else an earlier pass), an activation, an offset. */
internal class Term(val source: Int, val activation: Int, val dx: Int, val dy: Int)

/**
 * One shader pass: a sum of `mat4 × input(dx, dy)` terms plus a bias, as in Anime4K's GLSL. The
 * last pass either adds its result to the page ([residual]) or is followed by [depthToSpace].
 */
internal class Pass private constructor(val terms: List<Term>, val weights: FloatArray, val residual: Boolean, val depthToSpace: Boolean) {
    constructor(spec: String, blob: String, residual: Boolean) : this(parseTerms(spec), decode(blob), residual, false)

    val reach: Int get() = terms.maxOfOrNull { maxOf(kotlin.math.abs(it.dx), kotlin.math.abs(it.dy)) } ?: 0

    companion object {
        fun depthToSpace() = Pass(emptyList(), FloatArray(0), residual = false, depthToSpace = true)

        private fun parseTerms(spec: String) = spec.split(';').map { t ->
            val (s, a, x, y) = t.split(',').map(String::toInt)
            Term(s, a, x, y)
        }

        @OptIn(ExperimentalEncodingApi::class)
        private fun decode(blob: String): FloatArray {
            val bytes = Base64.decode(blob)
            return FloatArray(bytes.size / 4) { i ->
                val b = i * 4
                Float.fromBits((bytes[b].toInt() and 0xFF) or ((bytes[b + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[b + 2].toInt() and 0xFF) shl 16) or ((bytes[b + 3].toInt() and 0xFF) shl 24))
            }
        }
    }
}

internal class Model(val name: String, vararg passes: Pass) {
    val passes: List<Pass> = passes.toList()
    val upscales: Boolean get() = passes.last().depthToSpace

    /** How far the result at a pixel depends on its neighbours: the margin a tile needs. */
    val margin: Int get() = passes.sumOf { it.reach }
}

/**
 * Runs Anime4K's CNN models in plain Kotlin, tile by tile on every core, so memory stays small
 * whatever the page size. [strength] scales what the network adds (0: nothing, 1: as trained).
 */
internal object Cnn {
    private const val TILE = 160

    suspend fun run(model: Model, input: Image4, strength: Float): Image4 = coroutineScope {
        val scale = if (model.upscales) 2 else 1
        val output = Image4(input.width * scale, input.height * scale)
        val tiles = buildList {
            for (y in 0 until input.height step TILE) for (x in 0 until input.width step TILE) add(x to y)
        }
        tiles.map { (x, y) ->
            async(Dispatchers.Default) {
                tile(model, input, output, x, y, minOf(TILE, input.width - x), minOf(TILE, input.height - y), strength)
            }
        }.awaitAll()
        output
    }

    private fun tile(model: Model, input: Image4, output: Image4, x0: Int, y0: Int, tw: Int, th: Int, strength: Float) {
        val m = model.margin
        val w = tw + 2 * m
        val h = th + 2 * m
        // The page around the tile, edges clamped as a texture would be.
        val main = FloatArray(w * h * 4)
        for (y in 0 until h) {
            val sy = (y0 - m + y).coerceIn(0, input.height - 1)
            for (x in 0 until w) {
                val sx = (x0 - m + x).coerceIn(0, input.width - 1)
                input.data.copyInto(main, (y * w + x) * 4, (sy * input.width + sx) * 4, (sy * input.width + sx) * 4 + 4)
            }
        }
        val layers = arrayOfNulls<FloatArray>(model.passes.size)
        for ((index, pass) in model.passes.withIndex()) {
            if (pass.depthToSpace) {
                depthToSpace(layers[index - 1]!!, w, m, tw, th, input, output, x0, y0, strength)
                return
            }
            val result = convolve(pass, layers, main, w, h)
            if (pass.residual) {
                for (y in 0 until th) for (x in 0 until tw) {
                    val s = ((y + m) * w + x + m) * 4
                    val o = ((y0 + y) * output.width + x0 + x) * 4
                    for (c in 0 until 3) output.data[o + c] = main[s + c] + strength * result[s + c]
                    output.data[o + 3] = 1f
                }
                return
            }
            layers[index] = result
        }
    }

    private fun convolve(pass: Pass, layers: Array<FloatArray?>, main: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h * 4)
        val wt = pass.weights
        val bias = pass.terms.size * 16
        for (i in 0 until w * h) {
            out[i * 4] = wt[bias]; out[i * 4 + 1] = wt[bias + 1]; out[i * 4 + 2] = wt[bias + 2]; out[i * 4 + 3] = wt[bias + 3]
        }
        for ((t, term) in pass.terms.withIndex()) {
            val src = if (term.source < 0) main else layers[term.source]!!
            val k = t * 16
            // Column j of the GLSL mat4 multiplies component j of the input.
            val a0 = wt[k]; val a1 = wt[k + 1]; val a2 = wt[k + 2]; val a3 = wt[k + 3]
            val b0 = wt[k + 4]; val b1 = wt[k + 5]; val b2 = wt[k + 6]; val b3 = wt[k + 7]
            val c0 = wt[k + 8]; val c1 = wt[k + 9]; val c2 = wt[k + 10]; val c3 = wt[k + 11]
            val d0 = wt[k + 12]; val d1 = wt[k + 13]; val d2 = wt[k + 14]; val d3 = wt[k + 15]
            val act = term.activation
            for (y in 0 until h) {
                val sy = (y + term.dy).coerceIn(0, h - 1)
                var o = y * w * 4
                for (x in 0 until w) {
                    val sx = (x + term.dx).coerceIn(0, w - 1)
                    val s = (sy * w + sx) * 4
                    var v0 = src[s]; var v1 = src[s + 1]; var v2 = src[s + 2]; var v3 = src[s + 3]
                    when (act) {
                        1 -> { if (v0 < 0f) v0 = 0f; if (v1 < 0f) v1 = 0f; if (v2 < 0f) v2 = 0f; if (v3 < 0f) v3 = 0f }
                        2 -> { v0 = if (v0 < 0f) -v0 else 0f; v1 = if (v1 < 0f) -v1 else 0f; v2 = if (v2 < 0f) -v2 else 0f; v3 = if (v3 < 0f) -v3 else 0f }
                    }
                    out[o] += a0 * v0 + b0 * v1 + c0 * v2 + d0 * v3
                    out[o + 1] += a1 * v0 + b1 * v1 + c1 * v2 + d1 * v3
                    out[o + 2] += a2 * v0 + b2 * v1 + c2 * v2 + d2 * v3
                    out[o + 3] += a3 * v0 + b3 * v1 + c3 * v2 + d3 * v3
                    o += 4
                }
            }
        }
        return out
    }

    /**
     * Each low-resolution pixel's four results become a 2×2 block, added to the page enlarged
     * bilinearly (sampled at the new pixel centres, as the shader's texture lookup does).
     */
    private fun depthToSpace(last: FloatArray, w: Int, m: Int, tw: Int, th: Int, input: Image4, output: Image4, x0: Int, y0: Int, strength: Float) {
        for (y in 0 until th) for (x in 0 until tw) {
            val r = ((y + m) * w + x + m) * 4
            for (j in 0..1) for (i in 0..1) {
                val add = strength * last[r + j * 2 + i]
                val gx = x0 + x - 0.25f + i * 0.5f
                val gy = y0 + y - 0.25f + j * 0.5f
                val o = ((2 * (y0 + y) + j) * output.width + 2 * (x0 + x) + i) * 4
                bilinear(input, gx, gy, output.data, o)
                output.data[o] += add; output.data[o + 1] += add; output.data[o + 2] += add
                output.data[o + 3] = 1f
            }
        }
    }

    private fun bilinear(img: Image4, x: Float, y: Float, out: FloatArray, o: Int) {
        val fx = floor(x); val fy = floor(y)
        val tx = x - fx; val ty = y - fy
        val x0 = fx.toInt().coerceIn(0, img.width - 1); val x1 = (fx.toInt() + 1).coerceIn(0, img.width - 1)
        val y0 = fy.toInt().coerceIn(0, img.height - 1); val y1 = (fy.toInt() + 1).coerceIn(0, img.height - 1)
        val d = img.data
        val p00 = (y0 * img.width + x0) * 4; val p10 = (y0 * img.width + x1) * 4
        val p01 = (y1 * img.width + x0) * 4; val p11 = (y1 * img.width + x1) * 4
        for (c in 0 until 3) {
            val top = d[p00 + c] + (d[p10 + c] - d[p00 + c]) * tx
            val bottom = d[p01 + c] + (d[p11 + c] - d[p01 + c]) * tx
            out[o + c] = top + (bottom - top) * ty
        }
    }
}
