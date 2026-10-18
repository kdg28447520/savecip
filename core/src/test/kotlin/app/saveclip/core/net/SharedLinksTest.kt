package app.saveclip.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedLinksTest {
    @Test
    fun pullsTheFirstHttpUrlOutOfSharedText() {
        assertEquals(
            "https://cdn.example/video/1",
            SharedLinks.firstHttpUrl("봐 봐 https://cdn.example/video/1 이야"),
        )
        assertEquals(
            "https://example.com/a",
            SharedLinks.firstHttpUrl("https://example.com/a."),
        )
        assertEquals("https://example.com/watch", SharedLinks.firstHttpUrl("example.com/watch"))
        assertNull(SharedLinks.firstHttpUrl("오늘은 링크가 없어요"))
        assertNull(SharedLinks.firstHttpUrl("   "))
        assertEquals(
            "https://youtu.be/abc",
            SharedLinks.firstHttpUrl("https://youtu.be/abc"),
        )
    }
}
