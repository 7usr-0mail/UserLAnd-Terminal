package tech.ula

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Persistent, local-only session trace retained across service/UI failures. */
object LocalSessionTrace {
    private const val FILE_NAME = "terminal-session-trace.txt"
    private const val MAX_BYTES = 256 * 1024

    fun append(context: Context, message: String) {
        val line = "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())} $message\n"
        val targets = listOfNotNull(
                context.getExternalFilesDir(null)?.let { File(it, FILE_NAME) },
                File(context.filesDir, FILE_NAME)
        )
        targets.forEach { file -> try {
            file.parentFile?.mkdirs()
            if (file.exists() && file.length() > MAX_BYTES) file.writeText("[trace rotated]\n")
            file.appendText(line)
        } catch (_: Exception) {} }
    }
}
