package tessera.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun Label(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalPalette.current.ink,
    size: TextUnit = 13.sp,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = Int.MAX_VALUE,
    letterSpacing: TextUnit = TextUnit.Unspecified,
) {
    BasicText(
        text, modifier,
        style = TextStyle(color = color, fontSize = size, fontWeight = weight, letterSpacing = letterSpacing, lineHeight = size * 1.4f),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
}

/** The small uppercase heading of an inspector section. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Label(text.uppercase(), modifier, LocalPalette.current.muted, 11.sp, FontWeight.SemiBold, letterSpacing = 0.8.sp)
}

/** Aster's pill button; [primary] fills it with the accent. */
@Composable
fun Pill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true) {
    val c = LocalPalette.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val bg = when {
        primary && hovered -> c.accentDeep
        primary -> c.accent
        hovered -> c.panel
        else -> c.paper
    }
    Box(
        modifier.clip(CircleShape).background(bg).border(1.dp, if (primary) bg else c.line, CircleShape)
            .hoverable(hover).clickable(enabled = enabled, onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 14.dp, vertical = 5.dp),
    ) {
        Label(text, color = if (primary) c.onAccent else c.ink, weight = if (primary) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
    }
}

/** A row of mutually exclusive choices; disabled ones show what comes later. */
@Composable
fun Segmented(options: List<String>, selected: Int, enabled: (Int) -> Boolean, onSelect: (Int) -> Unit) {
    val c = LocalPalette.current
    Row(
        Modifier.clip(CircleShape).background(c.panel).border(1.dp, c.line, CircleShape).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.then(if (on) Modifier.shadow(1.dp, CircleShape) else Modifier).clip(CircleShape)
                    .background(if (on) c.paper else Color.Transparent)
                    .clickable(enabled = enabled(i)) { onSelect(i) }.padding(horizontal = 14.dp, vertical = 5.dp),
            ) {
                Label(label, color = if (on) c.ink else c.muted.copy(alpha = if (enabled(i)) 1f else 0.55f), maxLines = 1)
            }
        }
    }
}

/** A keyboard key, as in the hint bar. */
@Composable
fun Kbd(key: String) {
    val c = LocalPalette.current
    Box(
        Modifier.clip(RoundedCornerShape(5.dp)).background(c.paper).border(1.dp, c.line, RoundedCornerShape(5.dp))
            .padding(horizontal = 5.dp, vertical = 0.dp),
    ) { Label(key, color = c.ink, size = 11.sp, weight = FontWeight.SemiBold, maxLines = 1) }
}

/** A count in a small rounded badge; a dashed-looking empty badge for zero. */
@Composable
fun CountBadge(n: Int) {
    val c = LocalPalette.current
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.clip(shape).then(if (n == 0) Modifier.border(1.dp, c.muted.copy(alpha = 0.6f), shape) else Modifier.background(c.accentSoft))
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) { Label(n.toString(), color = if (n == 0) c.muted else c.accentDeep, size = 10.5.sp, weight = FontWeight.SemiBold) }
}

/** One item of the hint bar: optional keys, a label, and an optional emphasised effect. */
data class Hint(val keys: List<String> = emptyList(), val text: String = "", val strong: String = "")

@Composable
fun HintBar(mode: String, hints: List<Hint>, trailing: Hint?) {
    val c = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().background(c.panel).padding(horizontal = 16.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(mode, color = c.accentDeep, size = 12.sp, weight = FontWeight.SemiBold, maxLines = 1)
        hints.forEach { HintItem(it) }
        if (trailing != null) {
            Box(Modifier.weight(1f))
            HintItem(trailing)
        }
    }
}

@Composable
private fun HintItem(h: Hint) {
    val c = LocalPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        h.keys.forEach { Kbd(it) }
        if (h.text.isNotEmpty()) Label(h.text, color = c.muted, size = 12.sp, maxLines = 1)
        if (h.strong.isNotEmpty()) Label(h.strong, color = c.ink, size = 12.sp, weight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** A short message at the bottom of the window. */
@Composable
fun Toast(text: String, modifier: Modifier = Modifier) {
    val c = LocalPalette.current
    Box(modifier.shadow(6.dp, CircleShape).clip(CircleShape).background(c.ink).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Label(text, color = c.paper, maxLines = 2)
    }
}

/**
 * Detects a press held on this element: [onHold] gets true on press and false on release (or
 * when the pointer is lost), for "hold to compare".
 */
fun Modifier.holdToShow(onHold: (Boolean) -> Unit): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent()
                when (e.type) {
                    androidx.compose.ui.input.pointer.PointerEventType.Press -> { onHold(true); e.changes.forEach { it.consume() } }
                    androidx.compose.ui.input.pointer.PointerEventType.Release, androidx.compose.ui.input.pointer.PointerEventType.Exit -> onHold(false)
                }
            }
        }
    },
)

/** The badge shown over the page while comparing. */
@Composable
fun ComparingBadge(modifier: Modifier = Modifier) {
    val c = LocalPalette.current
    Box(modifier.shadow(4.dp, CircleShape).clip(CircleShape).background(c.ink).padding(horizontal = 14.dp, vertical = 5.dp)) {
        Label(Strings.withoutEnhancement, color = c.paper, size = 12.sp, maxLines = 1)
    }
}
