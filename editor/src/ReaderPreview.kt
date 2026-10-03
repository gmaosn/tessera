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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

private val PreviewBar = Color(0xFF111111)
private val PreviewText = Color(0xFFDDDDDD)
private val PreviewDot = Color(0xFF555555)
private val PreviewDotOn = Color(0xFFC7B5E6)

/**
 * Frame-by-frame reading, as a reader app shows it: the camera glides from frame to frame and
 * everything outside the frame takes the frame's background colour.
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
            val cx = remember { Animatable(0f) }
            val cy = remember { Animatable(0f) }
            val scale = remember { Animatable(0f) }
            val mask = remember { Animatable(0f) }
            val target = frames.getOrNull(index)
            LaunchedEffect(index, vw, vh) {
                val (poly, _) = target ?: return@LaunchedEffect
                val pad = 16f
                val s = min((vw - 2 * pad) / (poly.maxX - poly.minX).coerceAtLeast(1), (vh - 2 * pad) / (poly.maxY - poly.minY).coerceAtLeast(1))
                val x = (poly.minX + poly.maxX) / 2f
                val y = (poly.minY + poly.maxY) / 2f
                val spec = tween<Float>(550, easing = FastOutSlowInEasing)
                if (scale.value == 0f) {
                    cx.snapTo(x); cy.snapTo(y); scale.snapTo(s); mask.snapTo(1f)
                    return@LaunchedEffect
                }
                coroutineScope {
                    launch { cx.animateTo(x, spec) }
                    launch { cy.animateTo(y, spec) }
                    launch { scale.animateTo(s, spec) }
                    launch { mask.snapTo(0.35f); mask.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
                }
            }
            Canvas(
                Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { },
            ) {
                if (image == null || target == null) return@Canvas
                val s = scale.value
                val origin = Offset(vw / 2 - cx.value * s, vh / 2 - cy.value * s)
                drawImage(
                    image, dstOffset = IntOffset(origin.x.roundToInt(), origin.y.roundToInt()),
                    dstSize = IntSize((image.width * s).roundToInt(), (image.height * s).roundToInt()), filterQuality = FilterQuality.High,
                )
                val (poly, bg) = target
                val outside = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, Size(vw, vh)))
                    poly.points.forEachIndexed { k, p ->
                        val o = Offset(origin.x + p.x * s, origin.y + p.y * s)
                        if (k == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
                    }
                    close()
                }
                drawPath(outside, (parseColor(bg) ?: fallbackBg).copy(alpha = mask.value))
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
            Label("← →  case · Échap  fermer", color = PreviewText, size = 12.5.sp)
        }
    }
}
