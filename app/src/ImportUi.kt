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
import tessera.editor.SliderRow
import tessera.editor.Strings
import tessera.editor.scan.ScanFinish
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

/** The PDF import: what will happen (always lossless) and where the CBZ goes. */
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
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Label("✓", color = c.ok, weight = FontWeight.Bold)
        Label(Strings.importLossless, color = c.ink)
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

/** The scans import: what will happen to each scan, the reading order and where the CBZ goes. */
@Composable
fun ScanImportDialog(
    dir: File,
    scans: Int,
    options: ScanImportOptions,
    target: File,
    onOptions: (ScanImportOptions) -> Unit,
    onChangeTarget: () -> Unit,
    onCancel: () -> Unit,
    onImport: () -> Unit,
) = Modal {
    val c = LocalPalette.current
    Label(Strings.scansTitle(dir.name), size = 18.sp, weight = FontWeight.SemiBold, maxLines = 2)
    Label(Strings.scansCount(scans), color = c.muted)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Label("✓", color = c.ok, weight = FontWeight.Bold)
        Label(Strings.scansWhat, color = c.ink)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(Strings.readingOrder, weight = FontWeight.Medium, size = 12.5.sp)
        Segmented(listOf(Strings.scansRightToLeft, Strings.scansLeftToRight), if (options.rightToLeft) 0 else 1, { true }) {
            onOptions(options.copy(rightToLeft = it == 0))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SliderRow(Strings.scansUnfold, options.finish.unfold.toFloat(), max = 2f) { onOptions(options.copy(finish = options.finish.copy(unfold = it.toDouble()))) }
        SliderRow(Strings.scansSharpen, options.finish.sharpen.toFloat(), max = 2f) { onOptions(options.copy(finish = options.finish.copy(sharpen = it.toDouble()))) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Label(Strings.scansSettingsNote, Modifier.weight(1f), color = c.muted, size = 12.sp)
            Pill(Strings.scansDefaults, { onOptions(options.copy(finish = ScanFinish())) }, enabled = options.finish != ScanFinish())
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
fun ImportProgress(pdf: File, done: Int, total: Int, label: String = Strings.importProgress(done, total), onCancel: () -> Unit) = Modal {
    val c = LocalPalette.current
    Label(Strings.importing(pdf.name), size = 16.sp, weight = FontWeight.SemiBold, maxLines = 2)
    Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(c.panel)) {
        Box(Modifier.fillMaxWidth(if (total > 0) done.toFloat() / total else 0f).fillMaxHeight().clip(CircleShape).background(c.accent))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Label(label, Modifier.weight(1f), color = c.muted)
        Pill(Strings.cancel, onCancel)
    }
}
