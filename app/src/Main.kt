package tessera.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import tessera.acbf.ComicFiles
import tessera.editor.EditorScreen
import tessera.editor.ImageCache
import tessera.editor.Label
import tessera.editor.LocalPalette
import tessera.editor.Pill
import tessera.editor.Session
import tessera.editor.Strings
import tessera.editor.TesseraTheme
import java.awt.FileDialog
import java.awt.datatransfer.DataFlavor
import java.io.File

/** One open comic: the file on disk, the editing session and its images. */
private class Opened(val file: File, val session: Session, val images: ImageCache)

private fun open(file: File): Opened {
    val comic = ComicFiles.open(file)
    return Opened(file, Session(comic, file.name), ImageCache(comic))
}

fun main(args: Array<String>) = application {
    var opened by remember { mutableStateOf(args.firstOrNull()?.let { runCatching { open(File(it)) }.getOrNull() }) }
    var error by remember { mutableStateOf<String?>(null) }
    val state = rememberWindowState(size = DpSize(1440.dp, 920.dp))
    val title = opened?.let { "${it.file.name}${if (it.session.dirty) " •" else ""} — Tessera" } ?: "Tessera"

    Window(onCloseRequest = ::exitApplication, state = state, title = title) {
        fun load(f: File) {
            runCatching { open(f) }.onSuccess { opened = it; error = null }.onFailure { error = "${f.name} : ${it.message}" }
        }
        fun save(o: Opened): String = runCatching {
            val addedAcbf = o.session.comic.generated
            val reopened = ComicFiles.save(o.session.comic, o.file)
            o.images.comic = reopened
            o.session.saved(reopened)
            Strings.saved(addedAcbf)
        }.getOrElse { Strings.saveFailed(it.message) }

        val current = opened
        Menus(onOpen = { pickFile(window)?.let(::load) }, onSave = current?.let { o -> { save(o); Unit } })
        TesseraTheme {
            Box(Modifier.fillMaxSize().fileDrop(::load)) {
                if (current == null) Welcome(error) { pickFile(window)?.let(::load) }
                else EditorScreen(current.session, current.images, onSave = { save(current) })
            }
        }
    }
}

@Composable
private fun FrameWindowScope.Menus(onOpen: () -> Unit, onSave: (() -> Unit)?) {
    MenuBar {
        Menu("Fichier") {
            Item("Ouvrir…", shortcut = KeyShortcut(Key.O, meta = isMac, ctrl = !isMac), onClick = onOpen)
            Item("Enregistrer", enabled = onSave != null, shortcut = KeyShortcut(Key.S, meta = isMac, ctrl = !isMac), onClick = { onSave?.invoke() })
        }
    }
}

private val isMac = System.getProperty("os.name").lowercase().contains("mac")

@Composable
private fun Welcome(error: String?, onOpen: () -> Unit) {
    val c = LocalPalette.current
    Box(Modifier.fillMaxSize().background(c.well), contentAlignment = Alignment.Center) {
        Column(
            Modifier.background(c.paper, RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Label("Tessera", size = 26.sp, weight = FontWeight.SemiBold)
            Label("Ouvrez une bande dessinée CBZ ou ACBF, ou déposez-la ici.", color = c.muted)
            Pill("Ouvrir…", onOpen, primary = true)
            if (error != null) Label(error, color = c.danger, size = 12.sp)
        }
    }
}

private fun pickFile(window: java.awt.Frame): File? {
    val dialog = FileDialog(window, "Ouvrir une bande dessinée", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in setOf("cbz", "zip", "acbf") }
    dialog.isVisible = true
    return dialog.file?.let { File(dialog.directory, it) }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun Modifier.fileDrop(onFile: (File) -> Unit): Modifier {
    val target = remember(onFile) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val t = event.awtTransferable
                if (!t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return false
                val file = (t.getTransferData(DataFlavor.javaFileListFlavor) as List<*>).firstOrNull() as? File ?: return false
                onFile(file)
                return true
            }
        }
    }
    return dragAndDropTarget(shouldStartDragAndDrop = { true }, target = target)
}
