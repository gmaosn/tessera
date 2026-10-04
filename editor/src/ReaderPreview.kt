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
import androidx.compose.ui.zIndex
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

/** Durations of the reading transitions, in milliseconds: brisk, as in a reader app. */
private const val FRAME_MOVE_MS = 350

private const val PAGE_FADE_MS = 200

private val PreviewBar = Color(0xFF111111)
private val PreviewText = Color(0xFFDDDDDD)
private val PreviewDot = Color(0xFF555555)
private val PreviewDotOn = Color(0xFFC7B5E6)

/** One stop of the reading: a page and one of its frames (the whole page when it has none). */
private data class Stop(val page: Int, val frame: Int)

/**
 * Frame-by-frame reading, as a reader app shows it, through the whole book: the camera glides
 * from frame to frame and everything outside the frame takes the frame's background colour; the
 * outline morphs from one frame to the next. After a page's last frame comes the next page's
 * first one, with a fade; a page without frames is shown whole. [onClose] gets the page reached.
 */
@Composable
fun ReaderPreview(session: Session, images: ImageCache, preparer: Preparer? = null, onClose: (page: Int) -> Unit) {
    val pages = session.pages
    var stop by remember { mutableStateOf(Stop(session.pageIndex, 0)) }
    val page = pages[stop.page]
    val pageImage by rememberPageImage(images, page.imageHref)
    val image = pageImage.bitmap
    val enhanced by rememberEnhanced(images, page.imageHref, EnhancePrefs.reader)
    var enhanceOpen by remember { mutableStateOf(false) }
    var comparing by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // The next page is decoded while this one is read.
    LaunchedEffect(stop.page) { pages.getOrNull(stop.page + 1)?.let { images.page(it.imageHref) } }

    fun framesOf(index: Int): Int = pages[index].frames.count { it.polygon != null }.coerceAtLeast(1)
    fun next() {
        stop = when {
            stop.frame < framesOf(stop.page) - 1 -> stop.copy(frame = stop.frame + 1)
            stop.page < pages.size - 1 -> Stop(stop.page + 1, 0)
            else -> stop
        }
    }
    fun previous() {
        stop = when {
            stop.frame > 0 -> stop.copy(frame = stop.frame - 1)
            stop.page > 0 -> Stop(stop.page - 1, framesOf(stop.page - 1) - 1)
            else -> stop
        }
    }

    val pageBg = parseColor(page.bgcolor ?: session.document.body?.get("bgcolor")) ?: Color.Black
    // This page's frames; a page without any reads as one frame covering the whole image.
    val frames: List<Pair<Polygon, String?>> = page.frames.mapNotNull { f -> f.polygon?.let { it to f.bgcolor } }.ifEmpty {
        if (image == null) emptyList() else listOf(Polygon.rectangle(0, 0, image.width, image.height) to null)
    }
    // A high-definition page is enhanced frame by frame: the frame read fills the screen, and a
    // frame costs a fraction of the page. Frames done stay enhanced while the page is read.
    val settings = EnhancePrefs.reader
    val byFrame = image != null && images.isHighDefinition(page.imageHref) &&
        (settings.mode == tessera.editor.enhance.EnhanceMode.Restore || settings.mode == tessera.editor.enhance.EnhanceMode.SuperRes)
    val frameRegions = if (byFrame && image != null) frames.map { Region.around(it.first, image.width, image.height) } else emptyList()
    val readyRegions = remember(page.imageHref, settings) { androidx.compose.runtime.mutableStateMapOf<Region, ImageBitmap>() }
    val currentRegion = frameRegions.getOrNull(stop.frame)
    // Frames of this page computed earlier (or by the preparer meanwhile) show at once.
    LaunchedEffect(page.imageHref, settings, frameRegions.size, preparer?.done) {
        for (r in frameRegions) if (r !in readyRegions) images.enhancedRegion(page.imageHref, r, settings, computeIfMissing = false)?.let { readyRegions[r] = it }
    }
    LaunchedEffect(currentRegion, settings) {
        val r = currentRegion ?: return@LaunchedEffect
        images.enhancedRegion(page.imageHref, r, settings)?.let { readyRegions[r] = it }
    }
    LaunchedEffect(stop, byFrame, currentRegion) {
        images.shown = listOfNotNull(currentRegion?.let { r -> page.imageHref?.let { r.id(it) } } ?: page.imageHref)
    }
    // The frames ahead, in reading order, across pages: the preparer goes on after the reader
    // is closed, behind whatever is shown.
    LaunchedEffect(stop, settings.mode) {
        if (settings.mode == tessera.editor.enhance.EnhanceMode.SuperRes) preparer?.ahead(stop.page, stop.frame)
    }

    Column(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable().onPreviewKeyEvent { e ->
            if (e.key == Key.C) {
                comparing = e.type == KeyEventType.KeyDown && EnhancePrefs.reader.active
                return@onPreviewKeyEvent true
            }
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.Escape, Key.Spacebar -> { onClose(stop.page); true }
                Key.DirectionRight, Key.DirectionDown, Key.PageDown -> { next(); true }
                Key.DirectionLeft, Key.DirectionUp, Key.PageUp -> { previous(); true }
                else -> false
            }
        },
    ) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val vw = constraints.maxWidth.toFloat()
            val vh = constraints.maxHeight.toFloat()
            // One progress value drives a move inside a page: camera, frame outline and background
            // together, so nothing jumps or blinks. A new page fades in instead.
            val progress = remember { Animatable(1f) }
            val fade = remember { Animatable(1f) }
            var from by remember { mutableStateOf<Shot?>(null) }
            var to by remember { mutableStateOf<Shot?>(null) }
            var shownPage by remember { mutableIntStateOf(-1) }
            LaunchedEffect(stop, image, vw, vh) {
                if (image == null) return@LaunchedEffect
                val (poly, bg) = frames.getOrNull(stop.frame.coerceAtMost(frames.size - 1)) ?: return@LaunchedEffect
                val target = Shot.of(poly, parseColor(bg) ?: pageBg, vw, vh)
                val samePage = shownPage == stop.page
                val now = to?.let { t -> from?.lerp(t, progress.value) ?: t }
                if (now == null || !samePage) {
                    from = target; to = target; progress.snapTo(1f)
                    if (shownPage >= 0 && !samePage) {
                        shownPage = stop.page
                        fade.snapTo(0f)
                        fade.animateTo(1f, tween(PAGE_FADE_MS, easing = FastOutSlowInEasing))
                    }
                    shownPage = stop.page
                    return@LaunchedEffect
                }
                from = now; to = target
                progress.snapTo(0f)
                progress.animateTo(1f, tween(FRAME_MOVE_MS, easing = FastOutSlowInEasing))
            }
            Canvas(Modifier.fillMaxSize()) {
                val a = from ?: return@Canvas
                val b = to ?: return@Canvas
                if (image == null || shownPage != stop.page) return@Canvas
                val shot = a.lerp(b, progress.value)
                val s = shot.scale
                val origin = Offset(vw / 2 - shot.cx * s, vh / 2 - shot.cy * s)
                // Sub-pixel placement: rounding to whole pixels makes the page tremble in motion.
                withTransform({ translate(origin.x, origin.y); scale(s, s, Offset.Zero) }) {
                    val shown = if (comparing) null else enhanced
                    if (shown != null) {
                        drawImage(shown, dstSize = androidx.compose.ui.unit.IntSize(image.width, image.height), filterQuality = FilterQuality.High, alpha = fade.value)
                    } else {
                        drawImage(image, filterQuality = FilterQuality.High, alpha = fade.value)
                    }
                    // Enhanced frames, each exactly over its own pixels.
                    if (!comparing) for ((r, bmp) in readyRegions) {
                        drawImage(
                            bmp, dstOffset = androidx.compose.ui.unit.IntOffset(r.x, r.y),
                            dstSize = androidx.compose.ui.unit.IntSize(r.width, r.height), filterQuality = FilterQuality.High, alpha = fade.value,
                        )
                    }
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
            if (pageImage.loading) {
                Label(Strings.loading, Modifier.align(Alignment.Center), color = PreviewText)
            }
            if (comparing && enhanced != null) ComparingBadge(Modifier.align(Alignment.TopCenter).padding(top = 14.dp))
            val href = page.imageHref
            if (byFrame && currentRegion != null && currentRegion !in readyRegions && href != null && !comparing) {
                Toast(Strings.framePill(settings.mode == tessera.editor.enhance.EnhanceMode.SuperRes, images.superResProgress[currentRegion.id(href)]), Modifier.align(Alignment.TopCenter).padding(top = 14.dp))
            } else if (!byFrame && settings.mode == tessera.editor.enhance.EnhanceMode.SuperRes && enhanced == null && image != null) {
                Toast(Strings.superResPill(images.superResProgress[href]), Modifier.align(Alignment.TopCenter).padding(top = 14.dp))
            }
            if (enhanceOpen) {
                EnhancePanel(
                    EnhancePrefs.reader, busy = if (byFrame) currentRegion != null && currentRegion !in readyRegions else EnhancePrefs.reader.active && enhanced == null && image != null,
                    onChange = { EnhancePrefs.reader = it; EnhancePrefs.onChange?.invoke() },
                    progress = images.superResProgress[if (byFrame && currentRegion != null && page.imageHref != null) currentRegion.id(page.imageHref!!) else page.imageHref], storePlace = images.store?.place,
                    onPrepareBook = if (preparer != null && images.store != null) ({ preparer.book() }) else null,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).zIndex(2f),
                )
            }
            // Click zones: left third goes back, the rest goes forward.
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { previous() })
                Box(Modifier.weight(2f).fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { next() })
            }
        }
        Row(
            Modifier.fillMaxWidth().background(PreviewBar).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.clip(CircleShape).border(1.dp, Color(0xFF444444), CircleShape).clickable { onClose(stop.page) }.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Label(Strings.close, color = PreviewText, size = 12.5.sp)
            }
            val where = if (page.isCover) Strings.coverPage else Strings.pageOf(stop.page + 1, pages.size)
            Label("$where · " + Strings.frameOf(stop.frame + 1, framesOf(stop.page)), color = PreviewText, size = 12.5.sp)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0 until framesOf(stop.page)).forEach { k -> Box(Modifier.size(8.dp).clip(CircleShape).background(if (k == stop.frame) PreviewDotOn else PreviewDot)) }
            }
            Spacer(Modifier.weight(1f))
            if (preparer != null && preparer.active) {
                Label(Strings.preparing(preparer.done, preparer.total, preparer.wholeBook, preparer.current?.let { images.superResProgress[it] }), color = PreviewDotOn, size = 12.5.sp, maxLines = 1)
            }
            Label(Strings.previewKeys, color = PreviewText, size = 12.5.sp)
            Box(Modifier.clip(CircleShape).border(1.dp, if (EnhancePrefs.reader.active) PreviewDotOn else Color(0xFF444444), CircleShape).clickable { enhanceOpen = !enhanceOpen }.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Label((if (EnhancePrefs.reader.active) "✦ " else "✧ ") + Strings.enhanceButton, color = PreviewText, size = 12.5.sp)
            }
            if (EnhancePrefs.reader.active) {
                Box(Modifier.clip(CircleShape).background(Color(0xFF332C42)).holdToShow { comparing = it }.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Label("◐ " + Strings.compare, color = PreviewDotOn, size = 12.5.sp)
                }
            }
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
