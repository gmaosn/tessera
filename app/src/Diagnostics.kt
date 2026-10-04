package tessera.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.FrameWindowScope
import kotlinx.coroutines.delay
import org.jetbrains.skiko.SkiaLayer
import tessera.editor.Trace
import java.awt.Component
import java.awt.Container
import java.awt.FileDialog
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.io.File
import java.time.LocalTime
import javax.swing.SwingUtilities

/*
 * Diagnostics, only with TESSERA_TRACE=1: the trace goes to build/trace.log, and commands written
 * to build/control (one per line) are run: "shot NAME", "click X Y" (window points),
 * "dialog PATH" (shows the Open dialog, closes it, then opens PATH, as a user would), "prepare"
 * and "stopprepare" (the open comic's whole-book preparation).
 */

internal val diagnostics = System.getenv("TESSERA_TRACE") != null
private val traceFile = File("build/trace.log")
private val controlFile = File("build/control")

internal fun startTrace() {
    if (!diagnostics) return
    traceFile.parentFile.mkdirs()
    traceFile.writeText("")
    Trace.sink = { line -> synchronized(traceFile) { traceFile.appendText("${LocalTime.now()} $line\n") } }
}

@Composable
internal fun FrameWindowScope.ControlLoop(load: (File) -> Unit, pageInfo: () -> String, prepare: (Boolean) -> Unit = {}) {
    if (!diagnostics) return
    LaunchedEffect(Unit) {
        controlFile.delete()
        Trace.log { "ready: ${pageInfo()}" }
        while (true) {
            delay(300)
            if (!controlFile.exists()) continue
            val commands = controlFile.readLines()
            controlFile.delete()
            for (command in commands.map { it.trim() }.filter { it.isNotEmpty() }) {
                Trace.log { "command: $command" }
                val words = command.split(' ', limit = 2)
                when (words[0]) {
                    "shot" -> shot(words.getOrElse(1) { "shot" })
                    "click" -> {
                        val (x, y) = words[1].split(' ').map { it.toInt() }
                        click(x, y)
                        delay(1200)
                    }
                    "dialog" -> {
                        val dialog = FileDialog(window, "Diagnostic", FileDialog.LOAD)
                        Thread { Thread.sleep(1500); SwingUtilities.invokeLater { dialog.isVisible = false; dialog.dispose() } }.start()
                        dialog.isVisible = true
                        load(File(words[1]))
                        delay(1500)
                    }
                    "load" -> { load(File(words[1])); delay(1500) }
                    "prepare" -> prepare(true)
                    "stopprepare" -> prepare(false)
                }
                Trace.log { "after $command: ${pageInfo()}" }
            }
        }
    }
}

private fun FrameWindowScope.layer(): SkiaLayer? {
    fun all(c: Component): List<Component> = listOf(c) + ((c as? Container)?.components?.flatMap { all(it) } ?: emptyList())
    return all(window.contentPane).filterIsInstance<SkiaLayer>().firstOrNull()
}

private fun FrameWindowScope.shot(name: String) {
    val bitmap = layer()?.screenshot() ?: return Trace.log { "no screenshot" }
    File("build/screens").mkdirs()
    File("build/screens/$name.png").writeBytes(org.jetbrains.skia.Image.makeFromBitmap(bitmap).encodeToData()!!.bytes)
}

private suspend fun FrameWindowScope.click(x: Int, y: Int) {
    val target = layer() ?: return
    val t = System.currentTimeMillis()
    for (id in listOf(MouseEvent.MOUSE_MOVED, MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
        target.dispatchEvent(MouseEvent(target, id, t, if (id == MouseEvent.MOUSE_PRESSED) InputEvent.BUTTON1_DOWN_MASK else 0, x, y, 1, false, MouseEvent.BUTTON1))
        delay(60)
    }
}
