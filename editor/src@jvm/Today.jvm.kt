package tessera.editor

actual fun todayIso(): String = java.time.LocalDate.now().toString()
