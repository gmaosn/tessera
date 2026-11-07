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
import androidx.compose.runtime.key
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import tessera.acbf.ComicFiles
import tessera.acbf.NewBook
import tessera.acbf.Person
import tessera.acbf.OutsideImageFolder
import tessera.editor.EditorPrefs
import tessera.editor.EditorScreen
import tessera.editor.EnhancePrefs
import tessera.editor.BackgroundBook
import tessera.editor.Preparer
import tessera.editor.ImageCache
import tessera.editor.Label
import tessera.editor.Language
import tessera.editor.LocalPalette
import tessera.editor.ModeRequest
import tessera.editor.Notice
import tessera.editor.Pill
import tessera.editor.Session
import tessera.editor.Strings
import tessera.editor.TesseraTheme
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.util.Locale
import java.util.prefs.Preferences
import javax.swing.JOptionPane

/** One open comic: the file on disk, the editing session, its images and its preparer. */
private class Opened(
    val file: File,
    val session: Session,
    val images: ImageCache,
    /** Lives as long as the comic, which may go on being prepared after another one is opened. */
    val preparer: Preparer = Preparer(images, session, Edt),
)

/**
 * The Swing event thread, where Compose draws the window and the image cache lives. (There is no
 * Dispatchers.Main on the desktop without an extra library.)
 */
private val Edt = java.util.concurrent.Executor { javax.swing.SwingUtilities.invokeLater(it) }.asCoroutineDispatcher()

private fun open(file: File): Opened {
    val comic = ComicFiles.open(file)
    return Opened(file, Session(comic, file.name), ImageCache(comic, SuperResSidecar(file)))
}

/**
 * The editor for one comic. A new comic gets a wholly new editor: nothing composed for the
 * previous one survives (thumbnails kept the previous comic's click actions otherwise, since
 * references to the same local function compare equal and Compose skipped them).
 */
@Composable
fun EditorFor(
    session: Session, images: ImageCache, onSave: () -> String, saveRequest: Int = 0, notice: Notice? = null, prepareRequest: Int = 0,
    preparer: Preparer? = null, background: List<BackgroundBook> = emptyList(), modeRequest: ModeRequest? = null,
) {
    key(session) {
        EditorScreen(
            session, images, onSave = onSave, saveRequest = saveRequest, notice = notice, prepareRequest = prepareRequest, hostPreparer = preparer,
            background = background, modeRequest = modeRequest,
        )
    }
}

private val isMac = System.getProperty("os.name").lowercase().contains("mac")
private val isLinux = System.getProperty("os.name") == "Linux"
private val prefs: Preferences = Preferences.userRoot().node("tessera")

/**
 * The desktop's scale for X11 programs. AWT runs under XWayland and sees the screen at 100 % whatever
 * the desktop's scale (a 2880 × 1920 screen at 200 % under KDE: a window of tiny controls), so it is
 * read from the X resources' Xft.dpi (192 at 200 %, as KDE and GNOME set it), else from Hyprland's
 * monitors, and given to Java before Swing and Compose start. Null: nothing found, below 1,
 * unreadable, or chosen by the person (sun.java2d.uiScale, GDK_SCALE).
 */
private fun linuxUiScale(): String? {
    if (System.getProperty("sun.java2d.uiScale") != null || System.getenv("GDK_SCALE") != null) return null
    val dpi = commandOutput("xrdb", "-query")?.let { Regex("""(?m)^Xft\.dpi:\s*([\d.]+)""").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
    val scale = dpi?.let { it / 96.0 } ?: hyprlandUiScale() ?: return null
    return if (scale > 1.0 && scale <= 4.0) scale.toString() else null
}

/**
 * Hyprland with `xwayland:force_zero_scaling` leaves X11 programs unscaled and sets no Xft.dpi: they
 * must scale themselves. Its monitors' largest scale then (`hyprctl monitors -j`); null when Hyprland
 * is not the desktop, or scales X11 programs itself (they would be scaled twice).
 */
private fun hyprlandUiScale(): Double? {
    if (System.getenv("HYPRLAND_INSTANCE_SIGNATURE").isNullOrEmpty()) return null
    val option = commandOutput("hyprctl", "getoption", "xwayland:force_zero_scaling", "-j") ?: return null
    // « "bool": true » (Hyprland since 2025), « "int": 1 » before.
    if (!Regex(""""(?:bool|int)"\s*:\s*(?:true|1)\b""").containsMatchIn(option)) return null
    val monitors = commandOutput("hyprctl", "monitors", "-j") ?: return null
    return Regex(""""scale"\s*:\s*([\d.]+)""").findAll(monitors).mapNotNull { it.groupValues[1].toDoubleOrNull() }.maxOrNull()
}

/** A short command's output, or null when it cannot run. */
private fun commandOutput(vararg command: String): String? = runCatching {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    val text = process.inputStream.bufferedReader().use { it.readText() }
    if (process.waitFor() == 0) text else null
}.getOrNull()

fun main(args: Array<String>) {
    startTrace()
    if (isLinux) linuxUiScale()?.let { System.setProperty("sun.java2d.uiScale", it) }
    // The language chosen in the menu, else the system's.
    Strings.language = Language.of(prefs.get("language", null) ?: Locale.getDefault().language)
    if (!isMac) {
        Strings.cmd = "Ctrl+"; Strings.alt = "Alt"
    }
    EditorPrefs.creator = prefs.get("creator", "")
    EditorPrefs.onChange = { prefs.put("creator", EditorPrefs.creator) }
    EnhancePrefs.decode(prefs.get("enhance.editor", null))?.let { EnhancePrefs.editor = it }
    EnhancePrefs.decode(prefs.get("enhance.reader", null))?.let { EnhancePrefs.reader = it }
    EnhancePrefs.onChange = {
        prefs.put("enhance.editor", EnhancePrefs.encode(EnhancePrefs.editor))
        prefs.put("enhance.reader", EnhancePrefs.encode(EnhancePrefs.reader))
    }
    // The file dialogs open where the last file was chosen.
    FilePicker.folder = prefs.get("folder", null)?.let(::File)
    FilePicker.onFolder = { prefs.put("folder", it.path) }
    application {
        var opened by remember { mutableStateOf(args.firstOrNull()?.let { runCatching { open(File(it)) }.getOrNull() }) }
        var error by remember { mutableStateOf<String?>(null) }
        // 1440 × 920, or what the screen leaves (a 1440 × 960 screen at 200 % minus its panel).
        val room = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val state = rememberWindowState(size = DpSize(minOf(1440, room.width).dp, minOf(920, room.height).dp))
        val title = opened?.let { "${it.file.name}${if (it.session.dirty) " •" else ""} — Tessera" } ?: "Tessera"

        var saveRequest by remember { mutableStateOf(0) }
        var prepareRequest by remember { mutableStateOf(0) }
        // Comics whose whole-book preparation goes on while another one is open.
        val background = remember { androidx.compose.runtime.mutableStateListOf<Opened>() }
        // PDF import: the file being set up, its options and destination, then the progress.
        var plan by remember { mutableStateOf<Pair<File, PdfInfo>?>(null) }
        var importOptions by remember { mutableStateOf(PdfImportOptions()) }
        var importTarget by remember { mutableStateOf(File("")) }
        var importProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
        var importJob by remember { mutableStateOf<Job?>(null) }
        // Scans import: the folder and its scans, the options and destination; progress as above.
        var scanPlan by remember { mutableStateOf<Pair<File, List<File>>?>(null) }
        var scanOptions by remember { mutableStateOf(ScanImportOptions()) }
        val scope = rememberCoroutineScope()
        var notice by remember { mutableStateOf<Notice?>(null) }
        var modeRequest by remember { mutableStateOf<ModeRequest?>(null) }

        fun save(o: Opened): String = runCatching {
            val addedAcbf = o.session.comic.generated
            val reopened = ComicFiles.save(o.session.comic, o.file)
            o.images.comic = reopened
            o.session.saved(reopened)
            Strings.saved(addedAcbf)
        }.getOrElse { Strings.saveFailed(it.message) }

        // Finished background preparations let go of their comic.
        androidx.compose.runtime.LaunchedEffect(Unit) {
            androidx.compose.runtime.snapshotFlow { background.filter { !it.preparer.active } }.collect { done ->
                for (b in done) { b.preparer.close(); background.remove(b) }
            }
        }

        /** Makes [next] the open comic; the previous one goes on preparing if it was doing the whole book. */
        fun switchTo(next: Opened) {
            val previous = opened
            if (previous != null && previous !== next) {
                if (previous.preparer.wholeBook && previous.preparer.active) {
                    previous.images.shown = emptyList()
                    background += previous
                } else {
                    previous.preparer.close()
                }
            }
            background.remove(next)
            opened = next
        }

        Window(onCloseRequest = { if (mayDiscard(opened, ::save)) { background.forEach { it.preparer.close() }; opened?.preparer?.close(); exitApplication() } }, state = state, title = title) {
            fun planImport(pdf: File) {
                if (!mayDiscard(opened, ::save)) return
                runCatching { PdfImport.inspect(pdf) }
                    .onSuccess { plan = pdf to it; importTarget = freeName(pdf) }
                    .onFailure { e -> Strings.importFailed(e.message).let { error = it; notice = Notice(it) } }
            }

            fun runImport() {
                val (pdf, info) = plan ?: return
                val target = importTarget
                importProgress = 0 to info.pages
                importJob = scope.launch {
                    try {
                        val result = PdfImport.import(pdf, target, importOptions) { done, total -> importProgress = done to total }
                        val o = open(target)
                        o.session.suggested = NewBook(
                            title = info.title.ifBlank { pdf.nameWithoutExtension },
                            authors = info.author.split(Regex("\\s*(?:[,;&]|\\bet\\b|\\band\\b)\\s*")).map { Person.fromName(it) }.filter { !it.isEmpty },
                            annotation = info.subject,
                        )
                        switchTo(o)
                        error = null
                        notice = Notice(Strings.importDone(result.pages, result.originals + result.extracted, result.rendered))
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Strings.importFailed(e.message).let { error = it; notice = Notice(it) }
                    } finally {
                        importProgress = null; plan = null; importJob = null
                    }
                }
            }

            fun planScans(dir: File) {
                if (!mayDiscard(opened, ::save)) return
                val scans = ScanImport.scansIn(dir)
                if (scans.isEmpty()) { Strings.noScans.let { error = it; notice = Notice(it) }; return }
                scanPlan = dir to scans
                importTarget = freeName(File(dir.absoluteFile.parentFile, dir.name + ".scans"))
            }

            fun runScans() {
                val (dir, scans) = scanPlan ?: return
                val target = importTarget
                importProgress = 0 to scans.size * 2
                importJob = scope.launch {
                    try {
                        val result = ScanImport.import(scans, target, scanOptions) { done, total -> importProgress = done to total }
                        val o = open(target)
                        o.session.suggested = NewBook(title = dir.name, authors = emptyList(), annotation = "")
                        switchTo(o)
                        error = null
                        notice = Notice(Strings.scansDone(result.scans, result.pages, result.keptWhole))
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Strings.importFailed(e.message).let { error = it; notice = Notice(it) }
                    } finally {
                        importProgress = null; scanPlan = null; importJob = null
                    }
                }
            }

            fun load(f: File) {
                if (f.isDirectory) return planScans(f)
                if (f.extension.equals("pdf", ignoreCase = true)) return planImport(f)
                if (!mayDiscard(opened, ::save)) return
                // A comic still being prepared in the background is taken back as it is.
                val again = background.firstOrNull { it.file.canonicalPath == f.canonicalPath }
                runCatching { again ?: open(f) }.onSuccess { switchTo(it); error = null }.onFailure { error = "${f.name} : ${it.message}" }
                tessera.editor.Trace.log { "load ${f.name}: ${error ?: "ok"}" }
            }

            ControlLoop(::load, pageInfo = {
                (opened?.let { "${it.file.name} page ${it.session.pageIndex + 1}/${it.session.pages.size}, preparing ${it.preparer.done}/${it.preparer.total} active=${it.preparer.active}" } ?: "welcome") +
                    background.joinToString("") { "; background ${it.file.name} ${it.preparer.done}/${it.preparer.total}" }
            }, prepare = { on -> opened?.preparer?.let { if (on) it.book() else it.stop() } })

            fun saveAs(o: Opened) {
                val target = pickSaveFile(window, o.file) ?: return
                notice = Notice(
                    runCatching {
                        val reopened = ComicFiles.saveAs(o.session.comic, o.file, target)
                        o.images.comic = reopened
                        o.images.store = SuperResSidecar(target)
                        o.session.saved(reopened)
                        o.session.fileName = target.name
                        opened = Opened(target, o.session, o.images, o.preparer)
                        Strings.savedAs(target.name)
                    }.getOrElse { if (it is OutsideImageFolder) Strings.mustStayBesideImages else Strings.saveFailed(it.message) },
                )
            }

            val current = opened
            Menus(
                onOpen = { pickFile(window)?.let(::load) },
                onImport = { pickPdf(window)?.let(::planImport) },
                onImportScans = { pickFolder(window)?.let(::planScans) },
                onSave = current?.let { { saveRequest++ } },
                onSaveAs = current?.let { o -> { saveAs(o) } },
                onPrepareBook = current?.let { { prepareRequest++ } },
                onMode = current?.let { { m: Int -> modeRequest = ModeRequest(m) } },
            )
            TesseraTheme {
                Box(Modifier.fillMaxSize().fileDrop(::load)) {
                    if (current == null) Welcome(error) { pickFile(window)?.let(::load) }
                    else EditorFor(
                        current.session, current.images, onSave = { save(current) }, saveRequest = saveRequest, notice = notice, prepareRequest = prepareRequest,
                        preparer = current.preparer, background = background.map { BackgroundBook(it.file.nameWithoutExtension, it.preparer) },
                        modeRequest = modeRequest,
                    )
                    val p = plan
                    val progress = importProgress
                    val sp = scanPlan
                    if (sp != null && progress != null) {
                        ImportProgress(sp.first, progress.first, progress.second, Strings.scansProgress(progress.first, progress.second)) { importJob?.cancel() }
                    } else if (sp != null) {
                        ScanImportDialog(
                            sp.first, sp.second.size, scanOptions, importTarget,
                            onOptions = { scanOptions = it },
                            onChangeTarget = { pickSaveFile(window, importTarget)?.let { importTarget = it } },
                            onCancel = { scanPlan = null },
                            onImport = ::runScans,
                        )
                    } else if (p != null && progress != null) {
                        ImportProgress(p.first, progress.first, progress.second) { importJob?.cancel() }
                    } else if (p != null) {
                        ImportDialog(
                            p.first, p.second, importOptions, importTarget,
                            onOptions = { importOptions = it },
                            onChangeTarget = { pickSaveFile(window, importTarget)?.let { importTarget = it } },
                            onCancel = { plan = null },
                            onImport = ::runImport,
                        )
                    }
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
private fun FrameWindowScope.Menus(
    onOpen: () -> Unit, onImport: () -> Unit, onImportScans: () -> Unit, onSave: (() -> Unit)?, onSaveAs: (() -> Unit)?, onPrepareBook: (() -> Unit)?, onMode: ((Int) -> Unit)?,
) {
    MenuBar {
        Menu(Strings.menuFile) {
            Item(Strings.menuOpen, shortcut = KeyShortcut(Key.O, meta = isMac, ctrl = !isMac), onClick = onOpen)
            Item(Strings.menuImportPdf, shortcut = KeyShortcut(Key.I, meta = isMac, ctrl = !isMac), onClick = onImport)
            Item(Strings.menuImportScans, shortcut = KeyShortcut(Key.I, meta = isMac, ctrl = !isMac, shift = true), onClick = onImportScans)
            Item(Strings.save, enabled = onSave != null, shortcut = KeyShortcut(Key.S, meta = isMac, ctrl = !isMac), onClick = { onSave?.invoke() })
            Item(Strings.menuSaveAs, enabled = onSaveAs != null, shortcut = KeyShortcut(Key.S, meta = isMac, ctrl = !isMac, shift = true), onClick = { onSaveAs?.invoke() })
        }
        Menu(Strings.menuView) {
            Item(Strings.menuFramesTab, enabled = onMode != null, shortcut = KeyShortcut(Key.One, meta = isMac, ctrl = !isMac), onClick = { onMode?.invoke(0) })
            Item(Strings.menuTextsTab, enabled = onMode != null, shortcut = KeyShortcut(Key.Two, meta = isMac, ctrl = !isMac), onClick = { onMode?.invoke(1) })
            Item(Strings.menuBookInfo, enabled = onMode != null, shortcut = KeyShortcut(Key.Three, meta = isMac, ctrl = !isMac), onClick = { onMode?.invoke(2) })
            Separator()
            Item(Strings.menuPrepareBook, enabled = onPrepareBook != null, onClick = { onPrepareBook?.invoke() })
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

private fun pickFile(window: java.awt.Frame): File? = FilePicker.open(window, Strings.openDialog, Strings.comicFiles, listOf("cbz", "zip", "acbf", "pdf"))

private fun pickPdf(window: java.awt.Frame): File? = FilePicker.open(window, Strings.importPdfDialog, "PDF", listOf("pdf"))

private fun pickFolder(window: java.awt.Frame): File? = FilePicker.folder(window, Strings.importScansDialog)

/** "Book.cbz" beside the PDF, or "Book (2).cbz" and so on when taken. */
private fun freeName(pdf: File): File {
    val dir = pdf.absoluteFile.parentFile
    var candidate = File(dir, pdf.nameWithoutExtension + ".cbz")
    var n = 2
    while (candidate.exists()) candidate = File(dir, "${pdf.nameWithoutExtension} (${n++}).cbz")
    return candidate
}

private fun pickSaveFile(window: java.awt.Frame, current: File): File? {
    val chosen = FilePicker.save(window, Strings.saveAsDialog, current) ?: return null
    // Keep the comic's kind: a CBZ stays a CBZ, an ACBF document stays one.
    val ext = current.extension.lowercase()
    val name = chosen.name
    val fixed = if (name.substringAfterLast('.', "").lowercase() == ext || ext.isEmpty()) name else "$name.$ext"
    return File(chosen.absoluteFile.parentFile, fixed)
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun Modifier.fileDrop(onFile: (File) -> Unit): Modifier {
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
