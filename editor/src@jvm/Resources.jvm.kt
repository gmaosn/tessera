package tessera.editor.enhance

actual fun loadResource(name: String): ByteArray? =
    RealEsrgan::class.java.classLoader?.getResourceAsStream(name)?.use { it.readBytes() }
