package tessera.acbf

import tessera.xml.XmlParser
import tessera.zip.ByteSink
import tessera.zip.ByteSource
import tessera.zip.ZipArchive
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
     * Saves [comic], read from [file], back to [file]. The new content is written beside the file
     * and moved over it only once complete. Returns the comic with the same document, reading its
     * images from the saved file from now on.
     */
    fun save(comic: Comic, file: File): Comic = saveAs(comic, file, file)

    /**
     * Saves [comic], read from [source], as [destination]; returns the comic reading from the new
     * file. A CBZ can go anywhere. An ACBF document whose images lie beside it must stay in their
     * folder, or its image paths would break: [OutsideImageFolder] is thrown otherwise.
     */
    fun saveAs(comic: Comic, source: File, destination: File): Comic {
        val file = destination
        val container = comic.container
        if (container is DirectoryContainer && !source.isDirectory) {
            val folder = container.dir.canonicalFile
            if (destination.absoluteFile.parentFile.canonicalFile != folder) throw OutsideImageFolder(folder)
        }
        val target = if (file.isDirectory) File(file, comic.acbfPath) else file
        val temp = File.createTempFile(".${target.name}.", ".tmp", target.absoluteFile.parentFile)
        try {
            val zip = comic.container as? ZipContainer
            if (zip != null) {
                FileOutputStream(temp).buffered(1 shl 20).use { out ->
                    comic.writeCbz(object : ByteSink {
                        override fun write(bytes: ByteArray) = out.write(bytes)
                    })
                }
            } else {
                temp.writeBytes(comic.document.write())
            }
            val opened = zip?.archive?.source as? Closeable
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: java.io.IOException) {
                // Windows will not replace a file that is still open: close it and try again.
                if (opened == null) throw e
                opened.close()
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
            val acbfPath = if (zip == null && !file.isDirectory) file.name else comic.acbfPath
            if (zip == null) return Comic(comic.document, comic.container, acbfPath, generated = false)
            opened?.close()
            return Comic(comic.document, ZipContainer(ZipArchive(FileSource(file))), comic.acbfPath, generated = false)
        } finally {
            temp.delete()
        }
    }
}

/** An ACBF document with external images cannot be saved away from them. */
class OutsideImageFolder(val folder: File) : Exception("The ACBF file must stay in ${folder.path}, beside its images")
