package tessera.editor

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Aster's palette: warm paper, ink, violet accent. */
@Immutable
data class Palette(
    val paper: Color,
    val panel: Color,
    val well: Color,
    val line: Color,
    val ink: Color,
    val muted: Color,
    val accent: Color,
    val accentDeep: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val danger: Color,
    val ok: Color,
    val shadow: Color,
    val dark: Boolean,
)

val LightPalette = Palette(
    paper = Color(0xFFFAF9F6), panel = Color(0xFFF3EFF7), well = Color(0xFFE3DFEA), line = Color(0xFFDAD4E3),
    ink = Color(0xFF343447), muted = Color(0xFF81758B), accent = Color(0xFF8064AC), accentDeep = Color(0xFF553E7A),
    accentSoft = Color(0xFFE9E3F1), onAccent = Color.White, danger = Color(0xFFB13F43), ok = Color(0xFF287849),
    shadow = Color(0x24343447), dark = false,
)

val DarkPalette = Palette(
    paper = Color(0xFF1E1C25), panel = Color(0xFF25222E), well = Color(0xFF16141B), line = Color(0xFF3A3546),
    ink = Color(0xFFECE8F2), muted = Color(0xFFA39BB0), accent = Color(0xFFA58BD0), accentDeep = Color(0xFFC7B5E6),
    accentSoft = Color(0xFF332C42), onAccent = Color(0xFF16141B), danger = Color(0xFFE07A7D), ok = Color(0xFF6CC38F),
    shadow = Color(0x73000000), dark = true,
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

@Composable
fun TesseraTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPalette provides if (dark) DarkPalette else LightPalette, content = content)
}

/** "#rrggbb" to a colour; null when unreadable. */
fun parseColor(value: String?): Color? {
    val hex = value?.trim()?.removePrefix("#") ?: return null
    if (hex.length != 6) return null
    return hex.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
}
