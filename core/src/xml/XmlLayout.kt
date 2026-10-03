package acbf.xml

/*
 * Inserting and removing elements while keeping the document's own indentation, so that an edit
 * looks as if the original tool had written it.
 */

/** The whitespace text node directly before [node], if any. */
private fun XmlElement.whitespaceBefore(node: XmlNode): XmlText? {
    val i = indexOf(node)
    return (children.getOrNull(i - 1) as? XmlText)?.takeIf { it.isWhitespace }
}

/** The indentation string (line break included) used before [node], or null when inline. */
private fun XmlElement.indentBefore(node: XmlNode): String? =
    whitespaceBefore(node)?.text?.takeIf { '\n' in it }

/** The indentation used for this element's own children: copied from a sibling, or derived. */
fun XmlElement.childIndent(lineBreak: String): String? {
    elements.firstOrNull()?.let { first -> return indentBefore(first) }
    // No child element yet: one level deeper than this element itself.
    val p = parent ?: return null
    val own = p.indentBefore(this) ?: return null
    // The parent's own indentation; the root element sits at column zero.
    val outer = p.parent.let { gp -> if (gp == null) "" else gp.indentBefore(p) }
    val unit = outer?.let { own.substringAfterLast('\n').removePrefix(it.substringAfterLast('\n')) }
        ?.takeIf { it.isNotEmpty() } ?: " "
    return lineBreak + own.substringAfterLast('\n') + unit
}

/**
 * Inserts [child] right after [anchor] (or as the first element child when [anchor] is null,
 * or at the end when there is no element child), with matching indentation.
 */
fun XmlElement.insertElement(child: XmlElement, anchor: XmlElement?, lineBreak: String) {
    val indent = childIndent(lineBreak)
    if (anchor != null) {
        var at = indexOf(anchor) + 1
        if (indent != null) insert(at++, XmlText.whitespace(indent))
        insert(at, child)
        return
    }
    val first = elements.firstOrNull()
    if (first != null) {
        // Before the first element child, reusing the indentation in front of it.
        val ws = whitespaceBefore(first)
        val at = indexOf(first)
        insert(at, child)
        if (indent != null) insert(at + 1, XmlText.whitespace(indent)) else if (ws != null) insert(at + 1, XmlText.whitespace(ws.text))
        return
    }
    appendElement(child, lineBreak)
}

/** Appends [child] after the last child element, keeping the closing tag on its own line. */
fun XmlElement.appendElement(child: XmlElement, lineBreak: String) {
    val last = elements.lastOrNull()
    if (last != null) return insertElement(child, last, lineBreak)
    val indent = childIndent(lineBreak)
    val closing = parent?.let { p -> (p.children.getOrNull(p.indexOf(this) - 1) as? XmlText) }
        ?.text?.takeIf { '\n' in it }?.let { lineBreak + it.substringAfterLast('\n') }
    if (indent != null) append(XmlText.whitespace(indent))
    append(child)
    if (indent != null && closing != null && children.none { it is XmlText && it.text.isNotBlank() }) {
        append(XmlText.whitespace(closing))
    }
}

/** Removes [child] together with the indentation in front of it. */
fun XmlElement.removeElement(child: XmlElement) {
    val ws = whitespaceBefore(child)
    remove(child)
    if (ws != null && '\n' in ws.text) {
        // Keep the whitespace if it was the only thing separating the previous node from the end.
        remove(ws)
        if (elements.none()) {
            // The element became empty of children: drop leftover indentation too.
            children.filterIsInstance<XmlText>().filter { it.isWhitespace }.forEach { remove(it) }
        }
    }
}
