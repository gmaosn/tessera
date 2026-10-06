package tessera.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import tessera.acbf.Polygon
import kotlin.math.roundToInt

@Composable
fun Inspector(session: Session, tool: FrameTool, modifier: Modifier = Modifier, onBookInfo: () -> Unit = {}) {
    val c = LocalPalette.current
    @Suppress("UNUSED_VARIABLE") val revision = session.revision
    Column(modifier.background(c.paper).verticalScroll(rememberScrollState())) {
        FramesSection(session, tool)
        Divider()
        PageSection(session)
        Divider()
        FileSection(session)
        Divider()
        BookSection(session, onBookInfo)
    }
}

/** The book's title and authors at a glance, one click away from editing every field. */
@Composable
internal fun BookSection(session: Session, onBookInfo: () -> Unit) {
    val c = LocalPalette.current
    val m = tessera.acbf.Metadata(session.document)
    val B = tessera.acbf.Section.Book
    InspectorSection {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(Strings.bookInfoShort)
            Spacer(Modifier.weight(1f))
            TextLink(Strings.editBookInfo, onClick = onBookInfo)
        }
        val title = m.texts(B, "book-title").let { t -> t[Strings.language.code] ?: t[null] ?: t.values.firstOrNull() }.orEmpty()
        val authors = m.authors(B).map { m.readAuthor(it).displayName }.filter { it.isNotBlank() }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, c.line, RoundedCornerShape(8.dp)).clickable(onClick = onBookInfo)
                .pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Label(title.ifBlank { Strings.untitled }, weight = FontWeight.Medium, color = if (title.isBlank()) c.muted else c.ink, maxLines = 2)
            Label(authors.joinToString(", ").ifBlank { Strings.noAuthors }, color = c.muted, size = 12.sp, maxLines = 2)
        }
    }
}

@Composable
internal fun Divider() = Box(Modifier.fillMaxWidth().padding(0.dp).background(LocalPalette.current.line).heightIn(1.dp, 1.dp))

@Composable
internal fun InspectorSection(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

@Composable
private fun FramesSection(session: Session, tool: FrameTool) {
    val c = LocalPalette.current
    val polygons = tool.polygons
    InspectorSection {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(Strings.frames)
            CountBadge(polygons.size)
            Spacer(Modifier.weight(1f))
            TextLink(Strings.autoOrder, enabled = polygons.size > 1) { tool.autoOrder() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Choice(Strings.leftToRight, !tool.rightToLeft, Modifier.weight(1f)) { tool.rightToLeft = false }
            Choice(Strings.rightToLeft, tool.rightToLeft, Modifier.weight(1f)) { tool.rightToLeft = true }
        }
        if (polygons.isEmpty()) {
            Box(Modifier.fillMaxWidth().border(1.dp, c.line, RoundedCornerShape(8.dp)).padding(12.dp), contentAlignment = Alignment.Center) {
                Label(Strings.noFrames, color = c.muted, size = 12.sp)
            }
        }
        // Rows can be dragged by their grip to change the reading order.
        var dragging by remember { mutableIntStateOf(-1) }
        var dragY by remember { mutableFloatStateOf(0f) }
        var rowHeight by remember { mutableFloatStateOf(1f) }
        val target = if (dragging >= 0) (dragging + (dragY / rowHeight).roundToInt()).coerceIn(0, polygons.size - 1) else -1
        Column(Modifier.padding(horizontal = 0.dp)) {
            polygons.forEachIndexed { i, poly ->
                val shift = when {
                    dragging < 0 || i == dragging -> 0f
                    i in (dragging + 1)..target -> -rowHeight
                    i in target until dragging -> rowHeight
                    else -> 0f
                }
                FrameRow(
                    index = i, poly = poly, selected = i == tool.selected, hovered = i == tool.hovered,
                    modifier = Modifier.zIndex(if (i == dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (i == dragging) dragY else shift }
                        .onSizeChanged { rowHeight = it.height.toFloat().coerceAtLeast(1f) },
                    onClick = { tool.select(Tool.Select); tool.selected = i },
                    onHover = { inside -> tool.hovered = if (inside) i else if (tool.hovered == i) -1 else tool.hovered },
                    grip = Modifier.pointerInput(i, polygons.size) {
                        detectDragGestures(
                            onDragStart = { dragging = i; dragY = 0f },
                            onDragEnd = { val to = target; val from = dragging; dragging = -1; dragY = 0f; tool.reorder(from, to) },
                            onDragCancel = { dragging = -1; dragY = 0f },
                        ) { change, amount -> change.consume(); dragY += amount.y }
                    },
                    lifted = i == dragging,
                )
            }
        }
    }
}

@Composable
private fun FrameRow(
    index: Int,
    poly: Polygon?,
    selected: Boolean,
    hovered: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onHover: (Boolean) -> Unit,
    grip: Modifier,
    lifted: Boolean,
) {
    val c = LocalPalette.current
    val hover = remember { MutableInteractionSource() }
    val isHovered by hover.collectIsHoveredAsState()
    androidx.compose.runtime.LaunchedEffect(isHovered) { onHover(isHovered) }
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier.fillMaxWidth().then(if (lifted) Modifier.shadow(6.dp, shape) else Modifier).clip(shape)
            .background(
                when {
                    selected || lifted -> c.accentSoft
                    hovered -> c.panel
                    else -> c.paper
                },
            )
            .hoverable(hover).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
            Label("${index + 1}", color = c.onAccent, size = 11.5.sp, weight = FontWeight.Bold)
        }
        MiniShape(poly, Modifier.size(34.dp))
        Column(Modifier.weight(1f)) {
            if (poly == null) {
                Label("—", color = c.muted)
            } else {
                Label(if (poly.isRectangle(3)) Strings.rectangle else Strings.polygon(poly.points.size), weight = FontWeight.Medium, maxLines = 1)
                Label("${poly.maxX - poly.minX} × ${poly.maxY - poly.minY} px", color = c.muted, size = 12.sp, maxLines = 1)
            }
        }
        Box(grip.pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 4.dp, vertical = 6.dp)) { Label("⋮⋮", color = c.muted, letterSpacing = (-2).sp) }
    }
}

@Composable
private fun MiniShape(poly: Polygon?, modifier: Modifier) {
    val c = LocalPalette.current
    Canvas(modifier) {
        if (poly == null) return@Canvas
        val w = (poly.maxX - poly.minX).coerceAtLeast(1).toFloat()
        val h = (poly.maxY - poly.minY).coerceAtLeast(1).toFloat()
        val s = minOf(size.width / w, size.height / h) * 0.84f
        val ox = (size.width - w * s) / 2
        val oy = (size.height - h * s) / 2
        val path = Path().apply {
            poly.points.forEachIndexed { k, p ->
                val o = Offset(ox + (p.x - poly.minX) * s, oy + (p.y - poly.minY) * s)
                if (k == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
            }
            close()
        }
        drawPath(path, c.accentSoft)
        drawPath(path, c.accent, style = Stroke(1.5f * density))
    }
}

@Composable
private fun PageSection(session: Session) {
    val c = LocalPalette.current
    val page = session.page
    InspectorSection {
        SectionTitle(Strings.page)
        // The background a reader shows around a zoomed frame: page, else the book's body.
        val own = page.bgcolor
        val inherited = session.document.body?.get("bgcolor")
        val shown = own ?: inherited ?: "#000000"
        Field(Strings.background) {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, c.line, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(parseColor(shown) ?: c.paper).border(1.dp, c.line, RoundedCornerShape(4.dp)))
                Label(if (own != null) shown else "$shown · ${Strings.inherited}", maxLines = 1)
            }
        }
        if (!page.isCover) {
            Field(Strings.transition) {
                var open by remember { mutableStateOf(false) }
                val value = page.element["transition"]
                Box {
                    Row(
                        Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, c.line, RoundedCornerShape(8.dp)).clickable { open = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Label(value ?: Strings.transitionDefault, maxLines = 1)
                        Label("▾", color = c.muted, size = 11.sp)
                    }
                    DropdownMenu(open, onDismissRequest = { open = false }) {
                        (listOf<String?>(null) + TRANSITIONS).forEach { t ->
                            DropdownMenuItem(onClick = { open = false; session.setAttribute(page.element, "transition", t) }) {
                                Label(t ?: Strings.transitionDefault, weight = if (t == value) FontWeight.SemiBold else FontWeight.Normal)
                            }
                        }
                    }
                }
            }
        }
    }
}

private val TRANSITIONS = listOf("fade", "blend", "scroll_right", "scroll_down", "none")

@Composable
private fun FileSection(session: Session) {
    val c = LocalPalette.current
    InspectorSection {
        SectionTitle(Strings.inFile)
        val frames = session.page.frames
        val text = buildAnnotatedString {
            for (f in frames) {
                val raw = f.element.toString()
                if (session.isChanged(f.element)) withStyle(SpanStyle(color = c.accentDeep)) { append(raw) } else append(raw)
                append('\n')
            }
            if (frames.isEmpty()) append("—")
        }
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.panel).border(1.dp, c.line, RoundedCornerShape(8.dp))
                .heightIn(max = 180.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            BasicText(text, style = TextStyle(color = c.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 17.sp), softWrap = false)
        }
        Label(Strings.inFileNote, color = c.muted, size = 12.sp)
    }
}

@Composable
internal fun Field(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Label(label, Modifier.width(92.dp), color = LocalPalette.current.muted)
        content()
    }
}

@Composable
internal fun Choice(text: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalPalette.current
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier.clip(shape).background(if (on) c.accentSoft else c.paper).border(1.dp, if (on) c.accent else c.line, shape)
            .clickable(onClick = onClick).padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) { Label(text, color = if (on) c.accentDeep else c.ink, size = 12.sp, maxLines = 1) }
}

@Composable
fun TextLink(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = LocalPalette.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(if (hovered && enabled) c.accentSoft else c.paper)
            .hoverable(hover).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 4.dp, vertical = 2.dp),
    ) { Label(text, color = if (enabled) c.accent else c.muted, size = 12.sp, weight = FontWeight.Medium) }
}
