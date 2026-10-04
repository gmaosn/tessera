package tessera.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tessera.editor.enhance.EnhanceMode
import tessera.editor.enhance.Enhancement
import kotlin.math.roundToInt

/** Display enhancement, remembered separately for the editor and for reading. */
object EnhancePrefs {
    var editor by mutableStateOf(Enhancement())
    var reader by mutableStateOf(Enhancement())
    var onChange: (() -> Unit)? = null

    /** "Restore,0.40,1.00" and back, for the host's preferences. */
    fun encode(e: Enhancement) = "${e.mode.name},${e.sharpness},${e.strength}"
    fun decode(s: String?): Enhancement? = s?.split(',')?.takeIf { it.size == 3 }?.let { (m, sh, st) ->
        runCatching { Enhancement(EnhanceMode.valueOf(m), sh.toFloat().coerceIn(0f, 1f), st.toFloat().coerceIn(0f, 1f)) }.getOrNull()
    }
}

/**
 * The settings card: off, sharpen or restore, with sliders for sharpness and, when restoring,
 * strength. [busy] shows that the page is being computed.
 */
@Composable
fun EnhancePanel(settings: Enhancement, busy: Boolean, onChange: (Enhancement) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.width(320.dp).shadow(10.dp, shape).clip(shape).background(c.paper).border(1.dp, c.line, shape)
            .clickable(remember { MutableInteractionSource() }, null) { }.padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(Strings.enhanceTitle, Modifier.weight(1f), weight = FontWeight.SemiBold)
            if (busy) Label(Strings.enhanceBusy, color = c.accent, size = 12.sp)
        }
        Segmented(
            listOf(Strings.enhanceOff, Strings.enhanceSharpen, Strings.enhanceRestore),
            settings.mode.ordinal, enabled = { true },
        ) { onChange(settings.copy(mode = EnhanceMode.entries[it])) }
        if (settings.mode != EnhanceMode.Off) {
            SliderRow(Strings.enhanceSharpness, settings.sharpness) { onChange(settings.copy(sharpness = it)) }
        }
        if (settings.mode == EnhanceMode.Restore) {
            SliderRow(Strings.enhanceStrength, settings.strength) { onChange(settings.copy(strength = it)) }
        }
        Label(
            when (settings.mode) {
                EnhanceMode.Off -> Strings.enhanceOffNote
                EnhanceMode.Sharpen -> Strings.enhanceSharpenNote
                EnhanceMode.Restore -> Strings.enhanceRestoreNote
            },
            color = c.muted, size = 12.sp,
        )
        if (settings.active) Label("◐ " + Strings.compareHint, color = c.muted, size = 12.sp)
        Label(Strings.enhanceScreenOnly, color = c.muted, size = 12.sp)
    }
}

/** A labelled slider from 0 to 100 %; the value is applied when the thumb is released. */
@Composable
private fun SliderRow(label: String, value: Float, onChange: (Float) -> Unit) {
    val c = LocalPalette.current
    var live by remember(value) { mutableStateOf(value) }
    Column {
        Row(Modifier.fillMaxWidth()) {
            Label(label, Modifier.weight(1f), size = 12.5.sp)
            Label("${(live * 100).roundToInt()} %", color = c.muted, size = 12.5.sp)
        }
        Slider(
            live, { live = (it * 20).roundToInt() / 20f }, onValueChangeFinished = { onChange(live) },
            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.line),
            modifier = Modifier.padding(top = 0.dp),
        )
    }
}
