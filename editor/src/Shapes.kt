package tessera.editor

import tessera.acbf.Polygon
import tessera.acbf.addTextArea
import tessera.acbf.removeTextArea
import tessera.acbf.textAreas

/**
 * What the drawing tool works on: the current page's frames, or its text areas in one language.
 * Shapes are addressed by their position in reading order.
 */
interface Shapes {
    val polygons: List<Polygon?>

    /** One undoable change. */
    fun edit(change: ShapeEdit.() -> Unit)

    /** A continuous change (a drag): one undo step, however many updates follow. */
    fun begin(): (ShapeEdit.() -> Unit) -> Unit
}

interface ShapeEdit {
    fun set(index: Int, polygon: Polygon)
    fun add(polygon: Polygon)
    fun remove(index: Int)
    /** Puts the shapes in the order given by their current indices. */
    fun reorder(target: List<Int>)
}

class FrameShapes(private val session: Session) : Shapes {
    override val polygons: List<Polygon?> get() = session.page.frames.map { it.polygon }

    private fun on(page: tessera.acbf.AcbfPage) = object : ShapeEdit {
        override fun set(index: Int, polygon: Polygon) { page.frames[index].polygon = polygon }
        override fun add(polygon: Polygon) { page.addFrame(polygon) }
        override fun remove(index: Int) = page.removeFrame(page.frames[index])
        override fun reorder(target: List<Int>) {
            val elements = page.frames
            target.forEachIndexed { position, index ->
                val frame = elements[index]
                if (page.frames.indexOf(frame) != position) page.moveFrame(frame, position)
            }
        }
    }

    override fun edit(change: ShapeEdit.() -> Unit) = session.edit { on(it).change() }

    override fun begin(): (ShapeEdit.() -> Unit) -> Unit {
        val g = session.beginGesture()
        return { change -> g.update { on(it).change() } }
    }
}

/** The text areas of the language [lang] gives, on the current page. */
class TextShapes(private val session: Session, private val lang: () -> String) : Shapes {
    override val polygons: List<Polygon?> get() = session.page.textAreas(lang()).map { it.polygon }

    private fun on(page: tessera.acbf.AcbfPage) = object : ShapeEdit {
        val l = lang()
        override fun set(index: Int, polygon: Polygon) { page.textAreas(l)[index].polygon = polygon }
        override fun add(polygon: Polygon) { page.addTextArea(l, polygon) }
        override fun remove(index: Int) = page.removeTextArea(page.textAreas(l)[index])
        /** The order tool is for frames only. */
        override fun reorder(target: List<Int>) {}
    }

    override fun edit(change: ShapeEdit.() -> Unit) = session.editTexts { on(it).change() }

    override fun begin(): (ShapeEdit.() -> Unit) -> Unit {
        val g = session.beginTextGesture()
        return { change -> g.update { on(it).change() } }
    }
}
