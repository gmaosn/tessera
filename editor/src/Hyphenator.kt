package tessera.editor

import tessera.editor.enhance.loadResource

/**
 * Where a word may be cut at the end of a line, by the rules of its language: Frank Liang's
 * algorithm (the one TeX uses) with the hyph-utf8 patterns in `resources/hyphenation`
 * (`tools/hyphenation.py`). Words of a language without patterns are not cut.
 */
class Hyphenator private constructor(private val left: Int, private val right: Int, private val patterns: Map<String, IntArray>, private val exceptions: Map<String, List<Int>>) {
    private val longest = patterns.keys.maxOfOrNull { it.length } ?: 0

    /**
     * The positions in [word] after which it may be cut (adding a hyphen). Punctuation around
     * the word is skipped; a word already holding a hyphen is cut only after it.
     */
    fun points(word: String): List<Int> {
        if ('-' in word) return word.indices.filter { word[it] == '-' && it in 1 until word.length - 1 }.map { it + 1 }
        val start = word.indexOfFirst { it.isLetter() }
        val end = word.indexOfLast { it.isLetter() } + 1
        if (start < 0 || end - start < left + right) return emptyList()
        val core = word.substring(start, end).lowercase().replace('’', '\'')
        if (core.length != end - start) return emptyList()
        exceptions[core]?.let { e -> return e.map { it + start } }
        val dotted = ".$core."
        val levels = IntArray(dotted.length + 1)
        for (i in dotted.indices) {
            for (j in i + 1..minOf(dotted.length, i + longest)) {
                val values = patterns[dotted.substring(i, j)] ?: continue
                for (k in values.indices) if (values[k] > levels[i + k]) levels[i + k] = values[k]
            }
        }
        // levels[k] sits before dotted[k]: an odd value before core[p] allows a cut after p letters.
        return (left..core.length - right).filter { p -> levels[p + 1] % 2 == 1 }.map { it + start }
    }

    companion object {
        private val cache = HashMap<String, Hyphenator?>()

        /** The hyphenator of a language code ("fr", "en-GB"…), or null when there are no patterns. */
        fun of(lang: String?): Hyphenator? {
            val code = lang?.lowercase()?.substringBefore('-')?.substringBefore('_')?.takeIf { it.isNotBlank() } ?: return null
            return cache.getOrPut(code) { loadResource("hyphenation/$code.pat")?.decodeToString()?.let(::parse) }
        }

        /** Reads the format written by tools/hyphenation.py. */
        fun parse(text: String): Hyphenator {
            val lines = text.lines()
            val (left, right) = lines.first().trim().split(' ').map { it.toInt() }
            val patterns = HashMap<String, IntArray>()
            val exceptions = HashMap<String, List<Int>>()
            var inExceptions = false
            for (raw in lines.drop(1)) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (line == "-") { inExceptions = true; continue }
                if (inExceptions) {
                    val word = line.replace("-", "").lowercase()
                    var letters = 0
                    exceptions[word] = buildList { for (c in line) if (c == '-') add(letters) else letters++ }
                    continue
                }
                val letters = StringBuilder()
                val values = ArrayList<Int>()
                var pending = 0
                for (c in line) {
                    if (c.isDigit()) pending = c - '0' else { values += pending; pending = 0; letters.append(c) }
                }
                values += pending
                patterns[letters.toString()] = values.toIntArray()
            }
            return Hyphenator(left, right, patterns, exceptions)
        }
    }
}
