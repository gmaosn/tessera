package acbf.xml

class XmlParseException(message: String, val line: Int, val column: Int) :
    Exception("$message (line $line, column $column)")

/**
 * Parses XML into a lossless [XmlDocument]. No DTD is read, so entities other than the five
 * predefined ones and character references are kept as written.
 */
object XmlParser {
    fun parse(bytes: ByteArray): XmlDocument = parse(bytes.decodeToString())

    fun parse(source: String): XmlDocument {
        val bom = source.startsWith('﻿')
        return Reader(if (bom) source.substring(1) else source).document(bom)
    }

    private class Reader(val s: String) {
        var i = 0

        fun document(bom: Boolean): XmlDocument {
            val prolog = mutableListOf<XmlNode>()
            var root: XmlElement? = null
            val epilog = mutableListOf<XmlNode>()
            while (i < s.length) {
                val node = when {
                    s.startsWith("<!--", i) -> comment()
                    s.startsWith("<?", i) -> processingInstruction()
                    s.startsWith("<!DOCTYPE", i) -> doctype()
                    s[i] == '<' -> {
                        if (root != null) fail("Content after the root element")
                        root = element(); continue
                    }
                    else -> {
                        val start = i
                        while (i < s.length && s[i] != '<') i++
                        val raw = s.substring(start, i)
                        if (raw.isNotBlank()) fail("Text outside the root element", start)
                        XmlText.parsed(raw)
                    }
                }
                (if (root == null) prolog else epilog) += node
            }
            return XmlDocument(bom, prolog, root ?: fail("No root element"), epilog)
        }

        fun element(): XmlElement {
            val stack = ArrayList<XmlElement>()
            var top: XmlElement? = null
            while (true) {
                if (i >= s.length) fail("Unexpected end of document in <${stack.last().name}>")
                val c = s[i]
                if (c != '<') {
                    val start = i
                    i = s.indexOf('<', i).let { if (it < 0) s.length else it }
                    stack.last().appendParsed(XmlText.parsed(s.substring(start, i)))
                    continue
                }
                when {
                    s.startsWith("</", i) -> {
                        val start = i
                        i += 2
                        val name = name()
                        skipSpace()
                        expect('>')
                        val open = stack.removeLast()
                        if (open.name != name) fail("</$name> closes <${open.name}>", start)
                        open.selfClosing = false
                        val raw = s.substring(start, i)
                        open.endRaw = if (raw == "</$name>") null else raw
                        if (stack.isEmpty()) return open
                    }
                    s.startsWith("<!--", i) -> stack.last().appendParsed(comment())
                    s.startsWith("<![CDATA[", i) -> {
                        val end = s.indexOf("]]>", i + 9)
                        if (end < 0) fail("Unterminated CDATA section")
                        stack.last().appendParsed(XmlCData(s.substring(i + 9, end)))
                        i = end + 3
                    }
                    s.startsWith("<?", i) -> stack.last().appendParsed(processingInstruction())
                    s.startsWith("<!", i) -> fail("Unexpected markup declaration")
                    else -> {
                        val e = startTag()
                        if (stack.isEmpty()) top = e else stack.last().appendParsed(e)
                        if (e.selfClosing) {
                            if (stack.isEmpty()) return e
                        } else {
                            stack += e
                        }
                    }
                }
                if (stack.isEmpty() && top != null) return top
            }
        }

        /** Reads `<name attr="v" ...>` or `.../>`; [XmlElement.selfClosing] tells which. */
        fun startTag(): XmlElement {
            i++ // '<'
            val e = XmlElement(name())
            while (true) {
                val wsStart = i
                skipSpace()
                val leading = s.substring(wsStart, i)
                if (i >= s.length) fail("Unterminated start tag <${e.name}>")
                when (s[i]) {
                    '>' -> {
                        e.startTail = leading; e.selfClosing = false; i++; return e
                    }
                    '/' -> {
                        i++; expect('>'); e.startTail = leading; e.selfClosing = true; return e
                    }
                }
                if (leading.isEmpty()) fail("Expected whitespace before an attribute")
                val name = name()
                val eqStart = i
                skipSpace(); expect('='); skipSpace()
                val equals = s.substring(eqStart, i)
                if (i >= s.length || (s[i] != '"' && s[i] != '\'')) fail("Expected a quoted value for $name")
                val quote = s[i++]
                val end = s.indexOf(quote, i)
                if (end < 0) fail("Unterminated value for $name")
                if (e.attributes.any { it.name == name }) fail("Duplicate attribute $name")
                e.addParsedAttribute(XmlAttribute(name, s.substring(i, end), null, leading, equals, quote))
                i = end + 1
            }
        }

        fun comment(): XmlComment {
            val end = s.indexOf("-->", i + 4)
            if (end < 0) fail("Unterminated comment")
            return XmlComment(s.substring(i + 4, end)).also { i = end + 3 }
        }

        fun processingInstruction(): XmlProcessingInstruction {
            i += 2
            val target = name()
            val end = s.indexOf("?>", i)
            if (end < 0) fail("Unterminated processing instruction")
            return XmlProcessingInstruction(target, s.substring(i, end)).also { i = end + 2 }
        }

        fun doctype(): XmlDoctype {
            val start = i
            var depth = 0
            while (i < s.length) {
                when (s[i]) {
                    '[' -> depth++
                    ']' -> depth--
                    '>' -> if (depth <= 0) {
                        i++; return XmlDoctype(s.substring(start, i))
                    }
                }
                i++
            }
            fail("Unterminated DOCTYPE", start)
        }

        fun name(): String {
            val start = i
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '>' || c == '/' || c == '=' || c == '?') break
                i++
            }
            if (i == start) fail("Expected a name")
            return s.substring(start, i)
        }

        fun skipSpace() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        fun expect(c: Char) {
            if (i >= s.length || s[i] != c) fail("Expected '$c'")
            i++
        }

        fun fail(message: String, at: Int = i): Nothing {
            var line = 1
            var lineStart = 0
            for (k in 0 until minOf(at, s.length)) if (s[k] == '\n') {
                line++; lineStart = k + 1
            }
            throw XmlParseException(message, line, at - lineStart + 1)
        }
    }
}
