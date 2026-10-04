package tessera.xml

/*
 * Small edits on elements that keep the rest of the document as it was.
 */

/** Replaces the element's content with plain text; leaves it untouched when already equal. */
fun XmlElement.setText(value: String) {
    val only = children.singleOrNull() as? XmlText
    if (only != null && only.text == value) return
    if (children.isEmpty() && value.isEmpty()) return
    for (c in children.toList()) remove(c)
    if (value.isNotEmpty()) append(XmlText(value))
}

/**
 * Inserts [child] where [order] (a list of local names) says it belongs: after the last element
 * whose name comes at or before it, else before the first element. Unknown names do not count.
 */
fun XmlElement.insertOrdered(child: XmlElement, order: List<String>, lineBreak: String) {
    val rank = order.indexOf(child.localName)
    val after = elements.lastOrNull { order.indexOf(it.localName) in 0..rank }
    when {
        after != null -> insertElement(child, after, lineBreak)
        elements.none() -> appendElement(child, lineBreak)
        else -> insertElement(child, null, lineBreak)
    }
}

/** A new element named like this one's children would be (same prefix, if any). */
fun XmlElement.newChild(local: String): XmlElement =
    XmlElement(if (':' in name) name.substringBefore(':') + ":" + local else local)

/** Puts [replacement] where this element is, in its parent. */
fun XmlElement.replaceWith(replacement: XmlElement) {
    val p = parent ?: return
    val i = p.indexOf(this)
    p.remove(this)
    p.insert(i, replacement)
}
