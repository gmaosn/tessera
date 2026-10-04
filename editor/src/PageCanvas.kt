package tessera.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tessera.acbf.Point
import tessera.acbf.Polygon
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Zoom and scroll of the page canvas. A null [zoom] fits the page in the window. */
@Stable
class CanvasView {
    var zoom by mutableStateOf<Float?>(null)
    var pan by mutableStateOf(Offset.Zero)

    /** The scale actually shown, kept up to date by the canvas, for the zoom buttons. */
    var shownScale by mutableStateOf(1f)
        internal set

    internal var viewport = Size.Zero
    internal var imageSize = Size(1f, 1f)
    internal var density = 1f

    fun fit() {
        zoom = null; pan = Offset.Zero
    }

    /** Zooms by [factor] around [anchor] (canvas pixels; the centre by default). */
    fun zoomBy(factor: Float, anchor: Offset? = null) {
        val old = layout()
        val newScale = (old.scale * factor).coerceIn(MIN_ZOOM * density, MAX_ZOOM * density)
        val a = anchor ?: Offset(viewport.width / 2, viewport.height / 2)
        val imagePoint = (a - old.origin) / old.scale
        val newOrigin = a - imagePoint * newScale
        zoom = newScale / density
        pan = newOrigin - centred(newScale)
        pan = clampPan(pan, newScale)
    }

    internal fun scrollBy(delta: Offset) {
        pan = clampPan(pan - delta, layout().scale)
    }

    internal class Layout(val origin: Offset, val scale: Float)

    /** Where the image sits in the canvas, and its scale in canvas pixels per image pixel. */
    internal fun layout(): Layout {
        val margin = 40f * density
        val fit = min((viewport.width - 2 * margin) / imageSize.width, (viewport.height - 2 * margin) / imageSize.height).coerceAtLeast(0.02f)
        val scale = zoom?.let { it * density } ?: fit
        return Layout(centred(scale) + clampPan(pan, scale), scale)
    }

    private fun centred(scale: Float) =
        Offset((viewport.width - imageSize.width * scale) / 2, (viewport.height - imageSize.height * scale) / 2)

    /** The page may scroll until its edge is a margin away from the canvas edge, not further. */
    private fun clampPan(p: Offset, scale: Float): Offset {
        val margin = 40f * density
        val lx = max(0f, (imageSize.width * scale - viewport.width) / 2 + margin)
        val ly = max(0f, (imageSize.height * scale - viewport.height) / 2 + margin)
        return Offset(p.x.coerceIn(-lx, lx), p.y.coerceIn(-ly, ly))
    }

    companion object {
        const val MIN_ZOOM = 0.05f
        const val MAX_ZOOM = 8f
    }
}

@Composable
fun PageCanvas(
    session: Session,
    tool: FrameTool,
    image: ImageBitmap?,
    view: CanvasView,
    focus: FocusRequester,
    modifier: Modifier = Modifier,
    /** An enhanced version of [image] to draw in its place (any size); frames stay on [image]'s pixels. */
    display: ImageBitmap? = null,
) {
    val c = LocalPalette.current
    val density = LocalDensity.current.density
    val measurer = rememberTextMeasurer()
    @Suppress("UNUSED_VARIABLE") val revision = session.revision // redraw on every document change
    val imageSize = image?.let { Size(it.width.toFloat(), it.height.toFloat()) } ?: Size(1000f, 1500f)
    tool.imageWidth = imageSize.width.toInt()
    tool.imageHeight = imageSize.height.toInt()

    BoxWithConstraints(modifier.background(c.well)) {
        view.viewport = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        view.imageSize = imageSize
        view.density = density
        val layout = view.layout()
        view.shownScale = layout.scale / density
        val current by rememberUpdatedState(layout)
        var panning by remember { mutableStateOf(false) }

        val icon = when {
            panning -> PointerIcon.Hand
            tool.tool == Tool.Rectangle || tool.tool == Tool.Polygon -> PointerIcon.Crosshair
            tool.tool == Tool.Order && tool.hovered >= 0 -> PointerIcon.Hand
            tool.tool == Tool.Select && tool.hovered >= 0 -> PointerIcon.Hand
            else -> PointerIcon.Default
        }

        // The page's shadow, under the canvas drawing: only its visible part (plus room for the
        // blur), since a deeply zoomed page is far larger than any layout may be.
        val page = Rect(layout.origin, Size(imageSize.width * layout.scale, imageSize.height * layout.scale))
        val room = 64f * density
        val shown = page.intersect(Rect(-room, -room, view.viewport.width + room, view.viewport.height + room))
        if (shown.width > 0f && shown.height > 0f) {
            Box(
                Modifier.offset { IntOffset(shown.left.roundToInt(), shown.top.roundToInt()) }
                    .size(with(LocalDensity.current) { shown.width.toDp() }, with(LocalDensity.current) { shown.height.toDp() })
                    .shadow(14.dp, RoundedCornerShape(2.dp)),
            )
        }

        Canvas(
            Modifier.fillMaxSize().pointerHoverIcon(icon).pointerInput(tool, view) {
                awaitPointerEventScope {
                    var panFrom: Offset? = null
                    var drawing = false
                    // A second press close by and soon after closes a polygon (double-click).
                    var lastPressTime = 0L
                    var lastPressAt = Offset.Zero
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        val l = current
                        val toImage = { p: Offset -> (p - l.origin) / l.scale }
                        val scaleDp = l.scale / density
                        when (event.type) {
                            PointerEventType.Scroll -> {
                                val d = change.scrollDelta
                                val mods = event.keyboardModifiers
                                // The wheel zooms around the pointer; with a modifier it scrolls.
                                if (mods.isCtrlPressed || mods.isMetaPressed) view.scrollBy(Offset(d.x * 40f * density, d.y * 40f * density))
                                else if (mods.isShiftPressed) view.scrollBy(Offset(d.y * 40f * density, 0f))
                                else view.zoomBy(kotlin.math.exp(-d.y.coerceIn(-6f, 6f) * 0.12f), change.position)
                                change.consume()
                            }
                            PointerEventType.Press -> {
                                focus.requestFocus()
                                val onNothing = tool.tool == Tool.Select && !tool.wantsPress(toImage(change.position), scaleDp)
                                if (!event.buttons.isPrimaryPressed || onNothing) {
                                    // Other buttons, or a drag on no frame, scroll the page.
                                    if (onNothing) tool.selected = -1
                                    panFrom = change.position; panning = true
                                } else {
                                    val double = change.uptimeMillis - lastPressTime < 400 && (change.position - lastPressAt).getDistance() < 8f * density
                                    lastPressTime = change.uptimeMillis; lastPressAt = change.position
                                    if (double && tool.tool == Tool.Polygon && tool.draft != null) {
                                        tool.confirm()
                                    } else {
                                        drawing = true
                                        tool.press(toImage(change.position), scaleDp, event.keyboardModifiers.isAltPressed)
                                    }
                                }
                                change.consume()
                            }
                            PointerEventType.Move -> {
                                val from = panFrom
                                if (from != null) {
                                    view.scrollBy(from - change.position); panFrom = change.position
                                } else {
                                    tool.move(toImage(change.position), scaleDp)
                                }
                            }
                            PointerEventType.Release -> {
                                if (panFrom != null) {
                                    panFrom = null; panning = false
                                } else if (drawing) {
                                    drawing = false
                                    tool.release(scaleDp)
                                }
                            }
                            PointerEventType.Exit -> if (panFrom == null) tool.hovered = -1
                        }
                    }
                }
            },
        ) {
            val origin = layout.origin
            val s = layout.scale
            val map = { p: Point -> Offset(origin.x + p.x * s, origin.y + p.y * s) }
            if (image != null) {
                drawImage(
                    display ?: image, dstOffset = IntOffset(origin.x.roundToInt(), origin.y.roundToInt()),
                    dstSize = androidx.compose.ui.unit.IntSize((imageSize.width * s).roundToInt(), (imageSize.height * s).roundToInt()),
                    filterQuality = if (display != null) FilterQuality.High else FilterQuality.Medium,
                )
            } else {
                drawRect(c.paper, origin, Size(imageSize.width * s, imageSize.height * s))
            }

            val polygons = tool.polygons
            val stroke = 2f * density
            polygons.forEachIndexed { i, poly ->
                if (poly == null) return@forEachIndexed
                val path = poly.path(map)
                val sel = i == tool.selected && tool.tool == Tool.Select
                val alpha = when {
                    sel -> 0.14f
                    i == tool.hovered -> 0.16f
                    else -> 0.07f
                }
                drawPath(path, c.accent.copy(alpha = alpha))
                drawPath(path, if (sel) c.accentDeep else c.accent, style = Stroke(if (sel) stroke * 1.25f else stroke, join = StrokeJoin.Round))
            }

            val dash = PathEffect.dashPathEffect(floatArrayOf(5f * density, 4f * density))
            tool.draft?.let { pts ->
                if (tool.tool == Tool.Rectangle) {
                    val path = Polygon(pts).path(map)
                    drawPath(path, c.accent.copy(alpha = 0.10f))
                    drawPath(path, c.accentDeep, style = Stroke(stroke, pathEffect = dash))
                } else {
                    val line = Path().apply {
                        val all = pts + listOfNotNull(tool.draftCursor)
                        all.forEachIndexed { k, p -> map(p).let { if (k == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
                    }
                    drawPath(line, c.accentDeep, style = Stroke(stroke, pathEffect = dash, join = StrokeJoin.Round))
                    pts.forEachIndexed { k, p -> handle(map(p), if (k == 0) 6f * density else 4f * density, c.paper, c.accentDeep, stroke) }
                }
            }

            for (g in tool.guides) {
                if (g.vertical) drawLine(c.danger, map(Point(g.at, 0)), map(Point(g.at, imageSize.height.toInt())), density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * density, 3f * density)))
                else drawLine(c.danger, map(Point(0, g.at)), map(Point(imageSize.width.toInt(), g.at)), density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * density, 3f * density)))
            }

            val selPoly = polygons.getOrNull(tool.selected)
            if (selPoly != null && tool.tool == Tool.Select) {
                selPoly.points.indices.forEach { k -> drawCircle(c.accentDeep.copy(alpha = 0.45f), 2.5f * density, map(FrameTool.midpoint(selPoly, k))) }
                selPoly.points.forEach { p ->
                    val h = 4.5f * density
                    val o = map(p)
                    drawRoundRect(c.paper, Offset(o.x - h, o.y - h), Size(2 * h, 2 * h), CornerRadius(1.5f * density))
                    drawRoundRect(c.accentDeep, Offset(o.x - h, o.y - h), Size(2 * h, 2 * h), CornerRadius(1.5f * density), style = Stroke(stroke))
                }
            }

            // Reading-order badges at each frame's top-left.
            val r = 11f * density
            polygons.forEachIndexed { i, poly ->
                if (poly == null) return@forEachIndexed
                val centre = map(Point(poly.minX, poly.minY)) + Offset(r + 6f * density, r + 6f * density)
                val pending = tool.tool == Tool.Order && i !in tool.order
                val label = when {
                    tool.tool != Tool.Order -> "${i + 1}"
                    pending -> "?"
                    else -> "${tool.order.indexOf(i) + 1}"
                }
                if (pending) {
                    drawCircle(c.paper, r, centre)
                    drawCircle(c.accent, r, centre, style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * density, 3f * density))))
                } else {
                    drawCircle(if (i == tool.selected && tool.tool == Tool.Select) c.accentDeep else c.accent, r, centre)
                }
                val text = measurer.measure(label, TextStyle(color = if (pending) c.accent else c.onAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                drawText(text, topLeft = centre - Offset(text.size.width / 2f, text.size.height / 2f))
            }
        }
    }
}

private fun Polygon.path(map: (Point) -> Offset) = Path().apply {
    points.forEachIndexed { k, p -> map(p).let { if (k == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
    close()
}

private fun DrawScope.handle(at: Offset, radius: Float, fill: Color, stroke: Color, width: Float) {
    drawCircle(fill, radius, at)
    drawCircle(stroke, radius, at, style = Stroke(width))
}

/** The bounding box of a polygon, in image pixels. */
fun Polygon.bounds(): Rect = Rect(minX.toFloat(), minY.toFloat(), maxX.toFloat(), maxY.toFloat())
