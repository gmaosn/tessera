package tessera.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tessera.acbf.Author
import tessera.acbf.ContentRating
import tessera.acbf.DatabaseRef
import tessera.acbf.Genre
import tessera.acbf.Metadata
import tessera.acbf.NewBook
import tessera.acbf.Section
import tessera.acbf.Sequence

private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")

/** Distinct keys for one-off changes (removing, adding), so they never merge into one undo step. */
private object Once {
    private var n = 0
    fun key(what: String) = "$what#${n++}"
}

/**
 * The Book info tab: every metadata field of the document, edited in place. Changes apply at
 * once, each field's typing is one undo step, and untouched fields stay byte for byte the same.
 */
@Composable
fun InfoScreen(session: Session, modifier: Modifier = Modifier) {
    val c = LocalPalette.current
    @Suppress("UNUSED_VARIABLE") val revision = session.revision
    val m = Metadata(session.document)
    Box(modifier.background(c.well)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(Strings.infoBook) { BookCard(session, m) }
            Card(Strings.infoPublishing) { PublishCard(session, m) }
            Card(Strings.infoDocument) { DocumentCard(session, m) }
            Card(Strings.infoReferences) { ReferencesCard(session, m) }
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    val c = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.widthIn(max = 780.dp).fillMaxWidth().clip(shape).background(c.paper).border(1.dp, c.line, shape).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Label(title, size = 15.sp, weight = FontWeight.SemiBold)
        content()
    }
}

/** A text field bound to the document: shows its value, writes every change back. */
@Composable
internal fun LiveInput(
    key: String, value: String, modifier: Modifier = Modifier, minLines: Int = 1, placeholder: String = "", valid: (String) -> Boolean = { true },
    focus: androidx.compose.ui.focus.FocusRequester? = null, onChange: (String) -> Unit,
) {
    var local by remember(key) { mutableStateOf(value) }
    // Undo or another field changed the document: show it, unless this field is mid-typing it.
    LaunchedEffect(value) { if (value.trim() != local.trim()) local = value }
    Column(modifier) {
        FormInput(
            local, { local = it; if (valid(it)) onChange(it) }, minLines = minLines, placeholder = placeholder,
            modifier = if (focus != null) Modifier.focusRequester(focus) else Modifier,
        )
        if (!valid(local)) Label(Strings.dateFormat, color = LocalPalette.current.danger, size = 11.5.sp)
    }
}

// ----- Book -----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookCard(session: Session, m: Metadata) {
    val c = LocalPalette.current
    val B = Section.Book
    // The language whose title, summary and keywords are shown; more can be added.
    var added by remember { mutableStateOf(listOf<String?>()) }
    val langs = (m.textLanguages() + added).distinct()
    var lang by remember { mutableStateOf(langs.first()) }
    if (lang !in langs) lang = langs.first()

    FormField(Strings.textsLanguage) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            langs.forEach { l -> Chip(languageName(l), selected = l == lang, onClick = { lang = l }) }
            AddMenu(Strings.addLanguage, BookLanguages.filter { it.first.isNotEmpty() && it.first !in langs }) { code -> added = added + code; lang = code }
        }
    }
    FormField(Strings.fieldTitle) {
        LiveInput("title:$lang", m.text(B, "book-title", lang)) { v -> session.editMeta(B, "title:$lang") { it.setText(B, "book-title", v, lang) } }
    }
    FormField(Strings.fieldAuthors) { AuthorsEditor(session, m, B, withRole = true) }
    FormField(Strings.fieldGenres) {
        val genres = m.genres()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            genres.forEachIndexed { i, g ->
                Chip(Strings.genre(g.name) + (g.match?.let { " · $it %" } ?: ""), selected = true, onRemove = {
                    session.editMeta(B, Once.key("genre-")) { it.setGenres(genres.filterIndexed { k, _ -> k != i }) }
                })
            }
            AddMenu(Strings.addGenre, NewBook.GENRES.filter { g -> genres.none { it.name == g } }.map { it to Strings.genre(it) }.sortedBy { it.second }) { g ->
                session.editMeta(B, Once.key("genre+")) { it.setGenres(genres + Genre(g)) }
            }
        }
    }
    FormField(Strings.fieldSummary) {
        LiveInput("annotation:$lang", m.paragraphs(B, "annotation", lang).joinToString("\n"), minLines = 4) { v ->
            session.editMeta(B, "annotation:$lang") { it.setParagraphs(B, "annotation", v.split('\n'), lang) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FormField(Strings.fieldKeywords, modifier = Modifier.weight(1f)) {
            LiveInput("keywords:$lang", m.text(B, "keywords", lang)) { v -> session.editMeta(B, "keywords:$lang") { it.setText(B, "keywords", v, lang) } }
        }
        FormField(Strings.fieldCharacters, modifier = Modifier.weight(1f)) {
            LiveInput("characters", m.characters().joinToString(", ")) { v -> session.editMeta(B, "characters") { it.setCharacters(v.split(',')) } }
        }
    }
    FormField(Strings.fieldSeries) {
        val series = m.sequences()
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            series.forEachIndexed { i, q ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    fun set(n: Sequence) = series.toMutableList().also { it[i] = n }
                    LiveInput("seq$i.title", q.title, Modifier.weight(3f), placeholder = Strings.phSeriesTitle) { v -> session.editMeta(B, "seq$i.title") { it.setSequences(set(q.copy(title = v.trim()))) } }
                    LiveInput("seq$i.volume", q.volume, Modifier.weight(1f), placeholder = Strings.phVolume) { v -> session.editMeta(B, "seq$i.volume") { it.setSequences(set(q.copy(volume = v.trim()))) } }
                    LiveInput("seq$i.number", q.number, Modifier.weight(1f), placeholder = Strings.phNumber) { v -> session.editMeta(B, "seq$i.number") { it.setSequences(set(q.copy(number = v.trim()))) } }
                    RemoveButton { session.editMeta(B, Once.key("seq-")) { it.setSequences(series.filterIndexed { k, _ -> k != i }) } }
                }
            }
            TextLink(Strings.addSeries) { session.editMeta(B, Once.key("seq+")) { it.setSequences(series + Sequence("")) } }
        }
    }
    // ACBF 1.2 proposal: shown only where the document already speaks 1.2 or has the element.
    val direction = m.section(B)?.element("reading-direction")
    if (direction != null || session.document.version == "1.2") {
        FormField(Strings.readingDirection) {
            val rtl = m.text(B, "reading-direction").uppercase() == "RTL"
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(Strings.leftToRight, selected = !rtl, onClick = { session.editMeta(B, Once.key("dir")) { it.setText(B, "reading-direction", "LTR") } })
                Chip(Strings.rightToLeft, selected = rtl, onClick = { session.editMeta(B, Once.key("dir")) { it.setText(B, "reading-direction", "RTL") } })
            }
        }
    }
}

@Composable
private fun AuthorsEditor(session: Session, m: Metadata, s: Section, withRole: Boolean) {
    val authors = m.authors(s).map(m::readAuthor)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        authors.forEachIndexed { i, a ->
            fun write(field: String, n: Author) = session.editMeta(s, "$s.author$i.$field") { it.writeAuthor(it.authors(s)[i], n) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                LiveInput("$s.a$i.first", a.firstName, Modifier.weight(1f), placeholder = Strings.phFirstName) { write("first", a.copy(firstName = it)) }
                LiveInput("$s.a$i.last", a.lastName, Modifier.weight(1f), placeholder = Strings.phLastName) { write("last", a.copy(lastName = it)) }
                LiveInput("$s.a$i.nick", a.nickname, Modifier.weight(1f), placeholder = Strings.phNickname) { write("nick", a.copy(nickname = it)) }
                if (withRole) {
                    Box(Modifier.weight(1f)) {
                        FormChoice(a.activity?.let(Strings::activity) ?: "—", listOf("" to "—") + Author.ACTIVITIES.map { it to Strings.activity(it) }) { r ->
                            session.editMeta(s, Once.key("role")) { it.writeAuthor(it.authors(s)[i], a.copy(activity = r.ifBlank { null })) }
                        }
                    }
                }
                RemoveButton { session.editMeta(s, Once.key("author-")) { it.removeAuthor(it.authors(s)[i]) } }
            }
        }
        TextLink(Strings.addAuthor) { session.editMeta(s, Once.key("author+")) { it.addAuthor(s, Author()) } }
    }
}

// ----- Publishing -----

@Composable
private fun PublishCard(session: Session, m: Metadata) {
    val P = Section.Publish
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FormField(Strings.fieldPublisher, modifier = Modifier.weight(1f)) {
            LiveInput("publisher", m.text(P, "publisher")) { v -> session.editMeta(P, "publisher") { it.setText(P, "publisher", v) } }
        }
        FormField(Strings.fieldCity, modifier = Modifier.weight(1f)) {
            LiveInput("city", m.text(P, "city")) { v -> session.editMeta(P, "city") { it.setText(P, "city", v) } }
        }
    }
    DateFields(session, m, P, "publish-date", Strings.fieldPublishDate)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FormField(Strings.fieldIsbn, modifier = Modifier.weight(1f)) {
            LiveInput("isbn", m.text(P, "isbn")) { v -> session.editMeta(P, "isbn") { it.setText(P, "isbn", v) } }
        }
        FormField(Strings.fieldLicense, modifier = Modifier.weight(2f)) {
            LiveInput("license", m.text(P, "license")) { v -> session.editMeta(P, "license") { it.setText(P, "license", v) } }
        }
    }
}

@Composable
private fun DateFields(session: Session, m: Metadata, s: Section, local: String, label: String) {
    val (text, iso) = m.date(s, local)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FormField(label, Strings.dateShown, Modifier.weight(1f)) {
            LiveInput("$local.text", text) { v -> session.editMeta(s, "$local.text") { it.setDate(s, local, v, it.date(s, local).second) } }
        }
        FormField(label, Strings.dateIso, Modifier.weight(1f)) {
            LiveInput("$local.iso", iso, valid = { it.isBlank() || ISO_DATE.matches(it.trim()) }) { v ->
                session.editMeta(s, "$local.iso") { it.setDate(s, local, it.date(s, local).first, v.trim()) }
            }
        }
    }
}

// ----- Document -----

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
@Composable
private fun DocumentCard(session: Session, m: Metadata) {
    val D = Section.Document
    FormField(Strings.fieldDocumentAuthors, Strings.fieldDocumentAuthorsNote) { AuthorsEditor(session, m, D, withRole = false) }
    DateFields(session, m, D, "creation-date", Strings.fieldCreationDate)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
        FormField(Strings.fieldId, modifier = Modifier.weight(3f)) {
            LiveInput("id", m.text(D, "id")) { v -> session.editMeta(D, "id") { it.setText(D, "id", v) } }
        }
        Box(Modifier.padding(bottom = 4.dp)) {
            Pill(Strings.generateId, { session.editMeta(D, Once.key("id")) { it.setText(D, "id", kotlin.uuid.Uuid.random().toString()) } })
        }
        FormField(Strings.fieldVersion, modifier = Modifier.weight(1f)) {
            LiveInput("version", m.text(D, "version")) { v -> session.editMeta(D, "version") { it.setText(D, "version", v) } }
        }
    }
    FormField(Strings.fieldSource, Strings.onePerLine) {
        LiveInput("source", m.paragraphs(D, "source").joinToString("\n"), minLines = 2) { v -> session.editMeta(D, "source") { it.setParagraphs(D, "source", v.split('\n')) } }
    }
    FormField(Strings.fieldHistory, Strings.onePerLine) {
        LiveInput("history", m.paragraphs(D, "history").joinToString("\n"), minLines = 2) { v -> session.editMeta(D, "history") { it.setParagraphs(D, "history", v.split('\n')) } }
    }
}

// ----- References: databases and content ratings -----

@Composable
private fun ReferencesCard(session: Session, m: Metadata) {
    val c = LocalPalette.current
    val B = Section.Book
    FormField(Strings.fieldDatabases, Strings.fieldDatabasesNote) {
        val refs = m.databaseRefs()
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            refs.forEachIndexed { i, r ->
                fun set(n: DatabaseRef) = refs.toMutableList().also { it[i] = n }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    LiveInput("db$i.name", r.dbname, Modifier.weight(1f), placeholder = Strings.phDatabase) { v -> session.editMeta(B, "db$i.name") { it.setDatabaseRefs(set(r.copy(dbname = v.trim()))) } }
                    LiveInput("db$i.type", r.type, Modifier.weight(1f), placeholder = Strings.phRefType) { v -> session.editMeta(B, "db$i.type") { it.setDatabaseRefs(set(r.copy(type = v.trim()))) } }
                    LiveInput("db$i.value", r.value, Modifier.weight(2f), placeholder = Strings.phRefValue) { v -> session.editMeta(B, "db$i.value") { it.setDatabaseRefs(set(r.copy(value = v.trim()))) } }
                    RemoveButton { session.editMeta(B, Once.key("db-")) { it.setDatabaseRefs(refs.filterIndexed { k, _ -> k != i }) } }
                }
            }
            TextLink(Strings.addDatabase) { session.editMeta(B, Once.key("db+")) { it.setDatabaseRefs(refs + DatabaseRef("GCD")) } }
        }
    }
    FormField(Strings.fieldRatings) {
        val ratings = m.contentRatings()
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ratings.forEachIndexed { i, r ->
                fun set(n: ContentRating) = ratings.toMutableList().also { it[i] = n }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    LiveInput("cr$i.type", r.type, Modifier.weight(2f), placeholder = Strings.phRatingSystem) { v -> session.editMeta(B, "cr$i.type") { it.setContentRatings(set(r.copy(type = v.trim()))) } }
                    LiveInput("cr$i.value", r.value, Modifier.weight(1f), placeholder = Strings.phRating) { v -> session.editMeta(B, "cr$i.value") { it.setContentRatings(set(r.copy(value = v.trim()))) } }
                    RemoveButton { session.editMeta(B, Once.key("cr-")) { it.setContentRatings(ratings.filterIndexed { k, _ -> k != i }) } }
                }
            }
            TextLink(Strings.addRating) { session.editMeta(B, Once.key("cr+")) { it.setContentRatings(ratings + ContentRating()) } }
        }
    }
}

// ----- Small controls -----

@Composable
internal fun Chip(text: String, selected: Boolean, onClick: (() -> Unit)? = null, onRemove: (() -> Unit)? = null) {
    val c = LocalPalette.current
    Row(
        Modifier.clip(CircleShape).background(if (selected) c.accentSoft else c.paper).border(1.dp, if (selected) c.accent else c.line, CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .padding(start = 12.dp, end = if (onRemove != null) 6.dp else 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Label(text, color = if (selected) c.accentDeep else c.ink, size = 12.5.sp, maxLines = 1)
        if (onRemove != null) {
            Box(Modifier.size(16.dp).clip(CircleShape).clickable(onClick = onRemove).pointerHoverIcon(PointerIcon.Hand), contentAlignment = Alignment.Center) {
                Label("×", color = c.accentDeep, size = 13.sp)
            }
        }
    }
}

@Composable
internal fun AddMenu(label: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    val c = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.clip(CircleShape).border(1.dp, c.line, CircleShape).clickable(enabled = options.isNotEmpty()) { open = true }
                .pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 12.dp, vertical = 4.dp),
        ) { Label("+ $label", color = c.accent, size = 12.5.sp, maxLines = 1) }
        DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            options.forEach { (key, text) -> DropdownMenuItem(onClick = { open = false; onPick(key) }) { Label(text) } }
        }
    }
}

@Composable
internal fun RemoveButton(onClick: () -> Unit) {
    val c = LocalPalette.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(
        Modifier.size(28.dp).clip(CircleShape).background(if (hovered) c.panel else c.paper).hoverable(hover)
            .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) { Label("×", color = if (hovered) c.danger else c.muted, size = 15.sp) }
}
