package tessera.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tessera.acbf.AcbfTextArea
import tessera.acbf.Metadata
import tessera.acbf.Section
import tessera.acbf.TEXT_AREA_TYPES
import tessera.acbf.addTextArea
import tessera.acbf.textAreas
import tessera.acbf.textLanguages
import tessera.acbf.textLayer

/** Which language the Texts tab shows, and whether areas show their text or only outlines. */
class TextsView(lang: String?) {
    var lang by mutableStateOf(lang)
    var preview by mutableStateOf(true)
}

/**
 * The Texts tab's inspector: the language, the page's text areas with their text (typed in
 * place, one undo step per area), the selected area's options, and the layer as written.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TextInspector(
    session: Session, tool: FrameTool, view: TextsView, modifier: Modifier = Modifier, onBookInfo: () -> Unit = {},
    /** The colour behind the text drawn in a shape, from the page image; null while it is not loaded. */
    sampleGround: ((tessera.acbf.Polygon) -> String?)? = null,
) {
    val c = LocalPalette.current
    @Suppress("UNUSED_VARIABLE") val revision = session.revision
    val doc = session.document
    val langs = doc.textLanguages
    val lang = view.lang
    Column(modifier.background(c.paper).verticalScroll(rememberScrollState())) {
        InspectorSection {
            SectionTitle(Strings.textLanguage)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                langs.forEach { l -> Chip(languageName(l), selected = l == lang, onClick = { view.lang = l; tool.pageChanged() }) }
                AddMenu(Strings.addLanguage, BookLanguages.filter { it.first.isNotEmpty() && it.first !in langs }) { code ->
                    session.editMeta(Section.Book, "language+$code") { it.declareLanguage(code) }
                    view.lang = code; tool.pageChanged()
                }
            }
            if (lang == null) Label(Strings.noLanguageYet, color = c.muted, size = 12.sp)
            val declared = doc.languages.firstOrNull { it.lang == lang }
            if (declared != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Choice(Strings.shownOverImage, declared.show, Modifier.weight(1f)) { session.editMeta(Section.Book, "show:$lang") { it.setLanguageShown(lang!!, true) } }
                    Choice(Strings.drawnInImage, !declared.show, Modifier.weight(1f)) { session.editMeta(Section.Book, "show:$lang") { it.setLanguageShown(lang!!, false) } }
                }
                Label(Strings.shownNote, color = c.muted, size = 12.sp)
            }
        }
        if (lang != null) {
            Divider()
            AreasSection(session, tool, view, lang, sampleGround)
            Divider()
            LayerFileSection(session, lang)
        }
        Divider()
        BookSection(session, onBookInfo)
    }
}

@Composable
private fun AreasSection(session: Session, tool: FrameTool, view: TextsView, lang: String, sampleGround: ((tessera.acbf.Polygon) -> String?)?) {
    // Read the revision: Compose skips a section whose arguments are the same objects, even when the document changed.
    @Suppress("UNUSED_VARIABLE") val revision = session.revision
    val c = LocalPalette.current
    val page = session.page
    val areas = page.textAreas(lang)
    // Another language with areas on this page: shown beside each text, and offered to copy.
    val reference = page.let { p -> session.document.textLanguages.firstOrNull { it != lang && p.textAreas(it).isNotEmpty() } }
    val refAreas = reference?.let { page.textAreas(it) }.orEmpty()
    InspectorSection {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(Strings.textAreas)
            CountBadge(areas.size)
            Spacer(Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Choice(Strings.preview, view.preview, Modifier.weight(1f)) { view.preview = true }
            Choice(Strings.shapesOnly, !view.preview, Modifier.weight(1f)) { view.preview = false }
        }
        if (areas.isEmpty() && reference != null) {
            TextLink(Strings.copyAreasFrom(languageName(reference))) {
                session.editTexts { p ->
                    for (from in p.textAreas(reference)) {
                        val poly = from.polygon ?: continue
                        p.addTextArea(lang, poly).also { a ->
                            a.type = from.type; a.inverted = from.inverted; a.transparent = from.transparent
                            a.rotation = from.rotation; a.bgcolor = from.bgcolor
                        }
                    }
                    // The layer's ground, if the reference has one.
                    p.textLayer(reference)?.bgcolor?.let { bg -> p.textLayer(lang)?.element?.set("bgcolor", bg) }
                }
                tool.message = Strings.copiedAreas(refAreas.size, languageName(reference))
            }
        }
        if (areas.isNotEmpty() && sampleGround != null) {
            TextLink(Strings.groundFromImageAll) {
                session.editTexts { p -> for (a in p.textAreas(lang)) a.polygon?.let(sampleGround)?.let { a.bgcolor = it; a.transparent = false } }
                tool.message = Strings.groundTaken
            }
        }
        if (areas.isEmpty()) {
            Box(Modifier.fillMaxWidth().border(1.dp, c.line, RoundedCornerShape(8.dp)).padding(12.dp), contentAlignment = Alignment.Center) {
                Label(Strings.noTextAreas, color = c.muted, size = 12.sp)
            }
        }
        // A newly drawn area's field takes the keyboard, so that one can type at once.
        val focusers = remember(session.pageIndex, lang, areas.size) { List(areas.size) { FocusRequester() } }
        LaunchedEffect(tool.created) {
            if (tool.created > 0) focusers.getOrNull(tool.selected)?.let { runCatching { it.requestFocus() } }
        }
        areas.forEachIndexed { i, area ->
            AreaRow(session, tool, area, i, lang, refAreas.getOrNull(i)?.text, focusers[i], sampleGround)
        }
    }
}

@Composable
private fun AreaRow(session: Session, tool: FrameTool, area: AcbfTextArea, index: Int, lang: String, reference: String?, focus: FocusRequester, sampleGround: ((tessera.acbf.Polygon) -> String?)?) {
    // Read the revision: Compose skips a section whose arguments are the same objects, even when the document changed.
    @Suppress("UNUSED_VARIABLE") val revision = session.revision
    val c = LocalPalette.current
    val selected = index == tool.selected
    val shape = RoundedCornerShape(8.dp)
    val key = "text:${session.pageIndex}:$lang:$index"
    Column(
        Modifier.fillMaxWidth().clip(shape).background(if (selected) c.accentSoft else c.paper)
            .border(1.dp, if (selected) c.accent else c.line, shape)
            .clickable { tool.select(Tool.Select); tool.selected = index }.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                Label("${index + 1}", color = c.onAccent, size = 10.5.sp, weight = FontWeight.Bold)
            }
            Label(Strings.areaKind(area.type), color = c.muted, size = 12.sp, maxLines = 1)
            Spacer(Modifier.weight(1f))
            RemoveButton { tool.selected = index; tool.delete() }
        }
        if (reference != null && reference.isNotBlank()) Label(reference, color = c.muted, size = 12.sp)
        LiveInput(key, area.text, minLines = 2, placeholder = Strings.typeText, focus = focus) { v ->
            session.editTexts(key) { p -> p.textAreas(lang).getOrNull(index)?.setText(v) }
        }
        if (selected) AreaOptions(session, area, index, lang, sampleGround)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AreaOptions(session: Session, area: AcbfTextArea, index: Int, lang: String, sampleGround: ((tessera.acbf.Polygon) -> String?)?) {
    // Read the revision: Compose skips a section whose arguments are the same objects, even when the document changed.
    @Suppress("UNUSED_VARIABLE") val revision = session.revision
    fun change(key: String? = null, f: (AcbfTextArea) -> Unit) = session.editTexts(key) { p -> p.textAreas(lang).getOrNull(index)?.let(f) }
    FormField(Strings.areaType) {
        FormChoice(Strings.areaKind(area.type), TEXT_AREA_TYPES.map { it to Strings.areaKind(it) }) { t ->
            change { it.type = t }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip(Strings.areaInverted, selected = area.inverted, onClick = { change { it.inverted = !area.inverted } })
        Chip(Strings.areaTransparent, selected = area.transparent, onClick = { change { it.transparent = !area.transparent } })
        val poly = area.polygon
        if (sampleGround != null && poly != null) {
            Chip(Strings.groundFromImage, selected = false, onClick = { sampleGround(poly)?.let { hex -> change { it.bgcolor = hex; it.transparent = false } } })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FormField(Strings.areaRotation, modifier = Modifier.weight(1f)) {
            val k = "rotation:${session.pageIndex}:$lang:$index"
            LiveInput(k, area.rotation.takeIf { it != 0 }?.toString().orEmpty(), placeholder = "0°", valid = { it.isBlank() || it.trim().toIntOrNull() != null }) { v ->
                change(k) { it.rotation = v.trim().toIntOrNull() ?: 0 }
            }
        }
        FormField(Strings.areaGround, modifier = Modifier.weight(1.4f)) {
            val k = "ground:${session.pageIndex}:$lang:$index"
            LiveInput(k, area.bgcolor.orEmpty(), placeholder = Strings.groundHint, valid = { it.isBlank() || parseColor(it) != null }) { v ->
                change(k) { it.bgcolor = v.trim().ifBlank { null } }
            }
        }
    }
}

/** The page's layer in this language, as written in the file. */
@Composable
private fun LayerFileSection(session: Session, lang: String) {
    // Read the revision: Compose skips a section whose arguments are the same objects, even when the document changed.
    @Suppress("UNUSED_VARIABLE") val revision = session.revision
    val c = LocalPalette.current
    InspectorSection {
        SectionTitle(Strings.inFile)
        val text = session.page.textLayer(lang)?.element?.toString() ?: "—"
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.panel).border(1.dp, c.line, RoundedCornerShape(8.dp))
                .heightIn(max = 180.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            BasicText(text, style = TextStyle(color = c.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 17.sp), softWrap = false)
        }
    }
}

/** What the canvas needs to draw the areas of [lang] on the current page. */
fun textOverlay(session: Session, lang: String?, preview: Boolean): TextOverlay =
    TextOverlay(areaLooks(session.page, lang).map { it.second }, session.page.frames.map { it.polygon }, preview)

/**
 * The text areas of [lang] on [page] with how a reader draws them: the area's ground, else the
 * layer's, else white (black when inverted); black text, white when inverted. Areas without
 * readable points have a null polygon.
 */
fun areaLooks(page: tessera.acbf.AcbfPage, lang: String?): List<Pair<tessera.acbf.Polygon?, AreaLook>> {
    val layer = lang?.let { page.textLayer(it) } ?: return emptyList()
    return layer.areas.map { a ->
        val ground = if (a.transparent) null else parseColor(a.bgcolor) ?: parseColor(layer.bgcolor) ?: if (a.inverted) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White
        a.polygon to AreaLook(a.text, ground, if (a.inverted) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Black, a.rotation, layer.lang)
    }
}
