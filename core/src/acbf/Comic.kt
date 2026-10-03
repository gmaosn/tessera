package tessera.acbf

import tessera.xml.XmlParser
import tessera.zip.ByteSink
import tessera.zip.ZipArchive
import tessera.zip.ZipRewriter
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Where a comic's files live: a CBZ archive, a folder, or nothing beyond the ACBF file. */
interface Container {
    /** Every file path, with '/' separators. */
    val paths: List<String>
    fun read(path: String): ByteArray?
}

class ZipContainer(val archive: ZipArchive) : Container {
    override val paths: List<String> = archive.entries.filter { !it.isDirectory }.map { it.name }
    override fun read(path: String): ByteArray? = archive.entry(path)?.let(archive::read)
}

object EmptyContainer : Container {
    override val paths: List<String> = emptyList()
    override fun read(path: String): ByteArray? = null
}

/**
 * An opened comic. [acbfPath] is the ACBF file's path inside the container; [generated] is true
 * when the container had none and a document was made from its images (saving adds it).
 */
class Comic(
    val document: AcbfDocument,
    val container: Container,
    val acbfPath: String,
    val generated: Boolean,
) {
    private val acbfDir = acbfPath.substringBeforeLast('/', "")

    /** The bytes of the image an `href` points to, or null when it cannot be found here. */
    @OptIn(ExperimentalEncodingApi::class)
    fun image(href: String?): ByteArray? {
        if (href.isNullOrBlank()) return null
        if (href.startsWith("#")) {
            val binary = document.binaries[href.substring(1)] ?: return null
            val text = binary.textContent.filterNot { it.isWhitespace() }
            return runCatching { Base64.decode(text) }.getOrNull()
        }
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(href) && !href.startsWith("file:")) return null
        if (href.startsWith("zip:")) return null
        for (candidate in candidates(href)) container.read(candidate)?.let { return it }
        // Archives made on case-insensitive file systems.
        val wanted = candidates(href).map { it.lowercase() }.toSet()
        return container.paths.firstOrNull { it.lowercase() in wanted }?.let(container::read)
    }

    private fun candidates(href: String): List<String> {
        val path = href.removePrefix("file://").replace('\\', '/')
        val decoded = percentDecode(path)
        return listOf(path, decoded).distinct().flatMap { p ->
            listOf(normalize(if (acbfDir.isEmpty() || p.startsWith("/")) p.trimStart('/') else "$acbfDir/$p"), normalize(p.trimStart('/')))
        }.distinct()
    }

    /** Writes the comic as a CBZ: every entry copied raw, the ACBF file replaced or added. */
    fun writeCbz(sink: ByteSink) {
        val archive = (container as? ZipContainer)?.archive ?: error("not a CBZ comic")
        ZipRewriter(archive).apply { put(acbfPath, document.write()) }.writeTo(sink)
    }

    companion object {
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

        /** Opens a CBZ: its ACBF file when there is one, otherwise a document made from its images. */
        fun openCbz(archive: ZipArchive, fileName: String): Comic {
            val container = ZipContainer(archive)
            val acbf = container.paths.filter { it.endsWith(".acbf", ignoreCase = true) }
                .minByOrNull { it.count { c -> c == '/' } }
            if (acbf != null) return Comic(AcbfDocument(XmlParser.parse(container.read(acbf)!!)), container, acbf, generated = false)
            val title = fileName.substringAfterLast('/').substringBeforeLast('.')
            val doc = AcbfDocument.create(title, imagePaths(container.paths))
            return Comic(doc, container, "$title.acbf".replace('/', '_'), generated = true)
        }

        /** Image files in reading order: natural sort, so page2 comes before page10. */
        fun imagePaths(paths: List<String>): List<String> =
            paths.filter { p -> p.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS && !p.substringAfterLast('/').startsWith(".") && !p.startsWith("__MACOSX/") }
                .sortedWith(NaturalOrder)
    }
}

/** Compares strings with digit runs taken as numbers ("page2" < "page10"). */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val si = i
                val sj = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val na = a.substring(si, i).trimStart('0')
                val nb = b.substring(sj, j).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++; j++
            }
        }
        return (a.length - i).compareTo(b.length - j).takeIf { it != 0 } ?: a.compareTo(b)
    }
}

private fun normalize(path: String): String {
    val out = ArrayList<String>()
    for (part in path.split('/')) when (part) {
        "", "." -> {}
        ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
        else -> out += part
    }
    return out.joinToString("/")
}

private fun percentDecode(s: String): String {
    if ('%' !in s) return s
    val bytes = ArrayList<Byte>()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%' && i + 2 < s.length && s.substring(i + 1, i + 3).toIntOrNull(16) != null) {
            bytes += s.substring(i + 1, i + 3).toInt(16).toByte(); i += 3
        } else {
            c.toString().encodeToByteArray().forEach { bytes += it }; i++
        }
    }
    return bytes.toByteArray().decodeToString()
}
