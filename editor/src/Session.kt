package tessera.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import tessera.acbf.AcbfPage
import tessera.acbf.Comic
import tessera.acbf.FrameState
import tessera.xml.XmlElement

/**
 * An open comic in the editor: the current page, undo and redo, and whether there is
 * something to save. Every change goes through [edit] so that it can be undone.
 */
class Session(comic: Comic, fileName: String) {
    /** Changes after « Save as ». */
    var fileName by mutableStateOf(fileName)

    var comic: Comic by mutableStateOf(comic)
        private set

    /** Bumped on every change to the document; composables read it to refresh. */
    var revision by mutableIntStateOf(0)
        private set

    var dirty by mutableStateOf(comic.generated)
        private set

    var pageIndex by mutableIntStateOf(if (comic.document.pages.size > 1) 1 else 0)
        private set

    val document get() = comic.document
    val pages: List<AcbfPage> get() = document.pages
    val page: AcbfPage get() = pages[pageIndex]

    private sealed interface Step {
        val pageIndex: Int
    }

    private class FramesStep(override val pageIndex: Int, val state: List<FrameState>) : Step
    private class AttributeStep(override val pageIndex: Int, val element: XmlElement, val name: String, val value: String?) : Step

    private val undoStack = ArrayDeque<Step>()
    private val redoStack = ArrayDeque<Step>()
    val canUndo: Boolean get() = revision >= 0 && undoStack.isNotEmpty()
    val canRedo: Boolean get() = revision >= 0 && redoStack.isNotEmpty()

    /** The `points` each frame had when the file was opened or last saved, to show changes. */
    private val savedPoints = HashMap<XmlElement, String?>()

    init {
        rememberSaved()
    }

    fun goToPage(index: Int) {
        if (index in pages.indices) pageIndex = index
    }

    /** Changes the current page's frames, as one undoable step. */
    fun edit(change: (AcbfPage) -> Unit) {
        val before = page.frameState()
        change(page)
        if (before.map { it.element to it.points } == page.frameState().map { it.element to it.points }) return
        push(FramesStep(pageIndex, before))
        changed()
    }

    /** Begins a continuous change (a drag): one undo step, however many [update]s follow. */
    fun beginGesture(): Gesture = Gesture(pageIndex, page.frameState())

    inner class Gesture internal constructor(private val pageIndex: Int, private val before: List<FrameState>) {
        private var recorded = false

        fun update(change: (AcbfPage) -> Unit) {
            val page = pages[pageIndex]
            change(page)
            if (!recorded) {
                if (before.map { it.points } == page.frameState().map { it.points }) return
                push(FramesStep(pageIndex, before)); recorded = true
            }
            changed()
        }
    }

    /** Sets an attribute of the current page (or another element), undoably. */
    fun setAttribute(element: XmlElement, name: String, value: String?) {
        if (element[name] == value) return
        push(AttributeStep(pageIndex, element, name, element[name]))
        element[name] = value
        changed()
    }

    fun undo() = travel(undoStack, redoStack)
    fun redo() = travel(redoStack, undoStack)

    private fun travel(from: ArrayDeque<Step>, to: ArrayDeque<Step>) {
        val step = from.removeLastOrNull() ?: return
        pageIndex = step.pageIndex
        to.addLast(capture(step))
        when (step) {
            is FramesStep -> pages[step.pageIndex].restoreFrames(step.state)
            is AttributeStep -> step.element[step.name] = step.value
        }
        changed()
    }

    /** The current state matching [step], to go back the other way. */
    private fun capture(step: Step): Step = when (step) {
        is FramesStep -> FramesStep(step.pageIndex, pages[step.pageIndex].frameState())
        is AttributeStep -> AttributeStep(step.pageIndex, step.element, step.name, step.element[step.name])
    }

    private fun push(step: Step) {
        undoStack.addLast(step)
        if (undoStack.size > 500) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun changed() {
        revision++
        dirty = true
    }

    /** True when this frame's points differ from the saved file. */
    fun isChanged(frame: XmlElement): Boolean = !savedPoints.containsKey(frame) || savedPoints[frame] != frame["points"]

    /** Call after a successful save, with the comic reopened on the saved file. */
    fun saved(reopened: Comic) {
        comic = reopened
        dirty = false
        rememberSaved()
        revision++
    }

    private fun rememberSaved() {
        savedPoints.clear()
        for (p in document.pages) for (f in p.frames) savedPoints[f.element] = f.element["points"]
    }
}
