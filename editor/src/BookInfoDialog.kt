package tessera.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tessera.acbf.NewBook
import tessera.acbf.Person

/** Today, as YYYY-MM-DD, for the document's creation date. */
expect fun todayIso(): String

/** Choices the editor remembers between comics; the host loads and saves them. */
object EditorPrefs {
    /** The name of whoever makes ACBF documents with Tessera, for document-info. */
    var creator by mutableStateOf("")
    var onChange: (() -> Unit)? = null
}


/**
 * Asked when a comic without ACBF document is opened: who made it and what it is called, so that
 * the document Tessera writes says so. [onDone] gets the information, or null for « Later ».
 */
@Composable
fun BookInfoDialog(suggestedTitle: String, onDone: (NewBook?) -> Unit) {
    val c = LocalPalette.current
    var title by remember { mutableStateOf(suggestedTitle) }
    var authors by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("other") }
    var language by remember { mutableStateOf(Strings.language.code) }
    var summary by remember { mutableStateOf("") }
    var creator by remember { mutableStateOf(EditorPrefs.creator) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }

    fun confirm() {
        if (creator.trim() != EditorPrefs.creator) {
            EditorPrefs.creator = creator.trim(); EditorPrefs.onChange?.invoke()
        }
        onDone(
            NewBook(
                title = title.trim().ifEmpty { suggestedTitle },
                authors = authors.split(',').map { Person.fromName(it) }.filter { !it.isEmpty },
                genre = genre,
                annotation = summary.trim(),
                language = language,
                documentAuthor = creator.takeIf { it.isNotBlank() }?.let { Person.fromName(it) },
                creationDate = todayIso(),
            ),
        )
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (c.dark) 0.55f else 0.28f))
            .clickable(remember { MutableInteractionSource() }, null) { }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.Escape -> { onDone(null); true }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(14.dp)
        Column(
            Modifier.width(520.dp).heightIn(max = 680.dp).shadow(18.dp, shape).clip(shape).background(c.paper).border(1.dp, c.line, shape)
                .verticalScroll(rememberScrollState()).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Label(Strings.bookInfoTitle, size = 18.sp, weight = FontWeight.SemiBold)
            Label(Strings.bookInfoIntro, color = c.muted)
            FormField(Strings.fieldTitle) { FormInput(title, { title = it }, Modifier.focusRequester(first), onEnter = ::confirm) }
            FormField(Strings.fieldAuthors, Strings.fieldAuthorsHint) { FormInput(authors, { authors = it }, onEnter = ::confirm) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) {
                    FormField(Strings.fieldGenre) { FormChoice(Strings.genre(genre), NewBook.GENRES.map { it to Strings.genre(it) }.sortedBy { if (it.first == "other") "~" else it.second.lowercase() }) { genre = it } }
                }
                Box(Modifier.weight(1f)) {
                    FormField(Strings.fieldLanguage) { FormChoice(BookLanguages.first { it.first == language }.second, BookLanguages) { language = it } }
                }
            }
            FormField(Strings.fieldSummary, Strings.optional) { FormInput(summary, { summary = it }, minLines = 3) }
            FormField(Strings.fieldCreator, Strings.fieldCreatorHint) { FormInput(creator, { creator = it }, onEnter = ::confirm) }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(Strings.bookInfoLaterNote, Modifier.weight(1f), color = c.muted, size = 12.sp)
                Pill(Strings.later, { onDone(null) })
                Pill(Strings.validate, ::confirm, primary = true)
            }
        }
    }
}

