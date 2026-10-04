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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

/* Form controls shared by the book information dialog and the Book info tab. */

/** The languages offered for a book's texts. */
internal val BookLanguages = listOf(
    "" to "—", "en" to "English", "fr" to "Français", "de" to "Deutsch", "es" to "Español", "it" to "Italiano",
    "nl" to "Nederlands", "pt" to "Português", "sk" to "Slovenčina", "cs" to "Čeština", "pl" to "Polski", "ru" to "Русский",
    "ja" to "日本語", "zh" to "中文", "ko" to "한국어",
)

/** A language's name for display: from [BookLanguages], else its code. */
internal fun languageName(code: String?): String =
    if (code == null) Strings.noLanguage else BookLanguages.firstOrNull { it.first == code }?.second ?: code

@Composable
internal fun FormField(label: String, note: String? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = LocalPalette.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Label(label, weight = FontWeight.Medium, size = 12.5.sp)
            if (note != null) Label(note, color = c.muted, size = 12.sp)
        }
        content()
    }
}

@Composable
internal fun FormInput(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, minLines: Int = 1, onEnter: (() -> Unit)? = null, placeholder: String = "") {
    val c = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)
    BasicTextField(
        value, onChange,
        modifier.fillMaxWidth().clip(shape).background(c.panel).border(if (focused) 1.5.dp else 1.dp, if (focused) c.accent else c.line, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .onPreviewKeyEvent { e ->
                if (onEnter != null && minLines == 1 && e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) {
                    onEnter(); true
                } else false
            },
        textStyle = TextStyle(color = c.ink, fontSize = 13.sp, lineHeight = 18.sp),
        singleLine = minLines == 1,
        minLines = minLines,
        cursorBrush = SolidColor(c.accent),
        interactionSource = interaction,
        decorationBox = { inner ->
            Box {
                if (value.isEmpty() && placeholder.isNotEmpty()) Label(placeholder, color = c.muted.copy(alpha = 0.75f), maxLines = 1)
                inner()
            }
        },
    )
}

@Composable
internal fun FormChoice(shown: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    val c = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box {
        Row(
            Modifier.fillMaxWidth().clip(shape).background(c.panel).border(1.dp, c.line, shape).clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label(shown, Modifier.weight(1f), maxLines = 1)
            Spacer(Modifier.width(6.dp))
            Label("▾", color = c.muted, size = 11.sp)
        }
        DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            options.forEach { (key, label) ->
                DropdownMenuItem(onClick = { open = false; onPick(key) }) { Label(label) }
            }
        }
    }
}
