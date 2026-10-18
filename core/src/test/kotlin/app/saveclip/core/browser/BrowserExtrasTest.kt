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
    fun defaultShortcutsNeverIncludeYouTube() {
        assertTrue(HomeShortcuts.defaults.isNotEmpty())
        HomeShortcuts.defaults.forEach { shortcut ->
            assertFalse(shortcut.label, YouTubeBlocklist.isBlocked(shortcut.url))
            assertEquals(shortcut.url, HomeShortcuts.accepted(shortcut.url))
        }
        val filtered = HomeShortcuts.withoutBlocked(
            HomeShortcuts.defaults + Shortcut("막힘", "https://www.youtube.com/watch?v=abc"),
        )
        assertEquals(HomeShortcuts.defaults.size, filtered.size)
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
