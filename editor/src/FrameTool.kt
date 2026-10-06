package tessera.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import tessera.acbf.Point
import tessera.acbf.Polygon
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

enum class Tool { Select, Rectangle, Polygon, Order }

/** A snapping guide line, in image pixels. */
data class Guide(val vertical: Boolean, val at: Int)

/**
 * The drawing tool: everything that happens between the pointer and the page's [shapes] (its
 * frames, or the text areas of one language), in image pixels. The canvas converts screen
 * positions and passes [scale] (screen pixels per image pixel), so that distances on screen stay
 * the same at every zoom.
 */
class FrameTool(private val session: Session, val shapes: Shapes = FrameShapes(session)) {
    var tool by mutableStateOf(Tool.Select)
        private set
    var selected by mutableIntStateOf(-1)
    var hovered by mutableIntStateOf(-1)

    /** The rectangle being dragged out, or the polygon being clicked in. */
    var draft by mutableStateOf<List<Point>?>(null)
        private set
    var draftCursor by mutableStateOf<Point?>(null)
        private set
    var guides by mutableStateOf<List<Guide>>(emptyList())
        private set

    /** In [Tool.Order]: frame indices in the order they were clicked. */
    var order by mutableStateOf<List<Int>>(emptyList())
        private set

    var rightToLeft by mutableStateOf(session.document.readingDirection == "RTL")

    /** Bumped each time a shape is drawn, so that the UI can follow (focus the new text area's field). */
    var created by mutableIntStateOf(0)
        private set

    /** A message for the user, consumed by the UI (a toast). */
    var message by mutableStateOf<String?>(null)

    var imageWidth = 0
    var imageHeight = 0

    private var drag: Drag? = null

    private sealed interface Drag
    private class MoveDrag(val index: Int, val start: Offset, val original: Polygon, val gesture: (ShapeEdit.() -> Unit) -> Unit, var moved: Boolean = false) : Drag
    private class VertexDrag(val index: Int, val vertex: Int, val gesture: (ShapeEdit.() -> Unit) -> Unit) : Drag
    private class RectDrag(val start: Point) : Drag

    val polygons: List<Polygon?> get() = shapes.polygons

    fun select(tool: Tool) {
        if (tool == Tool.Order && polygons.size < 2) {
            message = Strings.orderNeedsTwo; return
        }
        this.tool = tool
        draft = null; draftCursor = null; guides = emptyList(); drag = null
        order = emptyList()
        if (tool != Tool.Select) hovered = -1
    }

    /** Forgets the selection and drafts; called when the page changes. */
    fun pageChanged() {
        selected = -1; hovered = -1; draft = null; draftCursor = null; guides = emptyList(); drag = null; order = emptyList()
        if (tool == Tool.Order) tool = Tool.Select
    }

    // ----- Pointer -----

    fun press(p: Offset, scale: Float, alt: Boolean = false) {
        when (tool) {
            Tool.Select -> pressSelect(p, scale, alt)
            Tool.Rectangle -> {
                val s = snap(p, scale, skip = -1)
                drag = RectDrag(s); draft = listOf(s, s, s, s)
            }
            Tool.Polygon -> {
                val s = snap(p, scale, skip = -1)
                val pts = draft.orEmpty()
                if (pts.size > 2 && distance(pts[0], p) * scale < CLOSE_RADIUS) return closePolygon()
                draft = pts + s
            }
            Tool.Order -> {
                val i = frameAt(p)
                if (i < 0 || i in order) return
                order = order + i
                if (order.size == polygons.size) finishOrder()
            }
        }
    }

    fun move(p: Offset, scale: Float) {
        when (val d = drag) {
            is MoveDrag -> {
                if (!d.moved && hypot(p.x - d.start.x, p.y - d.start.y) * scale < 3f) return
                d.moved = true
                val dx0 = (p.x - d.start.x).roundToInt()
                val dy0 = (p.y - d.start.y).roundToInt()
                // Snap the moved frame's top-left corner, as the eye aligns frames by their edges.
                val corner = snap(Offset((d.original.minX + dx0).toFloat(), (d.original.minY + dy0).toFloat()), scale, d.index, clamp = false)
                val moved = d.original.translated(corner.x - d.original.minX, corner.y - d.original.minY)
                d.gesture { set(d.index, moved) }
            }
            is VertexDrag -> {
                val s = snap(p, scale, d.index)
                val pts = polygons[d.index]!!.points.toMutableList()
                pts[d.vertex] = s
                d.gesture { set(d.index, Polygon(pts)) }
            }
            is RectDrag -> {
                val s = snap(p, scale, -1)
                draft = Polygon.rectangle(d.start.x, d.start.y, s.x, s.y).points
            }
            null -> {
                if (tool == Tool.Polygon && draft != null) draftCursor = snap(p, scale, -1)
                else if (tool == Tool.Select || tool == Tool.Order) hovered = frameAt(p)
            }
        }
    }

    fun release(scale: Float) {
        val d = drag
        drag = null
        guides = emptyList()
        if (d is RectDrag) {
            val r = draft?.let(::Polygon)
            draft = null
            if (r != null && (r.maxX - r.minX) * scale > MIN_SIZE && (r.maxY - r.minY) * scale > MIN_SIZE) {
                shapes.edit { add(r) }
                selected = polygons.size - 1
                created++
                tool = Tool.Select
            }
        }
    }

    private fun pressSelect(p: Offset, scale: Float, alt: Boolean) {
        val polygons = polygons
        // Handles of the selected frame first: corners, then the middle of each side.
        val sel = polygons.getOrNull(selected)
        if (sel != null) {
            val vertex = sel.points.indexOfFirst { distance(it, p) * scale <= HANDLE_RADIUS }
            if (vertex >= 0) {
                if (alt) {
                    if (sel.points.size > 3) shapes.edit { set(selected, Polygon(sel.points.filterIndexed { k, _ -> k != vertex })) }
                    else message = Strings.threePointsMinimum
                    return
                }
                drag = VertexDrag(selected, vertex, shapes.begin()); return
            }
            val mid = sel.points.indices.indexOfFirst { k -> distance(midpoint(sel, k), p) * scale <= MID_RADIUS }
            if (mid >= 0) {
                val gesture = shapes.begin()
                val m = midpoint(sel, mid)
                val index = selected
                gesture { set(index, Polygon(sel.points.toMutableList().apply { add(mid + 1, m) })) }
                drag = VertexDrag(selected, mid + 1, gesture); return
            }
        }
        val i = frameAt(p)
        selected = i
        if (i >= 0) drag = MoveDrag(i, p, polygons[i]!!, shapes.begin())
    }

    /** True when a press at [p] would act on a frame or a handle of the selected one. */
    fun wantsPress(p: Offset, scale: Float): Boolean {
        if (frameAt(p) >= 0) return true
        val sel = polygons.getOrNull(selected) ?: return false
        return sel.points.any { distance(it, p) * scale <= HANDLE_RADIUS } ||
            sel.points.indices.any { k -> distance(midpoint(sel, k), p) * scale <= MID_RADIUS }
    }

    /** The frame under [p]: the smallest one containing it, so nested frames stay reachable. */
    fun frameAt(p: Offset): Int =
        polygons.withIndex().filter { (_, poly) -> poly != null && poly.contains(p.x.toDouble(), p.y.toDouble()) }
            .minByOrNull { it.value!!.area }?.index ?: -1

    // ----- Keys -----

    /** Enter: close the polygon, or finish the reading order. */
    fun confirm() {
        when {
            tool == Tool.Polygon && draft != null -> closePolygon()
            tool == Tool.Order -> finishOrder()
        }
    }

    /** Escape: step back one level (draft, mode, selection). */
    fun cancel() {
        when {
            draft != null -> { draft = null; draftCursor = null }
            tool == Tool.Order -> select(Tool.Select)
            tool != Tool.Select -> select(Tool.Select)
            else -> selected = -1
        }
    }

    /** Backspace or Delete: drop the last polygon point, or delete the selected frame. */
    fun delete() {
        val pts = draft
        if (tool == Tool.Polygon && pts != null) {
            draft = pts.dropLast(1).ifEmpty { null }
            return
        }
        val i = selected
        if (tool == Tool.Select && i in polygons.indices) {
            shapes.edit { remove(i) }
            selected = -1
        }
    }

    /** Arrow keys: move the selected frame by [dx], [dy] pixels. */
    fun nudge(dx: Int, dy: Int) {
        val i = selected
        val poly = polygons.getOrNull(i) ?: return
        shapes.edit { set(i, poly.translated(dx, dy)) }
    }

    /** Moves frame [from] to reading position [to] (the list's drag handle). */
    fun reorder(from: Int, to: Int) {
        val count = polygons.size
        if (from == to || from !in 0 until count || to !in 0 until count) return
        shapes.edit { reorder((0 until count).toMutableList().apply { add(to, removeAt(from)) }) }
        selected = to
    }

    /**
     * Sorts the frames into rows from top to bottom, and each row in the reading direction. A
     * frame joins a row when it starts above the lower third of the row's height.
     */
    fun autoOrder() {
        val polys = polygons
        if (polys.size < 2 || polys.any { it == null }) return
        val sorted = polys.withIndex().sortedBy { it.value!!.minY }
        val rows = mutableListOf<MutableList<IndexedValue<Polygon?>>>()
        var rowTop = 0
        var rowBottom = 0
        for (item in sorted) {
            val p = item.value!!
            if (rows.isNotEmpty() && p.minY < rowBottom - (rowBottom - rowTop) * 0.35) {
                rows.last() += item; rowBottom = maxOf(rowBottom, p.maxY)
            } else {
                rows += mutableListOf(item); rowTop = p.minY; rowBottom = p.maxY
            }
        }
        val target = rows.flatMap { row -> if (rightToLeft) row.sortedByDescending { it.value!!.maxX } else row.sortedBy { it.value!!.minX } }.map { it.index }
        applyOrder(target)
        message = if (rightToLeft) Strings.autoOrderRtl else Strings.autoOrderLtr
    }

    private fun finishOrder() {
        val chosen = order
        if (chosen.isNotEmpty()) {
            val rest = polygons.indices.filter { it !in chosen }
            applyOrder(chosen + rest)
            message = Strings.orderSaved
        }
        order = emptyList()
        tool = Tool.Select
        selected = -1
    }

    /** Puts the frames in the order given by their current indices. */
    private fun applyOrder(target: List<Int>) {
        if (target == target.indices.toList()) return
        shapes.edit { reorder(target) }
        selected = -1
    }

    private fun closePolygon() {
        val pts = draft
        draft = null; draftCursor = null; guides = emptyList()
        if (pts == null || pts.size < 3) return
        shapes.edit { add(Polygon(pts)) }
        selected = polygons.size - 1
        created++
        tool = Tool.Select
    }

    // ----- Snapping -----

    /**
     * Rounds [p] to a pixel and pulls it onto the image edges or the corners' lines of other
     * frames within [SNAP_RADIUS] screen pixels. Records the guides it snapped to.
     */
    private fun snap(p: Offset, scale: Float, skip: Int, clamp: Boolean = true): Point {
        val radius = SNAP_RADIUS / scale
        val xs = mutableListOf(0, imageWidth)
        val ys = mutableListOf(0, imageHeight)
        polygons.forEachIndexed { i, poly -> if (i != skip && poly != null) poly.points.forEach { xs += it.x; ys += it.y } }
        val gx = xs.minByOrNull { abs(it - p.x) }?.takeIf { abs(it - p.x) <= radius }
        val gy = ys.minByOrNull { abs(it - p.y) }?.takeIf { abs(it - p.y) <= radius }
        guides = listOfNotNull(gx?.let { Guide(true, it) }, gy?.let { Guide(false, it) })
        var x = gx ?: p.x.roundToInt()
        var y = gy ?: p.y.roundToInt()
        if (clamp && imageWidth > 0) {
            x = x.coerceIn(0, imageWidth); y = y.coerceIn(0, imageHeight)
        }
        return Point(x, y)
    }

    companion object {
        /** Screen pixels. */
        const val HANDLE_RADIUS = 7f
        const val MID_RADIUS = 6f
        const val SNAP_RADIUS = 8f
        const val CLOSE_RADIUS = 10f
        const val MIN_SIZE = 6f

        fun midpoint(poly: Polygon, k: Int): Point {
            val a = poly.points[k]
            val b = poly.points[(k + 1) % poly.points.size]
            return Point((a.x + b.x) / 2, (a.y + b.y) / 2)
        }

        private fun distance(a: Point, p: Offset) = hypot(a.x - p.x, a.y - p.y)
    }
}
