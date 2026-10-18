package app.saveclip.core.crash

import java.io.PrintWriter
import java.io.StringWriter

object CrashLogFormat {
    fun format(threadName: String, error: Throwable, at: String): String {
        val trace = StringWriter()
        error.printStackTrace(PrintWriter(trace))
        return buildString {
            appendLine("세이브클립 오류 기록")
            appendLine("시각: $at")
            appendLine("스레드: $threadName")
            appendLine(trace.toString().take(12_000))
        }
    }
}
