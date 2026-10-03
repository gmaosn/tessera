package acbf.zip

/** Random access to the bytes of an archive. */
interface ByteSource {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray
}

class ByteArraySource(private val bytes: ByteArray) : ByteSource {
    override val size: Long get() = bytes.size.toLong()
    override fun read(offset: Long, length: Int): ByteArray = bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
}

interface ByteSink {
    fun write(bytes: ByteArray)
}

class ZipException(message: String) : Exception(message)

/** Raw DEFLATE (no zlib header), provided by the platform. */
expect fun inflate(data: ByteArray, uncompressedSize: Int): ByteArray
expect fun deflate(data: ByteArray): ByteArray

/** One entry of a ZIP archive, as its central directory describes it. */
class ZipEntry internal constructor(
    val name: String,
    val method: Int,
    val crc: Long,
    val compressedSize: Long,
    val size: Long,
    internal val localOffset: Long,
    /** The central directory record, kept to be copied back unchanged. */
    internal val centralRecord: ByteArray,
) {
    val isDirectory: Boolean get() = name.endsWith('/')
}

/**
 * Reads a ZIP archive (CBZ) through its central directory. Entries can be read one by one, and
 * [ZipRewriter] copies them back raw, so images are never recompressed.
 */
class ZipArchive(val source: ByteSource) {
    val entries: List<ZipEntry> = readCentralDirectory()

    fun entry(name: String): ZipEntry? = entries.firstOrNull { it.name == name }

    fun read(entry: ZipEntry): ByteArray {
        val dataStart = dataOffset(entry)
        if (entry.compressedSize > Int.MAX_VALUE || entry.size > Int.MAX_VALUE) throw ZipException("${entry.name} is too large")
        val raw = source.read(dataStart, entry.compressedSize.toInt())
        val data = when (entry.method) {
            0 -> raw
            8 -> inflate(raw, entry.size.toInt())
            else -> throw ZipException("${entry.name}: unsupported compression method ${entry.method}")
        }
        if (crc32(data) != entry.crc) throw ZipException("${entry.name}: checksum mismatch")
        return data
    }

    internal fun dataOffset(entry: ZipEntry): Long {
        val header = source.read(entry.localOffset, 30)
        if (header.u32(0) != LOCAL_SIG) throw ZipException("${entry.name}: bad local header")
        return entry.localOffset + 30 + header.u16(26) + header.u16(28)
    }

    /** The length of the entry's local header, data and data descriptor, for a raw copy. */
    internal fun storedLength(entry: ZipEntry): Long {
        val header = source.read(entry.localOffset, 30)
        var end = dataOffset(entry) + entry.compressedSize
        if (header.u16(6) and 0x8 != 0) {
            // Data descriptor: optional signature, then CRC and both sizes.
            val d = source.read(end, minOf(16L, source.size - end).toInt())
            end += if (d.size >= 4 && d.u32(0) == DESCRIPTOR_SIG) 16 else 12
        }
        return end - entry.localOffset
    }

    private fun readCentralDirectory(): List<ZipEntry> {
        val tailLength = minOf(source.size, 22L + 0xFFFF).toInt()
        val tail = source.read(source.size - tailLength, tailLength)
        var eocd = -1
        for (k in tail.size - 22 downTo 0) if (tail.u32(k) == EOCD_SIG) {
            eocd = k; break
        }
        if (eocd < 0) throw ZipException("not a ZIP archive")
        val count = tail.u16(eocd + 10)
        val cdSize = tail.u32(eocd + 12)
        val cdOffset = tail.u32(eocd + 16)
        if (count == 0xFFFF || cdOffset == 0xFFFFFFFFL) throw ZipException("ZIP64 archives are not supported yet")
        val cd = source.read(cdOffset, cdSize.toInt())
        val out = ArrayList<ZipEntry>(count)
        var p = 0
        repeat(count) {
            if (cd.u32(p) != CENTRAL_SIG) throw ZipException("corrupt central directory")
            val flags = cd.u16(p + 8)
            val nameLength = cd.u16(p + 28)
            val recordLength = 46 + nameLength + cd.u16(p + 30) + cd.u16(p + 32)
            val nameBytes = cd.copyOfRange(p + 46, p + 46 + nameLength)
            out += ZipEntry(
                name = if (flags and 0x800 != 0) nameBytes.decodeToString() else decodeCp437(nameBytes),
                method = cd.u16(p + 10),
                crc = cd.u32(p + 16),
                compressedSize = cd.u32(p + 20),
                size = cd.u32(p + 24),
                localOffset = cd.u32(p + 42),
                centralRecord = cd.copyOfRange(p, p + recordLength),
            )
            p += recordLength
        }
        return out
    }
}

/**
 * Writes a copy of [archive] where some entries are replaced or added. Every other entry is
 * copied byte for byte, in its original order.
 */
class ZipRewriter(private val archive: ZipArchive) {
    private val replacements = LinkedHashMap<String, ByteArray>()

    /** Replaces the entry called [name], or adds it at the end when there is none. */
    fun put(name: String, data: ByteArray) {
        replacements[name] = data
    }

    fun writeTo(sink: ByteSink) {
        var offset = 0L
        val central = ArrayList<ByteArray>()
        val pending = LinkedHashMap(replacements)
        for (entry in archive.entries) {
            val replacement = pending.remove(entry.name)
            if (replacement != null) {
                val (local, record) = freshEntry(entry.name, replacement, offset, entry.centralRecord)
                sink.write(local); offset += local.size
                central += record
                continue
            }
            val length = archive.storedLength(entry)
            var copied = 0L
            while (copied < length) {
                val chunk = minOf(1L shl 20, length - copied).toInt()
                sink.write(archive.source.read(entry.localOffset + copied, chunk))
                copied += chunk
            }
            central += entry.centralRecord.copyOf().also { it.put32(42, offset) }
            offset += length
        }
        for ((name, data) in pending) {
            val (local, record) = freshEntry(name, data, offset, null)
            sink.write(local); offset += local.size
            central += record
        }
        val cdStart = offset
        var cdSize = 0L
        for (r in central) {
            sink.write(r); cdSize += r.size
        }
        if (central.size >= 0xFFFF || cdStart + cdSize >= 0xFFFFFFFFL) throw ZipException("archive too large for ZIP without ZIP64")
        val eocd = ByteArray(22)
        eocd.put32(0, EOCD_SIG)
        eocd.put16(8, central.size); eocd.put16(10, central.size)
        eocd.put32(12, cdSize); eocd.put32(16, cdStart)
        sink.write(eocd)
    }

    /** A deflated entry; the time stamp and attributes come from [template] when replacing. */
    private fun freshEntry(name: String, data: ByteArray, offset: Long, template: ByteArray?): Pair<ByteArray, ByteArray> {
        val compressed = deflate(data)
        val (method, stored) = if (compressed.size < data.size) 8 to compressed else 0 to data
        val nameBytes = name.encodeToByteArray()
        val utf8 = nameBytes.any { it < 0 }
        val crc = crc32(data)
        val time = template?.u16(12) ?: 0
        val date = template?.u16(14) ?: DEFAULT_DATE
        val flags = if (utf8) 0x800 else 0

        val local = ByteArray(30 + nameBytes.size + stored.size)
        local.put32(0, LOCAL_SIG); local.put16(4, 20); local.put16(6, flags); local.put16(8, method)
        local.put16(10, time); local.put16(12, date); local.put32(14, crc)
        local.put32(18, stored.size.toLong()); local.put32(22, data.size.toLong())
        local.put16(26, nameBytes.size); local.put16(28, 0)
        nameBytes.copyInto(local, 30); stored.copyInto(local, 30 + nameBytes.size)

        val record = ByteArray(46 + nameBytes.size)
        record.put32(0, CENTRAL_SIG); record.put16(4, template?.u16(4) ?: 20); record.put16(6, 20)
        record.put16(8, flags); record.put16(10, method); record.put16(12, time); record.put16(14, date)
        record.put32(16, crc); record.put32(20, stored.size.toLong()); record.put32(24, data.size.toLong())
        record.put16(28, nameBytes.size)
        record.put32(38, template?.u32(38) ?: 0L)
        record.put32(42, offset)
        nameBytes.copyInto(record, 46)
        return local to record
    }
}

private const val LOCAL_SIG = 0x04034b50L
private const val CENTRAL_SIG = 0x02014b50L
private const val EOCD_SIG = 0x06054b50L
private const val DESCRIPTOR_SIG = 0x08074b50L
/** 1 January 2000, in MS-DOS date format. */
private const val DEFAULT_DATE = (20 shl 9) or (1 shl 5) or 1

internal fun ByteArray.u16(at: Int): Int = (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)
internal fun ByteArray.u32(at: Int): Long = (u16(at).toLong()) or (u16(at + 2).toLong() shl 16)
internal fun ByteArray.put16(at: Int, v: Int) {
    this[at] = v.toByte(); this[at + 1] = (v shr 8).toByte()
}
internal fun ByteArray.put32(at: Int, v: Long) {
    put16(at, (v and 0xFFFF).toInt()); put16(at + 2, ((v shr 16) and 0xFFFF).toInt())
}

private val CRC_TABLE = LongArray(256) { n ->
    var c = n.toLong()
    repeat(8) { c = if (c and 1L != 0L) 0xEDB88320L xor (c ushr 1) else c ushr 1 }
    c
}

fun crc32(data: ByteArray): Long {
    var c = 0xFFFFFFFFL
    for (b in data) c = CRC_TABLE[((c xor b.toLong()) and 0xFF).toInt()] xor (c ushr 8)
    return c xor 0xFFFFFFFFL
}

/** Names without the UTF-8 flag are in IBM code page 437, by the ZIP specification. */
private fun decodeCp437(bytes: ByteArray): String {
    if (bytes.all { it >= 0 }) return bytes.decodeToString()
    return buildString { for (b in bytes) append(if (b >= 0) b.toInt().toChar() else CP437_HIGH[b.toInt() and 0x7F]) }
}

private const val CP437_HIGH =
    "ÇüéâäàåçêëèïîìÄÅÉæÆôöòûùÿÖÜ¢£¥₧ƒáíóúñÑªº¿⌐¬½¼¡«»░▒▓│┤╡╢╖╕╣║╗╝╜╛┐└┴┬├─┼╞╟╚╔╩╦╠═╬╧╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀αßΓπΣσµτΦΘΩδ∞φε∩≡±≥≤⌠⌡÷≈°∙·√ⁿ²■ "
