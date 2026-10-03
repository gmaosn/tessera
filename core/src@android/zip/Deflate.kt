package tessera.zip

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

actual fun inflate(data: ByteArray, uncompressedSize: Int): ByteArray {
    val inflater = Inflater(true)
    try {
        inflater.setInput(data)
        val out = ByteArray(uncompressedSize)
        var n = 0
        while (n < out.size) {
            val k = inflater.inflate(out, n, out.size - n)
            if (k == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break
            n += k
        }
        if (n != out.size) throw ZipException("truncated compressed data")
        return out
    } finally {
        inflater.end()
    }
}

actual fun deflate(data: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
    try {
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size / 2 + 64)
        val buffer = ByteArray(64 * 1024)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        return out.toByteArray()
    } finally {
        deflater.end()
    }
}
