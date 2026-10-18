package app.saveclip.crash

import android.content.Context
import app.saveclip.core.crash.CrashLogFormat
import java.io.File
import java.time.Instant

object CrashStore {
    private const val FILE_NAME = "last-crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(appContext, thread, error) }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            }
        }
    }

    fun read(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists() || file.length() == 0L) return null
        return runCatching { file.readText().take(16_000).ifBlank { null } }.getOrNull()
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val text = CrashLogFormat.format(thread.name, error, Instant.now().toString())
        context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE).use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
        }
    }
}
