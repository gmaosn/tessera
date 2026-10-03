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
import tessera.editor.Language
import tessera.editor.LocalPalette
import tessera.editor.Pill
import tessera.editor.Session
import tessera.editor.Strings
import tessera.editor.TesseraTheme
import java.awt.FileDialog
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.util.Locale
import java.util.prefs.Preferences
import javax.swing.JOptionPane

/** One open comic: the file on disk, the editing session and its images. */
private class Opened(val file: File, val session: Session, val images: ImageCache)

private fun open(file: File): Opened {
    val comic = ComicFiles.open(file)
    return Opened(file, Session(comic, file.name), ImageCache(comic))
}

private val isMac = System.getProperty("os.name").lowercase().contains("mac")
private val prefs: Preferences = Preferences.userRoot().node("tessera")

fun main(args: Array<String>) {
    // The language chosen in the menu, else the system's.
    Strings.language = Language.of(prefs.get("language", null) ?: Locale.getDefault().language)
    if (!isMac) {
        Strings.cmd = "Ctrl+"; Strings.alt = "Alt"
    }
    application {
        var opened by remember { mutableStateOf(args.firstOrNull()?.let { runCatching { open(File(it)) }.getOrNull() }) }
        var error by remember { mutableStateOf<String?>(null) }
        val state = rememberWindowState(size = DpSize(1440.dp, 920.dp))
        val title = opened?.let { "${it.file.name}${if (it.session.dirty) " •" else ""} — Tessera" } ?: "Tessera"

        fun save(o: Opened): String = runCatching {
            val addedAcbf = o.session.comic.generated
            val reopened = ComicFiles.save(o.session.comic, o.file)
            o.images.comic = reopened
            o.session.saved(reopened)
            Strings.saved(addedAcbf)
        }.getOrElse { Strings.saveFailed(it.message) }

        Window(onCloseRequest = { if (mayDiscard(opened, ::save)) exitApplication() }, state = state, title = title) {
            fun load(f: File) {
                if (!mayDiscard(opened, ::save)) return
                runCatching { open(f) }.onSuccess { opened = it; error = null }.onFailure { error = "${f.name} : ${it.message}" }
            }

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
}

/**
 * True when the open comic may be closed: nothing unsaved, or the user chose to save (and it
 * worked) or to discard. False when the user cancels.
 */
private fun mayDiscard(o: Opened?, save: (Opened) -> String): Boolean {
    if (o == null || !o.session.dirty) return true
    val options = arrayOf(Strings.save, Strings.dontSave, Strings.cancel)
    val choice = JOptionPane.showOptionDialog(
        null, Strings.unsavedMessage(o.file.name), Strings.unsavedTitle,
        JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0],
    )
    return when (choice) {
        0 -> {
            val message = save(o)
            if (o.session.dirty) JOptionPane.showMessageDialog(null, message)
            !o.session.dirty
        }
        1 -> true
        else -> false
    }
}

@Composable
private fun FrameWindowScope.Menus(onOpen: () -> Unit, onSave: (() -> Unit)?) {
    MenuBar {
        Menu(Strings.menuFile) {
            Item(Strings.menuOpen, shortcut = KeyShortcut(Key.O, meta = isMac, ctrl = !isMac), onClick = onOpen)
            Item(Strings.save, enabled = onSave != null, shortcut = KeyShortcut(Key.S, meta = isMac, ctrl = !isMac), onClick = { onSave?.invoke() })
        }
        Menu(Strings.menuLanguage) {
            for (l in Language.entries) {
                RadioButtonItem(l.label, selected = Strings.language == l, onClick = {
                    Strings.language = l
                    prefs.put("language", l.code)
                })
            }
        }
    }
}

@Composable
private fun Welcome(error: String?, onOpen: () -> Unit) {
    val c = LocalPalette.current
    Box(Modifier.fillMaxSize().background(c.well), contentAlignment = Alignment.Center) {
        Column(
            Modifier.background(c.paper, RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Label("Tessera", size = 26.sp, weight = FontWeight.SemiBold)
            Label(Strings.welcome, color = c.muted)
            Pill(Strings.menuOpen, onOpen, primary = true)
            if (error != null) Label(error, color = c.danger, size = 12.sp)
        }
    }
}

private fun pickFile(window: java.awt.Frame): File? {
    val dialog = FileDialog(window, Strings.openDialog, FileDialog.LOAD)
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
