package tessera.editor.enhance

// Android gets the weights with its reader (as an asset); until then, no super-resolution there.
actual fun loadResource(name: String): ByteArray? =
    RealEsrgan::class.java.classLoader?.getResourceAsStream(name)?.use { it.readBytes() }
