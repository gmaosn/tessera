package tessera.acbf

import tessera.xml.XmlElement
import tessera.xml.XmlNode
import tessera.xml.XmlParser
import tessera.xml.appendElement
import tessera.xml.insertElement
import tessera.xml.insertOrdered
import tessera.xml.newChild
import tessera.xml.removeElement
import tessera.xml.setText

/** The kinds of text area in the ACBF 1.1 schema; none means plain speech. */
val TEXT_AREA_TYPES = listOf("speech", "commentary", "formal", "letter", "code", "heading", "audio", "thought", "sign")

/** Every language the book has text in: declared layers first, then any page layer not declared. */
val AcbfDocument.textLanguages: List<String>
    get() = (languages.map { it.lang } + pages.flatMap { p -> p.textLayers.map { it.lang } }).filter { it.isNotBlank() }.distinct()

/** The page's text layers, one per language (a page may in theory hold several for one; the first wins). */
val AcbfPage.textLayers: List<AcbfTextLayer>
    get() = element.elements("text-layer").map { AcbfTextLayer(this, it) }.toList()

fun AcbfPage.textLayer(lang: String): AcbfTextLayer? = textLayers.firstOrNull { it.lang == lang }

/** The text areas of [lang] on this page, in document order; empty when there is no such layer. */
fun AcbfPage.textAreas(lang: String): List<AcbfTextArea> = textLayer(lang)?.areas.orEmpty()

/**
 * Adds a text area to the [lang] layer, at [index] (default: last), with one empty paragraph.
 * The layer is created when missing, after the page's image, titles and other text layers.
 */
fun AcbfPage.addTextArea(lang: String, polygon: Polygon, index: Int? = null): AcbfTextArea {
    val layer = textLayer(lang)?.element ?: element.newChild("text-layer").also {
        it["lang"] = lang
        element.insertOrdered(it, listOf("image", "title", "text-layer"), document.xml.lineBreak)
    }
    val areas = layer.elements("text-area").toList()
    val e = layer.newChild("text-area")
    e["points"] = polygon.format()
    val at = (index ?: areas.size).coerceIn(0, areas.size)
    val lineBreak = document.xml.lineBreak
    if (areas.isEmpty()) layer.appendElement(e, lineBreak) else layer.insertElement(e, if (at == 0) null else areas[at - 1], lineBreak)
    e.appendElement(e.newChild("p"), lineBreak)
    return AcbfTextArea(AcbfTextLayer(this, layer), e)
}

/** Removes a text area; its layer goes too when it was the last one, as a layer needs one area. */
fun AcbfPage.removeTextArea(area: AcbfTextArea) {
    val layer = area.layer.element
    layer.removeElement(area.element)
    if (layer.elements("text-area").none()) element.removeElement(layer)
}

class AcbfTextLayer internal constructor(val page: AcbfPage, val element: XmlElement) {
    val lang: String get() = element["lang"].orEmpty()

    /** The background behind the layer's text areas, "#rrggbb", or null. */
    val bgcolor: String? get() = element["bgcolor"]

    val areas: List<AcbfTextArea> get() = element.elements("text-area").map { AcbfTextArea(this, it) }.toList()
}

class AcbfTextArea internal constructor(val layer: AcbfTextLayer, val element: XmlElement) {
    var polygon: Polygon?
        get() = Polygon.parse(element["points"])
        set(value) {
            if (value != null && value != polygon) element["points"] = value.format()
        }

    /** One of [TEXT_AREA_TYPES], or null (speech). */
    var type: String?
        get() = element["type"]
        set(value) { element["type"] = value?.ifBlank { null } }

    var bgcolor: String?
        get() = element["bgcolor"]
        set(value) { element["bgcolor"] = value?.ifBlank { null } }

    /** White text on a dark ground. */
    var inverted: Boolean
        get() = element["inverted"].isTrue()
        set(value) { setFlag("inverted", value) }

    /** No ground drawn behind the text. */
    var transparent: Boolean
        get() = element["transparent"].isTrue()
        set(value) { setFlag("transparent", value) }

    /** Degrees, 0 to 360. */
    var rotation: Int
        get() = element["text-rotation"]?.trim()?.toIntOrNull() ?: 0
        set(value) { element["text-rotation"] = value.mod(360).takeIf { it != 0 }?.toString() }

    /** The text, one paragraph per entry, inline markup flattened. */
    val paragraphs: List<String> get() = element.elements("p").map { it.textContent.trim() }.toList()

    val text: String get() = paragraphs.joinToString("\n")

    /**
     * Sets the text, one paragraph per line. Paragraphs whose text is unchanged keep their inline
     * markup; blank lines are dropped; an empty text keeps one empty paragraph (the schema needs one).
     */
    fun setText(value: String) = writeParagraphs(element, value.split('\n'), layer.page.document.xml.lineBreak)

    /** Sets a boolean attribute, keeping the file's spelling when its meaning is unchanged. */
    private fun setFlag(name: String, value: Boolean) {
        if (element[name].isTrue() == value) return
        element[name] = if (value) "true" else null
    }

    override fun equals(other: Any?) = other is AcbfTextArea && other.element === element
    override fun hashCode() = element.hashCode()
}

private fun String?.isTrue() = this?.trim()?.lowercase().let { it == "true" || it == "1" }

/**
 * Writes [paragraphs] as the `p` children of [block]: unchanged ones are left alone (inline markup
 * kept), changed ones become plain text, extras are removed, new ones added after the last.
 */
internal fun writeParagraphs(block: XmlElement, paragraphs: List<String>, lineBreak: String) {
    val wanted = paragraphs.map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("") }
    val ps = block.elements("p").toList()
    if (ps.map { it.textContent.trim() }.ifEmpty { listOf("") } == wanted && ps.isNotEmpty()) return
    wanted.forEachIndexed { i, text ->
        val p = ps.getOrNull(i)
        if (p != null) {
            if (p.textContent.trim() != text) p.setText(text)
        } else {
            block.insertOrdered(block.newChild("p").also { it.setText(text) }, listOf("p"), lineBreak)
        }
    }
    for (p in ps.drop(wanted.size)) block.removeElement(p)
}

/**
 * A page's text layers as they are now, to bring them back for undo. Other children (image,
 * frames) are kept as the very same nodes, so that frame undo steps stay valid.
 */
class TextState private constructor(private val nodes: List<XmlNode>, private val layers: Map<Int, String>) {
    /** Puts the page's children back as they were when captured. */
    fun restore(page: AcbfPage) {
        val e = page.element
        for (n in e.children.toList()) e.remove(n)
        nodes.forEachIndexed { i, n -> e.append(layers[i]?.let { XmlParser.parse(it).root } ?: n) }
    }

    /** The text layers' exact text, to tell whether anything changed. */
    val signature: String get() = layers.values.joinToString("\u0000")

    companion object {
        fun of(page: AcbfPage): TextState {
            val nodes = page.element.children.toList()
            val layers = nodes.withIndex().filter { (_, n) -> n is XmlElement && n.localName == "text-layer" }.associate { (i, n) -> i to n.toString() }
            return TextState(nodes, layers)
        }
    }
}
