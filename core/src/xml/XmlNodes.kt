package tessera.xml

/**
 * A lossless XML tree. Every node remembers the exact source text it was parsed from, and writes
 * that text back for as long as it is not modified. An unmodified document is therefore written
 * back byte for byte, and a modified one differs only where something was changed.
 */
sealed class XmlNode {
    var parent: XmlElement? = null
        internal set

    abstract fun writeTo(out: StringBuilder)

    override fun toString(): String = buildString { writeTo(this) }
}

/** Character data. [text] is the decoded value; the escaped source is kept while unchanged. */
class XmlText private constructor(private var raw: String?, private var decoded: String?) : XmlNode() {
    constructor(text: String) : this(null, text)

    var text: String
        get() = decoded ?: decodeEntities(raw!!).also { decoded = it }
        set(value) {
            decoded = value
            raw = null
        }

    val isWhitespace: Boolean get() = (raw ?: decoded!!).all { it == ' ' || it == '\t' || it == '\n' || it == '\r' }

    override fun writeTo(out: StringBuilder) {
        out.append(raw ?: escapeText(decoded!!))
    }

    internal companion object {
        fun parsed(raw: String) = XmlText(raw, null)

        /** Layout whitespace, written as is (an escaped carriage return would show as &#13;). */
        fun whitespace(ws: String) = XmlText(ws, null)
    }
}

class XmlCData(var content: String) : XmlNode() {
    override fun writeTo(out: StringBuilder) {
        out.append("<![CDATA[").append(content).append("]]>")
    }
}

class XmlComment(var content: String) : XmlNode() {
    override fun writeTo(out: StringBuilder) {
        out.append("<!--").append(content).append("-->")
    }
}

/** A processing instruction such as `<?xml-stylesheet ...?>`; also the XML declaration. */
class XmlProcessingInstruction(val target: String, val body: String) : XmlNode() {
    override fun writeTo(out: StringBuilder) {
        out.append("<?").append(target).append(body).append("?>")
    }
}

/** A `<!DOCTYPE ...>` declaration, kept verbatim. */
class XmlDoctype(val raw: String) : XmlNode() {
    override fun writeTo(out: StringBuilder) {
        out.append(raw)
    }
}

/**
 * One attribute, with the whitespace before it, the text around `=` and the quote character, so
 * an untouched attribute is written exactly as it was read.
 */
class XmlAttribute internal constructor(
    val name: String,
    private var raw: String?,
    private var decoded: String?,
    internal val leading: String,
    internal val equals: String,
    internal val quote: Char,
) {
    var value: String
        get() = decoded ?: decodeEntities(raw!!).also { decoded = it }
        internal set(v) {
            decoded = v
            raw = null
        }

    internal fun writeTo(out: StringBuilder) {
        out.append(leading).append(name).append(equals).append(quote)
        out.append(raw ?: escapeAttribute(decoded!!, quote)).append(quote)
    }
}

class XmlElement(val name: String) : XmlNode() {
    private val attrs = mutableListOf<XmlAttribute>()
    private val kids = mutableListOf<XmlNode>()

    /** Whitespace between the last attribute and `>` or `/>`. */
    internal var startTail: String = ""

    /** True when the source wrote `<name/>`; kept while the element stays empty. */
    internal var selfClosing: Boolean = true

    /** The source text of the end tag, or null to write `</name>`. */
    internal var endRaw: String? = null

    val localName: String get() = name.substringAfter(':')
    val attributes: List<XmlAttribute> get() = attrs
    val children: List<XmlNode> get() = kids
    val elements: Sequence<XmlElement> get() = kids.asSequence().filterIsInstance<XmlElement>()

    fun elements(localName: String): Sequence<XmlElement> = elements.filter { it.localName == localName }

    fun element(localName: String): XmlElement? = elements(localName).firstOrNull()

    /** Follows a path of local names, such as `"book-info/coverpage"`. */
    fun path(path: String): XmlElement? =
        path.split('/').fold(this as XmlElement?) { e, part -> e?.element(part) }

    operator fun get(attribute: String): String? = attrs.firstOrNull { it.name == attribute }?.value

    /** Sets an attribute; an existing one keeps its place, spacing and quotes. Null removes it. */
    operator fun set(attribute: String, value: String?) {
        val i = attrs.indexOfFirst { it.name == attribute }
        when {
            value == null -> if (i >= 0) attrs.removeAt(i)
            i >= 0 -> if (attrs[i].value != value) attrs[i].value = value
            else -> attrs += XmlAttribute(attribute, null, value, " ", "=", '"')
        }
    }

    /** The concatenated text of this element and its descendants. */
    val textContent: String
        get() = buildString {
            fun walk(n: XmlNode) {
                when (n) {
                    is XmlText -> append(n.text)
                    is XmlCData -> append(n.content)
                    is XmlElement -> n.kids.forEach(::walk)
                    else -> {}
                }
            }
            walk(this@XmlElement)
        }

    fun insert(index: Int, node: XmlNode) {
        require(node.parent == null) { "node already has a parent" }
        node.parent = this
        kids.add(index, node)
    }

    fun append(node: XmlNode) = insert(kids.size, node)

    fun remove(node: XmlNode) {
        if (kids.remove(node)) node.parent = null
    }

    fun indexOf(node: XmlNode): Int = kids.indexOf(node)

    internal fun appendParsed(node: XmlNode) {
        node.parent = this
        kids += node
    }

    internal fun addParsedAttribute(a: XmlAttribute) {
        attrs += a
    }

    override fun writeTo(out: StringBuilder) {
        out.append('<').append(name)
        for (a in attrs) a.writeTo(out)
        out.append(startTail)
        if (kids.isEmpty() && selfClosing) {
            out.append("/>")
            return
        }
        out.append('>')
        for (k in kids) k.writeTo(out)
        out.append(endRaw ?: "</$name>")
    }
}

class XmlDocument internal constructor(
    /** True when the source started with a UTF-8 byte order mark; it is written back. */
    val byteOrderMark: Boolean,
    /** Declaration, comments, processing instructions and whitespace before the root. */
    val prolog: MutableList<XmlNode>,
    val root: XmlElement,
    /** Whatever follows the root element. */
    val epilog: MutableList<XmlNode>,
) {
    fun write(): String = buildString {
        if (byteOrderMark) append('﻿')
        prolog.forEach { it.writeTo(this) }
        root.writeTo(this)
        epilog.forEach { it.writeTo(this) }
    }

    fun toBytes(): ByteArray = write().encodeToByteArray()

    /** The line break the document uses, for inserted nodes. */
    val lineBreak: String by lazy { if (write().contains("\r\n")) "\r\n" else "\n" }
}

internal fun decodeEntities(s: String): String {
    if ('&' !in s) return s
    val out = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c != '&') {
            out.append(c); i++; continue
        }
        val end = s.indexOf(';', i)
        if (end < 0 || end - i > 12) {
            out.append(c); i++; continue
        }
        val name = s.substring(i + 1, end)
        val decoded: String? = when {
            name == "lt" -> "<"
            name == "gt" -> ">"
            name == "amp" -> "&"
            name == "quot" -> "\""
            name == "apos" -> "'"
            name.startsWith("#x") || name.startsWith("#X") -> name.substring(2).toIntOrNull(16)?.let(::codePointString)
            name.startsWith("#") -> name.substring(1).toIntOrNull()?.let(::codePointString)
            else -> null
        }
        if (decoded == null) {
            // An entity we cannot resolve stays as written.
            out.append(c); i++
        } else {
            out.append(decoded); i = end + 1
        }
    }
    return out.toString()
}

private fun codePointString(cp: Int): String? = when {
    cp < 0 || cp > 0x10FFFF -> null
    cp < 0x10000 -> cp.toChar().toString()
    else -> {
        val v = cp - 0x10000
        charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
    }
}

internal fun escapeText(s: String): String = buildString(s.length) {
    for (c in s) when (c) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '\r' -> append("&#13;")
        else -> append(c)
    }
}

internal fun escapeAttribute(s: String, quote: Char): String = buildString(s.length) {
    for (c in s) when (c) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> if (quote == '"') append("&quot;") else append(c)
        '\'' -> if (quote == '\'') append("&apos;") else append(c)
        '\n' -> append("&#10;")
        '\r' -> append("&#13;")
        '\t' -> append("&#9;")
        else -> append(c)
    }
}
