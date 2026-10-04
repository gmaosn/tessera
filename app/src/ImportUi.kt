package tessera.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tessera.editor.Label
import tessera.editor.LocalPalette
import tessera.editor.Pill
import tessera.editor.Segmented
import tessera.editor.Strings
import java.io.File

/** A dimmed backdrop with a centred card, catching clicks so the window behind stays still. */
@Composable
private fun Modal(content: @Composable () -> Unit) {
    val c = LocalPalette.current
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (c.dark) 0.55f else 0.28f))
            .clickable(remember { MutableInteractionSource() }, null) { },
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(14.dp)
        Column(
            Modifier.width(500.dp).shadow(18.dp, shape).clip(shape).background(c.paper).border(1.dp, c.line, shape).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) { content() }
    }
}

private val RESOLUTIONS = listOf(150, 200, 300)

/** The PDF import options: resolution, format, keeping scanned pages, destination. */
@Composable
fun ImportDialog(
    pdf: File,
    info: PdfInfo,
    options: PdfImportOptions,
    target: File,
    onOptions: (PdfImportOptions) -> Unit,
    onChangeTarget: () -> Unit,
    onCancel: () -> Unit,
    onImport: () -> Unit,
) = Modal {
    val c = LocalPalette.current
    Label(Strings.importTitle(pdf.name), size = 18.sp, weight = FontWeight.SemiBold, maxLines = 2)
    Label(Strings.importPages(info.pages), color = c.muted)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(Strings.importResolution, weight = FontWeight.Medium, size = 12.5.sp)
        Segmented(RESOLUTIONS.map { "$it dpi" }, RESOLUTIONS.indexOf(options.dpi), enabled = { true }) { onOptions(options.copy(dpi = RESOLUTIONS[it])) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(Strings.importFormat, weight = FontWeight.Medium, size = 12.5.sp)
        Segmented(listOf("JPEG", "PNG"), options.format.ordinal, enabled = { true }) { onOptions(options.copy(format = PageFormat.entries[it])) }
        Label(if (options.format == PageFormat.Jpeg) Strings.importJpegNote else Strings.importPngNote, color = c.muted, size = 12.sp)
    }
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable { onOptions(options.copy(keepOriginals = !options.keepOriginals)) }.padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.padding(top = 2.dp).size(16.dp).clip(RoundedCornerShape(4.dp))
                .background(if (options.keepOriginals) c.accent else c.paper).border(1.dp, if (options.keepOriginals) c.accent else c.line, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) { if (options.keepOriginals) Label("✓", color = c.onAccent, size = 11.sp, weight = FontWeight.Bold) }
        Column {
            Label(Strings.importKeep)
            Label(Strings.importKeepNote, color = c.muted, size = 12.sp)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(Strings.importTarget, weight = FontWeight.Medium, size = 12.5.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(c.panel).border(1.dp, c.line, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 8.dp)) {
                Label(target.name, maxLines = 1)
            }
            Pill(Strings.change, onChangeTarget)
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
        Pill(Strings.cancel, onCancel)
        Pill(Strings.importStart, onImport, primary = true)
    }
}

/** Progress while pages are converted, with a way out. */
@Composable
fun ImportProgress(pdf: File, done: Int, total: Int, onCancel: () -> Unit) = Modal {
    val c = LocalPalette.current
    Label(Strings.importing(pdf.name), size = 16.sp, weight = FontWeight.SemiBold, maxLines = 2)
    Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(c.panel)) {
        Box(Modifier.fillMaxWidth(if (total > 0) done.toFloat() / total else 0f).fillMaxHeight().clip(CircleShape).background(c.accent))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Label(Strings.importProgress(done, total), Modifier.weight(1f), color = c.muted)
        Pill(Strings.cancel, onCancel)
    }
}
