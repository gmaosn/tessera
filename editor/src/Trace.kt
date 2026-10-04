package tessera.editor

/**
 * Diagnostic trace, off unless a host sets [sink] (the desktop app does with TESSERA_TRACE=1).
 * Records what reaches the editor, to understand a problem seen on someone else's screen.
 */
object Trace {
    var sink: ((String) -> Unit)? = null

    fun log(message: () -> String) {
        sink?.invoke(message())
    }
}
