package app.saveclip.core.crash

import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogFormatTest {
    @Test
    fun includesThreadMessageAndCause() {
        val root = IllegalStateException("페이지 주소를 읽지 못했어요")
        val error = RuntimeException("분석 중 오류", root)
        val text = CrashLogFormat.format("main", error, "2026-10-18T00:00:00Z")
        assertTrue(text.contains("세이브클립 오류 기록"))
        assertTrue(text.contains("main"))
        assertTrue(text.contains("분석 중 오류"))
        assertTrue(text.contains("페이지 주소를 읽지 못했어요"))
        assertTrue(text.contains("2026-10-18T00:00:00Z"))
    }
}
