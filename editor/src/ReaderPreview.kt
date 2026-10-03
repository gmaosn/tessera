package tessera.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.drawscope.withTransform
import tessera.acbf.Polygon
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

private val PreviewBar = Color(0xFF111111)
private val PreviewText = Color(0xFFDDDDDD)
private val PreviewDot = Color(0xFF555555)
private val PreviewDotOn = Color(0xFFC7B5E6)

/**
 * Frame-by-frame reading, as a reader app shows it: the camera glides from frame to frame and
 * everything outside the frame takes the frame's background colour. The outline morphs from one
 * frame to the next during the move.
 */
@Composable
fun ReaderPreview(session: Session, image: ImageBitmap?, onClose: () -> Unit) {
    val page = session.page
    val frames = page.frames.mapNotNull { f -> f.polygon?.let { it to f.bgcolor } }
    var index by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    val fallbackBg = parseColor(page.bgcolor ?: session.document.body?.get("bgcolor")) ?: Color.Black
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.Escape, Key.Spacebar -> { onClose(); true }
                Key.DirectionRight, Key.DirectionDown, Key.PageDown -> { index = (index + 1).coerceAtMost(frames.size - 1); true }
                Key.DirectionLeft, Key.DirectionUp, Key.PageUp -> { index = (index - 1).coerceAtLeast(0); true }
                else -> false
            }
        },
    ) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val vw = constraints.maxWidth.toFloat()
            val vh = constraints.maxHeight.toFloat()
            // One progress value drives the whole transition: camera, frame outline and
            // background move together, so nothing jumps or blinks between two frames.
            val progress = remember { Animatable(1f) }
            var from by remember { mutableStateOf<Shot?>(null) }
            var to by remember { mutableStateOf<Shot?>(null) }
            LaunchedEffect(index, vw, vh) {
                val (poly, bg) = frames.getOrNull(index) ?: return@LaunchedEffect
                val target = Shot.of(poly, parseColor(bg) ?: fallbackBg, vw, vh)
                val now = to?.let { t -> from?.lerp(t, progress.value) ?: t }
                if (now == null) {
                    from = target; to = target; progress.snapTo(1f)
                    return@LaunchedEffect
                }
                from = now; to = target
                progress.snapTo(0f)
                progress.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
            }
            Canvas(Modifier.fillMaxSize()) {
                val a = from ?: return@Canvas
                val b = to ?: return@Canvas
                if (image == null) return@Canvas
                val shot = a.lerp(b, progress.value)
                val s = shot.scale
                val origin = Offset(vw / 2 - shot.cx * s, vh / 2 - shot.cy * s)
                // Sub-pixel placement: rounding to whole pixels makes the page tremble in motion.
                withTransform({ translate(origin.x, origin.y); scale(s, s, Offset.Zero) }) {
                    drawImage(image, filterQuality = FilterQuality.High)
                }
                val outside = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, Size(vw, vh)))
                    shot.outline.forEachIndexed { k, p ->
                        val o = Offset(origin.x + p.x * s, origin.y + p.y * s)
                        if (k == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
                    }
                    close()
                }
                drawPath(outside, shot.background)
            }
            // Click zones: left third goes back, the rest goes forward.
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { index = (index - 1).coerceAtLeast(0) })
                Box(Modifier.weight(2f).fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { index = (index + 1).coerceAtMost(frames.size - 1) })
            }
        }
        Row(
            Modifier.fillMaxWidth().background(PreviewBar).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.clip(CircleShape).border(1.dp, Color(0xFF444444), CircleShape).clickable(onClick = onClose).padding(horizontal = 12.dp, vertical = 4.dp)) {
                Label(Strings.close, color = PreviewText, size = 12.5.sp)
            }
            Label(Strings.frameOf(index + 1, frames.size), color = PreviewText, size = 12.5.sp)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                frames.indices.forEach { k -> Box(Modifier.size(8.dp).clip(CircleShape).background(if (k == index) PreviewDotOn else PreviewDot)) }
            }
            Spacer(Modifier.weight(1f))
            Label(Strings.previewKeys, color = PreviewText, size = 12.5.sp)
        }
    }
}

/** What the reader shows: the camera, the frame outline in image pixels, the background. */
private class Shot(val cx: Float, val cy: Float, val scale: Float, val outline: List<Offset>, val background: Color) {
    fun lerp(to: Shot, t: Float): Shot = Shot(
        cx + (to.cx - cx) * t,
        cy + (to.cy - cy) * t,
        // Zooming feels even when the scale changes geometrically.
        exp(ln(scale) + (ln(to.scale) - ln(scale)) * t),
        outline.zip(to.outline) { p, q -> Offset(p.x + (q.x - p.x) * t, p.y + (q.y - p.y) * t) },
        androidx.compose.ui.graphics.lerp(background, to.background, t),
    )

    companion object {
        private const val PADDING = 16f
        private const val OUTLINE_POINTS = 128

        fun of(poly: Polygon, background: Color, vw: Float, vh: Float): Shot {
            val s = min((vw - 2 * PADDING) / (poly.maxX - poly.minX).coerceAtLeast(1), (vh - 2 * PADDING) / (poly.maxY - poly.minY).coerceAtLeast(1))
            return Shot((poly.minX + poly.maxX) / 2f, (poly.minY + poly.maxY) / 2f, s, outline(poly), background)
        }

        /**
         * The polygon walked at even steps, always in the same direction and starting near its
         * top-left corner, so that two frames' outlines can morph point by point.
         */
        fun outline(poly: Polygon): List<Offset> {
            var pts = poly.points.map { Offset(it.x.toFloat(), it.y.toFloat()) }
            if (poly.signedArea < 0) pts = pts.reversed()
            val start = pts.indices.minBy { (pts[it].x - poly.minX).let { d -> d * d } + (pts[it].y - poly.minY).let { d -> d * d } }
            pts = pts.drop(start) + pts.take(start)
            val lengths = pts.indices.map { k -> (pts[(k + 1) % pts.size] - pts[k]).getDistance() }
            val total = lengths.sum()
            if (total <= 0f) return List(OUTLINE_POINTS) { pts[0] }
            val out = ArrayList<Offset>(OUTLINE_POINTS)
            var segment = 0
            var walked = 0f
            for (k in 0 until OUTLINE_POINTS) {
                val at = total * k / OUTLINE_POINTS
                while (segment < pts.size - 1 && walked + lengths[segment] < at) walked += lengths[segment++]
                val a = pts[segment]
                val b = pts[(segment + 1) % pts.size]
                val f = if (lengths[segment] > 0f) ((at - walked) / lengths[segment]).coerceIn(0f, 1f) else 0f
                out += Offset(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
            }
            return out
        }
    }
}
