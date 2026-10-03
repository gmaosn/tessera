package acbf

import acbf.xml.XmlParser
import acbf.zip.ByteSink
import acbf.zip.ByteSource
import acbf.zip.ZipArchive
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class FileSource(file: File) : ByteSource, Closeable {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()

    @Synchronized
    override fun read(offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        raf.seek(offset)
        raf.readFully(out)
        return out
    }

    override fun close() = raf.close()
}

/** A folder on disk: for an ACBF file that references images beside it, or a folder of images. */
class DirectoryContainer(val dir: File) : Container {
    override val paths: List<String> by lazy {
        dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.toList()
    }

    override fun read(path: String): ByteArray? {
        val f = File(dir, path)
        return if (f.isFile && f.canonicalPath.startsWith(dir.canonicalPath)) f.readBytes() else null
    }
}

/** Opening and saving comics on disk. */
object ComicFiles {
    fun open(file: File): Comic {
        if (file.isDirectory) {
            val container = DirectoryContainer(file)
            container.paths.firstOrNull { it.endsWith(".acbf", ignoreCase = true) && '/' !in it }?.let {
                return Comic(AcbfDocument(XmlParser.parse(container.read(it)!!)), container, it, generated = false)
            }
            val doc = AcbfDocument.create(file.name, Comic.imagePaths(container.paths))
            return Comic(doc, container, "${file.name}.acbf", generated = true)
        }
        return when (file.extension.lowercase()) {
            "acbf" -> Comic(AcbfDocument.parse(file.readBytes()), DirectoryContainer(file.absoluteFile.parentFile), file.name, generated = false)
            "cbz", "zip" -> Comic.openCbz(ZipArchive(FileSource(file)), file.name)
            else -> throw IllegalArgumentException("Unsupported file: ${file.name}")
        }
    }

    /**
     * Saves [comic], read from [file], back to [file], and returns the reopened comic. The new
     * content is written beside the file and moved over it only once complete.
     */
    fun save(comic: Comic, file: File): Comic {
        val target = if (file.isDirectory) File(file, comic.acbfPath) else file
        val temp = File.createTempFile(".${target.name}.", ".tmp", target.absoluteFile.parentFile)
        try {
            when (val container = comic.container) {
                is ZipContainer -> {
                    FileOutputStream(temp).buffered(1 shl 20).use { out ->
                        comic.writeCbz(object : ByteSink {
                            override fun write(bytes: ByteArray) = out.write(bytes)
                        })
                    }
                    (container.archive.source as? Closeable)?.close()
                }
                else -> temp.writeBytes(comic.document.write())
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temp.delete()
        }
        return open(file)
    }
}
