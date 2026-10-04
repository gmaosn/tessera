package tessera.editor

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

/** Beyond this, decoding would take seconds and hundreds of megabytes: such an image is refused. */
private const val MAX_PIXELS = 80_000_000L

private fun tooLarge(bytes: ByteArray): Boolean = runCatching {
    org.jetbrains.skia.Codec.makeFromData(org.jetbrains.skia.Data.makeFromBytes(bytes)).use { it.width.toLong() * it.height > MAX_PIXELS }
}.getOrDefault(false)

actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    if (tooLarge(bytes)) null else runCatching { Image.makeFromEncoded(bytes).use { it.toComposeImageBitmap() } }.getOrNull()

actual fun decodeThumbnail(bytes: ByteArray, width: Int): ImageBitmap? = if (tooLarge(bytes)) null else runCatching {
    Image.makeFromEncoded(bytes).use { image ->
        if (image.width <= width) return@use image.toComposeImageBitmap()
        val height = maxOf(1, image.height * width / image.width)
        Surface.makeRasterN32Premul(width, height).use { surface ->
            surface.canvas.drawImageRect(
                image, Rect.makeWH(image.width.toFloat(), image.height.toFloat()), Rect.makeWH(width.toFloat(), height.toFloat()),
                FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR), null, true,
            )
            surface.makeImageSnapshot().use { it.toComposeImageBitmap() }
        }
    }
}.getOrNull()

actual fun imageFromArgb(pixels: IntArray, width: Int, height: Int): ImageBitmap {
    val bitmap = org.jetbrains.skia.Bitmap()
    bitmap.allocPixels(org.jetbrains.skia.ImageInfo.makeN32(width, height, org.jetbrains.skia.ColorAlphaType.OPAQUE))
    val bytes = ByteArray(pixels.size * 4)
    for (i in pixels.indices) {
        // N32 is BGRA in memory on every platform skiko supports.
        val p = pixels[i]
        bytes[i * 4] = p.toByte(); bytes[i * 4 + 1] = (p shr 8).toByte(); bytes[i * 4 + 2] = (p shr 16).toByte(); bytes[i * 4 + 3] = (p shr 24).toByte()
    }
    bitmap.installPixels(bytes)
    bitmap.setImmutable()
    return bitmap.asComposeImageBitmap()
}

