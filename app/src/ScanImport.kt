package tessera.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import tessera.editor.enhance.Argb
import tessera.editor.scan.ScanFinish
import tessera.editor.scan.ScanPlan
import tessera.editor.scan.Scans
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.coroutines.coroutineContext

/**
 * How the double pages are read (right page first for a manga), and how much the paper near the
 * spine is unfolded and brought back into focus, as fractions of Tessera's own settings.
 */
data class ScanImportOptions(val rightToLeft: Boolean = true, val finish: ScanFinish = ScanFinish())

/** What came out: scans read, pages written, double pages kept whole. */
data class ScanImportResult(val scans: Int, val pages: Int, val keptWhole: Int)

/**
 * Turns a folder of flatbed scans of an open book into a CBZ of its pages: each scan straightened,
 * the spine's shadow taken off, the paper unfolded and brought back into focus near the spine,
 * cut at the fold (a picture across both pages stays whole), trimmed to the paper. The page
 * size is agreed over the whole folder, so every scan is looked at before any page is written.
 * Pages are PNG: whatever was recomputed is never compressed with loss.
 */
object ScanImport {
    private val extensions = setOf("png", "jpg", "jpeg", "tif", "tiff", "bmp")

    /** The scans in [dir], in natural order (2 before 10). */
    fun scansIn(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && !f.name.startsWith(".") && f.extension.lowercase() in extensions }.orEmpty().sortedWith(naturalOrder)

    /** Writes [target]; [progress] gets (steps done, total): each scan is read twice. Cancelling leaves no file behind. */
    suspend fun import(scans: List<File>, target: File, options: ScanImportOptions, progress: (Int, Int) -> Unit = { _, _ -> }): ScanImportResult =
        withContext(Dispatchers.Default) {
            val total = scans.size * 2
            val plans = Scans.reconcile(
                scans.mapIndexed { i, f ->
                    coroutineContext.ensureActive()
                    val page = read(f)
                    // A double page lies on its side when the scan is taller than wide.
                    Scans.plan(page, if (page.height > page.width) 1 else 0).also { progress(i + 1, total) }
                },
            )
            val temp = File.createTempFile(".${target.name}.", ".tmp", target.absoluteFile.parentFile)
            try {
                var pages = 0
                val digits = maxOf(3, (scans.size * 2).toString().length)
                ZipOutputStream(temp.outputStream().buffered(1 shl 20)).use { zip ->
                    for ((i, f) in scans.withIndex()) {
                        coroutineContext.ensureActive()
                        val plan: ScanPlan = plans[i]
                        for (page in Scans.render(read(f), plan, options.rightToLeft, options.finish)) {
                            pages++
                            zip.putNextEntry(ZipEntry("page-" + pages.toString().padStart(digits, '0') + ".png"))
                            zip.write(png(page))
                            zip.closeEntry()
                        }
                        progress(scans.size + i + 1, total)
                    }
                }
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                ScanImportResult(scans.size, pages, plans.count { it.keepWhole })
            } finally {
                temp.delete()
            }
        }

    private fun read(f: File): Argb {
        val img = ImageIO.read(f) ?: error("${f.name}: not an image")
        return Argb(img.width, img.height, img.getRGB(0, 0, img.width, img.height, null, 0, img.width))
    }

    private fun png(page: Argb): ByteArray {
        val img = BufferedImage(page.width, page.height, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, page.width, page.height, page.pixels, 0, page.width)
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    /** Names compared with their runs of digits taken as numbers. */
    private val naturalOrder = Comparator<File> { a, b ->
        val x = a.name.lowercase()
        val y = b.name.lowercase()
        var i = 0
        var j = 0
        while (i < x.length && j < y.length) {
            if (x[i].isDigit() && y[j].isDigit()) {
                val si = i
                val sj = j
                while (i < x.length && x[i].isDigit()) i++
                while (j < y.length && y[j].isDigit()) j++
                val c = x.substring(si, i).trimStart('0').padStart(20, '0').compareTo(y.substring(sj, j).trimStart('0').padStart(20, '0'))
                if (c != 0) return@Comparator c
            } else {
                if (x[i] != y[j]) return@Comparator x[i].compareTo(y[j])
                i++
                j++
            }
        }
        (x.length - i).compareTo(y.length - j)
    }
}
