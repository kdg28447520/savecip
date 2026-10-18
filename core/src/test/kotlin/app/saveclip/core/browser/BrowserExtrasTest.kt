package app.saveclip.core.browser

import app.saveclip.core.BrowserProfile
import app.saveclip.core.policy.YouTubeBlocklist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserExtrasTest {
    @Test
    fun desktopUserAgentIsDistinctAndPlain() {
        assertTrue(BrowserProfile.USER_AGENT.contains("Mobile"))
        assertFalse(BrowserProfile.DESKTOP_USER_AGENT.contains("Mobile"))
        assertNotEquals(BrowserProfile.USER_AGENT, BrowserProfile.userAgent(desktopSite = true))
        assertEquals(BrowserProfile.USER_AGENT, BrowserProfile.userAgent(desktopSite = false))
        assertFalse(BrowserProfile.DESKTOP_USER_AGENT.contains("\\x"))
    }

    @Test
    fun defaultShortcutsAreEmpty() {
        assertTrue(HomeShortcuts.defaults.isEmpty())
        assertTrue(HomeShortcuts.retiredSeedUrls.none { YouTubeBlocklist.isBlocked(it) })
        val filtered = HomeShortcuts.withoutBlocked(
            listOf(
                Shortcut("직접 추가", "https://vimeo.com/"),
                Shortcut("막힘", "https://www.youtube.com/watch?v=abc"),
            ),
        )
        assertEquals(1, filtered.size)
        assertTrue(filtered.none { YouTubeBlocklist.isBlocked(it.url) })
    }

    @Test
    fun youtubeShortcutIsRejected() {
        assertNull(HomeShortcuts.accepted("https://youtu.be/abc"))
        assertNull(HomeShortcuts.accepted("https://m.youtube.com/watch?v=1"))
        assertNull(PageAddress.openable("not a url"))
        assertEquals("https://vimeo.com/1", PageAddress.openable("vimeo.com/1"))
    }

    @Test
    fun tabCapStopsAtEight() {
        assertTrue(TabLimits.canAdd(7))
        assertFalse(TabLimits.canAdd(8))
        assertFalse(TabLimits.canAdd(9))
    }
}
