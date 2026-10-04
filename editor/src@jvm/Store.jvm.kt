package tessera.editor.enhance

import androidx.compose.ui.graphics.asSkiaBitmap
import tessera.editor.decodeImage
import tessera.editor.imageFromArgb

actual fun encodeJpeg(image: Argb, quality: Int): ByteArray {
    val bitmap = imageFromArgb(image.pixels, image.width, image.height).asSkiaBitmap()
    return org.jetbrains.skia.Image.makeFromBitmap(bitmap).use { it.encodeToData(org.jetbrains.skia.EncodedImageFormat.JPEG, quality)!!.bytes }
}

actual fun decodeArgb(bytes: ByteArray): Argb? {
    val bmp = decodeImage(bytes) ?: return null
    val px = IntArray(bmp.width * bmp.height)
    bmp.readPixels(px)
    return Argb(bmp.width, bmp.height, px)
}
