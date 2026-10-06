package tessera.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import tessera.acbf.textAreas
import tessera.acbf.textLanguages
import tessera.editor.enhance.EnhanceMode
import kotlin.math.roundToInt

/**
 * The frame editor: pages on the left, the page in the middle, the inspector on the right, and
 * the hint bar at the bottom. [onSave] writes the comic and returns a message for the user.
 */
@Composable
fun EditorScreen(
    session: Session,
    images: ImageCache,
    onSave: () -> String,
    modifier: Modifier = Modifier,
    tool: FrameTool = remember(session) { FrameTool(session) },
    startPreviewing: Boolean = false,
    /** Incremented by the host (the menu's Save) to save with the usual message. */
    saveRequest: Int = 0,
    /** A message from the host (after « Save as »), shown once per instance. */
    notice: Notice? = null,
    /** Ask for the book's title and authors when the comic had no ACBF document. */
    askBookInfo: Boolean = true,
    /** 0: frames, 1: texts, 2: book info. */
    startMode: Int = 0,
    /** A tab chosen from the host's menu; each instance is applied once. */
    modeRequest: ModeRequest? = null,
    /** Incremented by the host (menu View → Prepare the whole book). */
    prepareRequest: Int = 0,
    /** The host's preparer for this comic, which may outlive this screen; one is made otherwise. */
    hostPreparer: Preparer? = null,
    /** Other comics being prepared in the background, shown in the top bar. */
    background: List<BackgroundBook> = emptyList(),
) {
    val c = LocalPalette.current
    val view = remember(session) { CanvasView() }
    val focus = remember { FocusRequester() }
    var previewing by remember { mutableStateOf(startPreviewing) }
    /** 0: frames, 1: texts, 2: book info. */
    var mode by remember(session) { mutableStateOf(startMode) }
    /** The tab to go back to when leaving Book info. */
    var lastMode by remember(session) { mutableStateOf(if (startMode == 2) 0 else startMode) }
    val texts = remember(session) { TextsView(session.document.textLanguages.firstOrNull()) }
    val textTool = remember(session) { FrameTool(session, TextShapes(session) { texts.lang.orEmpty() }) }
    /** The drawing tool of the tab shown: frames, or text areas. */
    val active = if (mode == 1) textTool else tool
    /** True while the page has the keyboard (not a text field of the inspector). */
    var pageHasKeys by remember { mutableStateOf(false) }
    var askingBookInfo by remember(session) { mutableStateOf(askBookInfo && session.comic.generated) }
    var toast by remember { mutableStateOf<String?>(null) }
    val pageImage by rememberPageImage(images, session.page.imageHref)
    val image = pageImage.bitmap
    LaunchedEffect(session.page.imageHref) { images.wanted = listOfNotNull(session.page.imageHref) }
    val enhanced by rememberEnhanced(images, session.page.imageHref, EnhancePrefs.editor)
    var enhanceOpen by remember { mutableStateOf(false) }
    // The preparer runs on the composition's own (UI) thread, which owns the image cache.
    val uiScope = rememberCoroutineScope()
    val preparer = hostPreparer ?: remember(session) {
        Preparer(images, session, uiScope.coroutineContext[kotlin.coroutines.ContinuationInterceptor] as? kotlinx.coroutines.CoroutineDispatcher ?: kotlinx.coroutines.Dispatchers.Default)
    }
    if (hostPreparer == null) DisposableEffect(preparer) { onDispose { preparer.close() } }
    LaunchedEffect(prepareRequest) { if (prepareRequest > 0) preparer.book() }
    /** Held: show the page without enhancement, to compare. */
    var comparing by remember { mutableStateOf(false) }

    fun say(text: String) {
        toast = text
    }
    tool.message?.let { say(it); tool.message = null }
    textTool.message?.let { say(it); textTool.message = null }
    LaunchedEffect(toast) { if (toast != null) { delay(2400); toast = null } }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Decode the neighbouring pages ahead, so that turning the page is instant.
    LaunchedEffect(session.pageIndex) {
        for (k in listOf(session.pageIndex + 1, session.pageIndex - 1)) session.pages.getOrNull(k)?.let { images.page(it.imageHref) }
    }

    fun goTo(index: Int) {
        Trace.log { "goTo($index) from ${session.pageIndex} of ${session.pages.size}" }
        if (index == session.pageIndex || index !in session.pages.indices) return
        session.goToPage(index); tool.pageChanged(); textTool.pageChanged(); view.fit()
    }

    fun save() {
        if (!session.dirty) say(Strings.nothingToSave) else say(onSave())
    }

    LaunchedEffect(saveRequest) { if (saveRequest > 0) save() }
    LaunchedEffect(notice) { notice?.let { say(it.text) } }

    fun showMode(m: Int) {
        if (m == mode) return
        if (m == 2) lastMode = mode
        mode = m
        focus.requestFocus()
    }
    LaunchedEffect(modeRequest) { modeRequest?.let { showMode(it.mode) } }

    fun preview() {
        previewing = true
    }

    fun onKey(e: KeyEvent): Boolean {
        if (e.key == Key.C && !(e.isMetaPressed || e.isCtrlPressed) && mode == 0) {
            comparing = e.type == KeyEventType.KeyDown && EnhancePrefs.editor.active
            return true
        }
        if (e.type != KeyEventType.KeyDown) return false
        val mod = e.isMetaPressed || e.isCtrlPressed
        when {
            mod && e.key == Key.Z -> { if (e.isShiftPressed) session.redo() else session.undo(); tool.pageChanged(); textTool.pageChanged() }
            mod && e.key == Key.Y -> { session.redo(); tool.pageChanged(); textTool.pageChanged() }
            mod && e.key == Key.One -> showMode(0)
            mod && e.key == Key.Two -> showMode(1)
            mod && e.key == Key.Three -> showMode(2)
            mod && e.key == Key.S -> save()
            mod && (e.key == Key.Equals || e.key == Key.Plus) -> view.zoomBy(1.25f)
            mod && e.key == Key.Minus -> view.zoomBy(1 / 1.25f)
            mod && e.key == Key.Zero -> view.fit()
            mod -> return false
            // Escape leaves Book info, or a text field of the Texts tab for the page.
            mode == 2 && e.key == Key.Escape -> showMode(lastMode)
            mode == 1 && e.key == Key.Escape && !pageHasKeys -> focus.requestFocus()
            // In Book info, and while typing a text, plain keys belong to the text fields.
            mode == 2 || (mode == 1 && !pageHasKeys) -> return false
            e.key == Key.Spacebar -> preview()
            e.key == Key.V -> active.select(Tool.Select)
            e.key == Key.R -> active.select(Tool.Rectangle)
            e.key == Key.P -> active.select(Tool.Polygon)
            e.key == Key.O && mode == 0 -> tool.select(Tool.Order)
            e.key == Key.Escape -> active.cancel()
            e.key == Key.Enter || e.key == Key.NumPadEnter -> active.confirm()
            e.key == Key.Backspace || e.key == Key.Delete -> active.delete()
            e.isAltPressed && (e.key == Key.DirectionRight || e.key == Key.DirectionDown) -> goTo(session.pageIndex + 1)
            e.isAltPressed && (e.key == Key.DirectionLeft || e.key == Key.DirectionUp) -> goTo(session.pageIndex - 1)
            e.key == Key.PageDown -> goTo(session.pageIndex + 1)
            e.key == Key.PageUp -> goTo(session.pageIndex - 1)
            e.key in ARROWS && active.selected >= 0 && active.tool == Tool.Select -> {
                val d = if (e.isShiftPressed) 10 else 1
                active.nudge(
                    when (e.key) { Key.DirectionLeft -> -d; Key.DirectionRight -> d; else -> 0 },
                    when (e.key) { Key.DirectionUp -> -d; Key.DirectionDown -> d; else -> 0 },
                )
            }
            e.key == Key.DirectionDown || e.key == Key.DirectionRight -> goTo(session.pageIndex + 1)
            e.key == Key.DirectionUp || e.key == Key.DirectionLeft -> goTo(session.pageIndex - 1)
            else -> return false
        }
        return true
    }

    Box(modifier.fillMaxSize().background(c.paper)) {
        Column(
            Modifier.fillMaxSize().focusRequester(focus).onFocusChanged { pageHasKeys = it.isFocused }.focusable().onPreviewKeyEvent { Trace.log { "key ${it.key} ${it.type}" }; onKey(it) }
                .then(if (Trace.sink != null) Modifier.traceClicks() else Modifier),
        ) {
            TopBar(session, preparer, images, background, mode, ::showMode, onPrevious = { goTo(session.pageIndex - 1) }, onNext = { goTo(session.pageIndex + 1) }, onPreview = ::preview, onSave = ::save)
            Rule()
            if (mode == 2) InfoScreen(session, Modifier.weight(1f).fillMaxWidth())
            else Row(Modifier.weight(1f).fillMaxWidth()) {
                val lang = texts.lang
                PageStrip(
                    session, images, onSelect = ::goTo, modifier = Modifier.width(118.dp).fillMaxHeight(),
                    count = if (mode == 1) { p -> lang?.let { p.textAreas(it).size } ?: 0 } else { p -> p.frames.size },
                )
                VRule()
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    PageCanvas(
                        session, active, image, view, focus, Modifier.fillMaxSize(), display = if (comparing) null else enhanced,
                        texts = if (mode == 1) textOverlay(session, lang, texts.preview) else null,
                    )
                    if (comparing && enhanced != null) ComparingBadge(Modifier.align(Alignment.TopCenter).padding(top = 14.dp))
                    if ((EnhancePrefs.editor.mode == EnhanceMode.SuperRes || (EnhancePrefs.editor.mode == EnhanceMode.Restore && images.isHighDefinition(session.page.imageHref))) && enhanced == null && image != null) {
                        Toast(if (images.isHighDefinition(session.page.imageHref)) Strings.alreadyHighDefinition else Strings.superResPill(images.superResProgress[session.page.imageHref]), Modifier.align(Alignment.TopCenter).padding(top = 14.dp))
                    }
                    if (pageImage.loading || image == null) {
                        Box(Modifier.align(Alignment.Center).shadow(4.dp, CircleShape).clip(CircleShape).background(c.paper).padding(horizontal = 14.dp, vertical = 6.dp)) {
                            Label(if (pageImage.loading) Strings.loading else Strings.imageMissing, color = c.muted)
                        }
                    }
                    if (mode == 1 && lang == null) Toast(Strings.chooseLanguageFirst, Modifier.align(Alignment.TopCenter).padding(top = 14.dp))
                    else Toolbar(active, withOrder = mode == 0, Modifier.align(Alignment.TopStart).padding(12.dp))
                    ZoomPill(view, EnhancePrefs.editor.active, { enhanceOpen = !enhanceOpen }, { comparing = it }, Modifier.align(Alignment.BottomEnd).padding(12.dp))
                    if (enhanceOpen) {
                        EnhancePanel(
                            EnhancePrefs.editor, busy = EnhancePrefs.editor.active && enhanced == null && image != null && !(EnhancePrefs.editor.mode != EnhanceMode.Sharpen && images.isHighDefinition(session.page.imageHref)),
                            onChange = { EnhancePrefs.editor = it; EnhancePrefs.onChange?.invoke() },
                            progress = images.superResProgress[session.page.imageHref], storePlace = images.store?.place,
                            onPrepareBook = images.store?.let { { preparer.book() } },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 60.dp),
                        )
                    }
                    if (mode == 0 && tool.tool == Tool.Order) OrderBanner(tool, session, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
                }
                VRule()
                if (mode == 1) TextInspector(session, textTool, texts, Modifier.width(300.dp).fillMaxHeight(), onBookInfo = { showMode(2) })
                else Inspector(session, tool, Modifier.width(300.dp).fillMaxHeight(), onBookInfo = { showMode(2) })
            }
            Rule()
            when (mode) {
                2 -> HintBar(Strings.infoMode, Strings.infoHints, null)
                1 -> HintBar(Strings.modeTexts + " · " + Strings.hintMode(textTool.tool), Strings.textHints, Strings.hintRead)
                else -> Hints(tool)
            }
        }
        toast?.let { Toast(it, Modifier.align(Alignment.BottomCenter).padding(bottom = 52.dp)) }
        if (askingBookInfo) {
            BookInfoDialog(session.fileName.substringBeforeLast('.'), suggestion = session.suggested, onDone = { book ->
                if (book != null) {
                    session.replaceGenerated(tessera.acbf.AcbfDocument.create(book, session.pages.mapNotNull { it.imageHref }))
                    tool.pageChanged(); textTool.pageChanged()
                    texts.lang = session.document.textLanguages.firstOrNull()
                }
                askingBookInfo = false
                focus.requestFocus()
            })
        }
        if (previewing) ReaderPreview(session, images, preparer) { reached -> previewing = false; goTo(reached); focus.requestFocus() }
    }
}

/** Logs every press and release that reaches the editor, before anything handles it. */
private fun Modifier.traceClicks() = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            if (e.type == androidx.compose.ui.input.pointer.PointerEventType.Press || e.type == androidx.compose.ui.input.pointer.PointerEventType.Release) {
                val p = e.changes.first().position
                Trace.log { "pointer ${e.type} at ${p.x.toInt()},${p.y.toInt()} px" }
            }
        }
    }
}

/** A message for the editor's toast; each instance is shown once. */
class Notice(val text: String)

/** A tab (0: frames, 1: texts, 2: book info) chosen by the host; each instance is applied once. */
class ModeRequest(val mode: Int)

private val ARROWS = setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)

@Composable
private fun Rule() = Box(Modifier.fillMaxWidth().height(1.dp).background(LocalPalette.current.line))

@Composable
private fun VRule() = Box(Modifier.width(1.dp).fillMaxHeight().background(LocalPalette.current.line))

@Composable
private fun TopBar(session: Session, preparer: Preparer, images: ImageCache, background: List<BackgroundBook>, mode: Int, onMode: (Int) -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onPreview: () -> Unit, onSave: () -> Unit) {
    val c = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().background(c.paper).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RoundButton("‹", session.pageIndex > 0, onPrevious)
            RoundButton("›", session.pageIndex < session.pages.size - 1, onNext)
        }
        // The file name opens Book info: the obvious place to look for the book's details.
        val titleHover = remember { MutableInteractionSource() }
        val titleHovered by titleHover.collectIsHoveredAsState()
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (titleHovered && mode != 2) c.panel else c.paper)
                .hoverable(titleHover).clickable { onMode(2) }.pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Label(session.fileName, weight = FontWeight.SemiBold, maxLines = 1)
                if (session.dirty) Box(Modifier.size(7.dp).clip(CircleShape).background(c.accent))
                if (titleHovered && mode != 2) Label("ⓘ " + Strings.openBookInfo, color = c.accent, size = 11.5.sp, maxLines = 1)
            }
            val version = session.document.version?.let { " · ACBF $it" }.orEmpty()
            val where = if (session.page.isCover) Strings.coverPage else Strings.pageOf(session.pageIndex + 1, session.pages.size)
            Label(where + version, color = c.muted, size = 11.5.sp, maxLines = 1)
        }
        Segmented(listOf(Strings.modeFrames, Strings.modeTexts, Strings.modeInfo), mode, enabled = { true }, onSelect = onMode)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            for (b in background) if (b.preparer.active) BackgroundPill(b)
            if (preparer.active) PreparingPill(preparer, images)
            Pill("▶  " + Strings.read, onPreview)
            Pill(Strings.save, onSave, primary = true)
        }
    }
}

/** What the preparer is doing, and a way to stop it. */
@Composable
private fun PreparingPill(preparer: Preparer, images: ImageCache) {
    val c = LocalPalette.current
    Row(
        Modifier.clip(CircleShape).background(c.accentSoft).padding(start = 12.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Label(Strings.preparing(preparer.done, preparer.total, preparer.wholeBook, preparer.current?.let { images.superResProgress[it] }), color = c.accentDeep, size = 12.sp, maxLines = 1)
        Box(Modifier.size(20.dp).clip(CircleShape).clickable { preparer.stop() }.pointerHoverIcon(PointerIcon.Hand), contentAlignment = Alignment.Center) {
            Label("×", color = c.accentDeep, size = 14.sp)
        }
    }
}

/** Another comic prepared in the background: its name, progress and a way to stop it. */
@Composable
private fun BackgroundPill(book: BackgroundBook) {
    val c = LocalPalette.current
    Row(
        Modifier.clip(CircleShape).background(c.panel).border(1.dp, c.line, CircleShape).padding(start = 12.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Label(Strings.backgroundBook(book.name.take(28), book.preparer.done, book.preparer.total), color = c.muted, size = 12.sp, maxLines = 1)
        Box(Modifier.size(20.dp).clip(CircleShape).clickable { book.preparer.stop() }.pointerHoverIcon(PointerIcon.Hand), contentAlignment = Alignment.Center) {
            Label("×", color = c.muted, size = 14.sp)
        }
    }
}

/** A small round button with a single glyph (page arrows). */
@Composable
private fun RoundButton(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    val c = LocalPalette.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(
        Modifier.size(28.dp).clip(CircleShape).background(if (hovered && enabled) c.panel else c.paper).border(1.dp, c.line, CircleShape)
            .hoverable(hover).clickable(enabled = enabled, onClick = onClick).pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default),
        contentAlignment = Alignment.Center,
    ) { Label(glyph, color = if (enabled) c.ink else c.muted.copy(alpha = 0.5f), size = 16.sp, weight = FontWeight.SemiBold) }
}

@Composable
private fun Toolbar(tool: FrameTool, withOrder: Boolean, modifier: Modifier) {
    val c = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.shadow(6.dp, shape).clip(shape).background(c.paper).border(1.dp, c.line, shape).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ToolButton(Tool.Select, "V", tool, ::selectIcon)
        ToolButton(Tool.Rectangle, "R", tool, ::rectIcon)
        ToolButton(Tool.Polygon, "P", tool, ::polygonIcon)
        if (withOrder) {
            Box(Modifier.padding(horizontal = 4.dp, vertical = 3.dp).width(28.dp).height(1.dp).background(c.line))
            ToolButton(Tool.Order, "O", tool, ::orderIcon)
        }
    }
}

@Composable
private fun ToolButton(kind: Tool, key: String, tool: FrameTool, icon: DrawScope.(Color) -> Unit) {
    val c = LocalPalette.current
    val on = tool.tool == kind
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.size(36.dp).clip(shape).background(
            when {
                on -> c.accentSoft
                hovered -> c.panel
                else -> c.paper
            },
        ).hoverable(hover).clickable { tool.select(kind) }.pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) { icon(if (on) c.accentDeep else c.ink) }
        Label(key, Modifier.align(Alignment.BottomEnd).padding(end = 3.dp, bottom = 1.dp), color = c.muted, size = 8.5.sp, weight = FontWeight.SemiBold)
    }
}

/* Icons drawn on a 24-unit grid, like the mockup's SVGs. */

private fun DrawScope.u(v: Float) = v * size.width / 24f

private fun DrawScope.strokeOf(color: Color) = Stroke(u(1.6f), cap = StrokeCap.Round, join = StrokeJoin.Round)

private fun DrawScope.poly(vararg xy: Float, closed: Boolean = true) = Path().apply {
    for (k in xy.indices step 2) if (k == 0) moveTo(u(xy[0]), u(xy[1])) else lineTo(u(xy[k]), u(xy[k + 1]))
    if (closed) close()
}

private fun selectIcon(d: DrawScope, color: Color) = with(d) { drawPath(poly(5f, 3f, 18f, 11f, 12f, 12.5f, 9f, 19f), color, style = strokeOf(color)) }

private fun rectIcon(d: DrawScope, color: Color) = with(d) {
    drawRoundRect(color, Offset(u(4f), u(5f)), Size(u(16f), u(14f)), androidx.compose.ui.geometry.CornerRadius(u(1f)), style = strokeOf(color))
}

private fun polygonIcon(d: DrawScope, color: Color) = with(d) {
    drawPath(poly(5f, 6f, 16f, 4f, 20f, 13f, 14f, 20f, 5f, 17f), color, style = strokeOf(color))
    for ((x, y) in listOf(5f to 6f, 16f to 4f, 20f to 13f)) drawCircle(color, u(1.4f), Offset(u(x), u(y)))
}

private fun orderIcon(d: DrawScope, color: Color) = with(d) {
    drawCircle(color, u(3f), Offset(u(6f), u(6f)), style = strokeOf(color))
    drawCircle(color, u(3f), Offset(u(18f), u(18f)), style = strokeOf(color))
    val p = Path().apply {
        moveTo(u(9f), u(6f)); lineTo(u(15f), u(6f))
        quadraticTo(u(18f), u(6f), u(18f), u(9f)); quadraticTo(u(18f), u(12f), u(15f), u(12f))
        lineTo(u(9f), u(12f)); quadraticTo(u(6f), u(12f), u(6f), u(15f)); quadraticTo(u(6f), u(18f), u(9f), u(18f))
        lineTo(u(15f), u(18f))
    }
    drawPath(p, color, style = strokeOf(color))
}

@Composable
private fun ZoomPill(view: CanvasView, enhancing: Boolean, onEnhance: () -> Unit, onCompare: (Boolean) -> Unit, modifier: Modifier) {
    val c = LocalPalette.current
    Row(
        modifier.shadow(6.dp, CircleShape).clip(CircleShape).background(c.paper).border(1.dp, c.line, CircleShape).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZoomButton("−") { view.zoomBy(1 / 1.25f) }
        Label("${(view.shownScale * 100).roundToInt()} %", Modifier.width(52.dp).padding(horizontal = 2.dp), color = c.muted, maxLines = 1)
        ZoomButton("+") { view.zoomBy(1.25f) }
        ZoomButton(Strings.fit) { view.fit() }
        ZoomButton((if (enhancing) "✦ " else "✧ ") + Strings.enhanceShort, onEnhance)
        if (enhancing) {
            val c = LocalPalette.current
            Box(
                Modifier.clip(CircleShape).background(c.accentSoft).holdToShow(onCompare).pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) { Label("◐ " + Strings.compare, color = c.accentDeep, maxLines = 1) }
        }
    }
}

@Composable
private fun ZoomButton(text: String, onClick: () -> Unit) {
    val c = LocalPalette.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(
        Modifier.clip(CircleShape).background(if (hovered) c.panel else c.paper).hoverable(hover).clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 10.dp, vertical = 4.dp),
    ) { Label(text, maxLines = 1) }
}

@Composable
private fun OrderBanner(tool: FrameTool, session: Session, modifier: Modifier) {
    val c = LocalPalette.current
    Row(
        modifier.shadow(8.dp, CircleShape).clip(CircleShape).background(c.accentDeep).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Label(Strings.orderProgress(tool.order.size, session.page.frames.size), color = c.paper, maxLines = 1)
        BannerButton(Strings.validate) { tool.confirm() }
        BannerButton(Strings.cancel) { tool.cancel() }
    }
}

@Composable
private fun BannerButton(text: String, onClick: () -> Unit) {
    val c = LocalPalette.current
    Box(Modifier.clip(CircleShape).background(c.paper.copy(alpha = 0.18f)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Label(text, color = c.paper, maxLines = 1)
    }
}

@Composable
private fun Hints(tool: FrameTool) = HintBar(Strings.hintMode(tool.tool), Strings.hints(tool.tool), Strings.hintRead)
