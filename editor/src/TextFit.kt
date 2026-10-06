package tessera.editor

import tessera.acbf.Polygon
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Lays a text out inside a polygon, as large as it fits: each line gets the width the shape
 * really has at its height (a round balloon is narrow at the top and bottom), words are never
 * cut unless one alone is wider than the shape, and the lines are centred. Pure geometry, in
 * image pixels; [wordWidth] gives a word's width at a font size of [REFERENCE] pixels (widths
 * grow linearly with the size).
 */
object TextFit {
    const val REFERENCE = 100f
    /** Line height, in font sizes. */
    const val LEADING = 1.18f

    /** One laid-out line: its text, the x of its centre and the y of its top, in the area's own frame. */
    class Line(val text: String, val centreX: Float, val top: Float)

    class Layout(val fontSize: Float, val lines: List<Line>)

    /**
     * The layout of [text] in [polygon], turned by [rotation] degrees around the polygon's centre
     * (the frame in which the text is horizontal). [padding] keeps text off the outline, as a
     * fraction of the smaller side. [hyphenate] gives where a word may be cut (after how many
     * characters), by its language's rules; a cut adds a hyphen [hyphenWidth] wide.
     */
    fun layout(
        text: String, polygon: Polygon, rotation: Int, wordWidth: (String) -> Float, spaceWidth: Float, padding: Float = 0.08f,
        hyphenate: (String) -> List<Int> = { emptyList() }, hyphenWidth: Float = spaceWidth,
    ): Layout? {
        val paragraphs = text.split('\n').map { p -> glued(p.split(' ', '\u00a0', '\u202f').filter { it.isNotEmpty() }) }.filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) return null
        val shape = turned(polygon, rotation)
        val top = shape.minOf { it.second }
        val bottom = shape.maxOf { it.second }
        val inset = padding * min(shape.maxOf { it.first } - shape.minOf { it.first }, bottom - top)
        val widths = HashMap<String, Float>()
        val cuts = HashMap<String, List<Pair<Int, Boolean>>>()
        val words = Words({ widths.getOrPut(it) { wordWidth(it) } }, spaceWidth, hyphenWidth) { w -> cuts.getOrPut(w) { cutsOf(w, hyphenate) } }
        var lo = 1f
        var hi = (bottom - top).coerceAtLeast(2f)
        var best: Layout? = null
        repeat(16) {
            val size = (lo + hi) / 2
            val fitted = place(paragraphs, words, size, shape, top + inset, bottom - inset, inset)
            if (fitted != null) { best = fitted; lo = size } else hi = size
        }
        // Nothing fits (a word wider than the shape at any size): the smallest size, centred.
        return best ?: place(paragraphs, words, 1f, shape, top, bottom, 0f, force = true)
    }

    /**
     * Punctuation standing alone never starts or ends a line: French « ? », « ! », « : », « ; »
     * and closing quotes join the word before, opening quotes the word after (by a no-break space).
     */
    private fun glued(tokens: List<String>): List<String> {
        val out = ArrayList<String>()
        var opening: String? = null
        for (t in tokens) {
            val bare = t.none { it.isLetterOrDigit() }
            when {
                bare && t.all { it in "«“‘(¿¡" } -> opening = (opening?.let { "$it\u00a0" } ?: "") + t
                bare && out.isNotEmpty() && opening == null -> out[out.size - 1] = out.last() + "\u00a0" + t
                else -> { out += (opening?.let { "$it\u00a0" } ?: "") + t; opening = null }
            }
        }
        opening?.let { out += it }
        return out
    }

    private class Words(val width: (String) -> Float, val space: Float, val hyphen: Float, val cuts: (String) -> List<Pair<Int, Boolean>>)

    /**
     * Where a word may end a line: its hyphenation points (a hyphen added, unless the cut follows
     * one already there), or between any two characters of Chinese or Japanese, which use no spaces.
     */
    private fun cutsOf(word: String, hyphenate: (String) -> List<Int>): List<Pair<Int, Boolean>> =
        if (word.any(::unspaced)) (1 until word.length).filter { !word[it].isLowSurrogate() && word[it] !in CLOSING }.map { it to false }
        else hyphenate(word).filter { it in 1 until word.length }.map { it to (word[it - 1] != '-') }

    private const val CLOSING = "、。，．！？）」』】〉》ー・：；"

    /** Chinese ideographs, kana, CJK punctuation and full-width forms. */
    private fun unspaced(c: Char): Boolean =
        c in '\u3000'..'\u30ff' || c in '\u3400'..'\u9fff' || c in '\uf900'..'\ufaff' || c in '\uff00'..'\uffef'

    /** The lines at font [size], or null when they do not fit between [top] and [bottom]. */
    private fun place(
        paragraphs: List<List<String>>, words: Words, size: Float,
        shape: List<Pair<Float, Float>>, top: Float, bottom: Float, inset: Float, force: Boolean = false,
    ): Layout? {
        val k = size / REFERENCE
        val lh = size * LEADING
        val maxLines = ((bottom - top) / lh).toInt()
        if (maxLines < 1 && !force) return null
        // Try n lines, centred vertically, until the words fit in n lines.
        for (n in 1..max(1, if (force) 999 else maxLines)) {
            val blockTop = (top + bottom) / 2 - n * lh / 2
            val lines = ArrayList<Line>()
            var ok = true
            loop@ for (paragraph in paragraphs) {
                val queue = ArrayDeque(paragraph)
                while (queue.isNotEmpty()) {
                    val row = lines.size
                    if (row >= n) { ok = false; break@loop }
                    val y0 = blockTop + row * lh
                    val (left, right) = span(shape, y0, y0 + lh, inset)
                    val room = right - left
                    val line = StringBuilder()
                    var w = 0f
                    while (queue.isNotEmpty()) {
                        val word = queue.first()
                        val gap = if (line.isEmpty()) 0f else words.space * k
                        val ww = words.width(word) * k
                        if (w + gap + ww <= room) {
                            if (line.isNotEmpty()) line.append(' ')
                            line.append(word); w += gap + ww; queue.removeFirst(); continue
                        }
                        // The longest beginning of the word that still fits, cut by its language's rules.
                        val cut = words.cuts(word).lastOrNull { (at, dash) -> w + gap + words.width(word.substring(0, at)) * k + (if (dash) words.hyphen * k else 0f) <= room }
                        if (cut != null) {
                            if (line.isNotEmpty()) line.append(' ')
                            line.append(word, 0, cut.first); if (cut.second) line.append('-')
                            queue[0] = word.substring(cut.first)
                        } else if (line.isEmpty()) {
                            if (!force) { ok = false; break@loop }
                            line.append(word); queue.removeFirst()
                        }
                        break
                    }
                    lines += Line(line.toString(), (left + right) / 2, y0)
                }
            }
            if (ok) return Layout(size, lines)
        }
        return null
    }

    /** The horizontal room inside the shape over the band [y0, y1], less [inset] on each side. */
    private fun span(shape: List<Pair<Float, Float>>, y0: Float, y1: Float, inset: Float): Pair<Float, Float> {
        var left = Float.NEGATIVE_INFINITY
        var right = Float.POSITIVE_INFINITY
        for (y in listOf(y0, (y0 + y1) / 2, y1)) {
            val xs = crossings(shape, y)
            if (xs.size < 2) return 0f to 0f
            // The widest piece, for shapes the line crosses more than twice.
            val (a, b) = xs.chunked(2).filter { it.size == 2 }.maxBy { it[1] - it[0] }.let { it[0] to it[1] }
            left = max(left, a); right = min(right, b)
        }
        return (left + inset) to max(left + inset, right - inset)
    }

    /** Where the horizontal line at [y] crosses the outline, sorted. */
    private fun crossings(shape: List<Pair<Float, Float>>, y: Float): List<Float> {
        val xs = ArrayList<Float>()
        for (k in shape.indices) {
            val (ax, ay) = shape[k]
            val (bx, by) = shape[(k + 1) % shape.size]
            if ((ay <= y && by > y) || (by <= y && ay > y)) xs += ax + (y - ay) / (by - ay) * (bx - ax)
        }
        return xs.sorted()
    }

    /** The polygon's points turned by [rotation] degrees around its bounding box's centre. */
    fun turned(polygon: Polygon, rotation: Int): List<Pair<Float, Float>> {
        val cx = (polygon.minX + polygon.maxX) / 2f
        val cy = (polygon.minY + polygon.maxY) / 2f
        if (rotation % 360 == 0) return polygon.points.map { it.x.toFloat() to it.y.toFloat() }
        val a = rotation * kotlin.math.PI / 180
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        return polygon.points.map { p ->
            val dx = p.x - cx
            val dy = p.y - cy
            (cx + dx * c - dy * s) to (cy + dx * s + dy * c)
        }
    }
}
