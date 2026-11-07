package tessera.app

import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.io.File
import java.io.FilenameFilter
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * The file dialogs. On macOS and Windows, the system's through AWT. On Linux, AWT's is GTK's run
 * inside Java, which comes up blank with JDK 25 under Plasma (owner, 2026-11-07: « Ouvrir ne
 * fonctionne pas »), so the desktop's own dialog is run as a program instead, `kdialog` under KDE,
 * else `zenity`, with the window's events kept flowing meanwhile; Swing's chooser when there is
 * neither or it cannot run.
 */
internal object FilePicker {
    private val linux = System.getProperty("os.name") == "Linux"
    private val isMac = System.getProperty("os.name").lowercase().contains("mac")

    /** The desktop's dialog program: kdialog under KDE, zenity elsewhere, either when the other is missing. */
    private val tool: String? by lazy {
        if (!linux) return@lazy null
        val kde = System.getenv("XDG_CURRENT_DESKTOP").orEmpty().contains("KDE", ignoreCase = true)
        val found = listOf("kdialog", "zenity").filter { onPath(it) }
        if (kde) found.firstOrNull() else found.lastOrNull()
    }

    private fun onPath(name: String) = System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, name).canExecute() }

    /** One at a time: the window stays live under a desktop dialog, and a second Open would stack another. */
    private var asking = false

    /**
     * Where the next dialog opens: the folder of the last file or folder chosen (the desktop's
     * dialogs run as programs remember nothing), else the home. Main keeps it in the preferences.
     */
    var folder: File? = null
    var onFolder: (File) -> Unit = {}
    private val start: File get() = folder?.takeIf { it.isDirectory } ?: File(System.getProperty("user.home"))

    private fun remember(chosen: File?, dir: File? = chosen?.absoluteFile?.parentFile): File? {
        if (chosen != null && dir != null) { folder = dir; onFolder(dir) }
        return chosen
    }

    /** An existing file with one of [extensions] (described as [kind]), or null when cancelled. */
    fun open(window: Frame, title: String, kind: String, extensions: List<String>): File? {
        val patterns = extensions.joinToString(" ") { "*.$it" }
        if (!linux) return remember(awt(window, title, FileDialog.LOAD, directory = start, filter = FilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in extensions }))
        return remember(desktop(
            kdialog = arrayOf("--getopenfilename", start.path, "$kind ($patterns)"),
            zenity = arrayOf("--file-selection", "--filename=${start.path}/", "--file-filter=$kind | $patterns"),
            title,
        ) { swing(window, title, JFileChooser.FILES_ONLY, directory = start, filter = FileNameExtensionFilter(kind, *extensions.toTypedArray())) })
    }

    /** Where to save, starting from [current]'s folder and name; the name as typed (the caller settles the extension); null when cancelled. */
    fun save(window: Frame, title: String, current: File): File? {
        if (!linux) return remember(awt(window, title, FileDialog.SAVE, current = current))
        return remember(desktop(
            kdialog = arrayOf("--getsavefilename", current.absolutePath),
            zenity = arrayOf("--file-selection", "--save", "--confirm-overwrite", "--filename=${current.absolutePath}"),
            title,
        ) { swing(window, title, JFileChooser.FILES_ONLY, selected = current, save = true) })
    }

    /** An existing folder, or null when cancelled (macOS lets a file dialog choose folders when asked). */
    fun folder(window: Frame, title: String): File? {
        val chosen = if (!linux) {
            if (isMac) System.setProperty("apple.awt.fileDialogForDirectories", "true")
            try {
                awt(window, title, FileDialog.LOAD, directory = start)
            } finally {
                if (isMac) System.setProperty("apple.awt.fileDialogForDirectories", "false")
            }
        } else desktop(
            kdialog = arrayOf("--getexistingdirectory", start.path),
            zenity = arrayOf("--file-selection", "--directory", "--filename=${start.path}/"),
            title,
        ) { swing(window, title, JFileChooser.DIRECTORIES_ONLY, directory = start) }
        val dir = chosen?.takeIf { it.isDirectory }
        return remember(dir, dir)
    }

    /** The desktop's dialog when there is one and it runs, else Swing's ([fallback]); null when cancelled. */
    private fun desktop(kdialog: Array<String>, zenity: Array<String>, title: String, fallback: () -> File?): File? {
        val answer = when (tool) {
            "kdialog" -> ask("kdialog", "--title", title, *kdialog)
            "zenity" -> ask("zenity", "--title=$title", *zenity)
            else -> null
        } ?: return fallback()
        return answer.getOrElse { return fallback() }?.let(::File)
    }

    /**
     * Runs the dialog program and gives the path it prints, null when cancelled, a failure when it
     * cannot run. On the event thread, the window's events go on being dispatched meanwhile, as
     * under a modal dialog.
     */
    private fun ask(vararg command: String): Result<String?> {
        if (asking) return Result.success(null)
        asking = true
        try {
            var answer: Result<String?> = Result.success(null)
            val loop = if (EventQueue.isDispatchThread()) Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop() else null
            val worker = Thread({
                answer = runCatching {
                    val process = ProcessBuilder(*command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                    val out = process.inputStream.bufferedReader().use { it.readText() }
                    if (process.waitFor() == 0) out.trimEnd('\n', '\r').takeIf { it.isNotEmpty() } else null
                }
                // exit() does nothing until the loop is entered.
                if (loop != null) while (!loop.exit()) Thread.sleep(10)
            }, "file dialog")
            worker.start()
            loop?.enter()
            worker.join()
            return answer
        } finally {
            asking = false
        }
    }

    private fun swing(window: Frame, title: String, mode: Int, directory: File? = null, filter: FileNameExtensionFilter? = null, selected: File? = null, save: Boolean = false): File? {
        val chooser = JFileChooser(selected?.absoluteFile?.parentFile ?: directory).apply {
            dialogTitle = title
            fileSelectionMode = mode
            filter?.let { fileFilter = it }
            selected?.let { selectedFile = it }
        }
        val answer = if (save) chooser.showSaveDialog(window) else chooser.showOpenDialog(window)
        return if (answer == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }

    private fun awt(window: Frame, title: String, mode: Int, directory: File? = null, current: File? = null, filter: FilenameFilter? = null): File? {
        val dialog = FileDialog(window, title, mode)
        filter?.let { dialog.filenameFilter = it }
        directory?.let { dialog.directory = it.path }
        current?.let { dialog.directory = it.absoluteFile.parent; dialog.file = it.name }
        dialog.isVisible = true
        return dialog.file?.let { File(dialog.directory, it) }
    }
}
