package app.saveclip.core.browser

import app.saveclip.core.policy.YouTubeBlocklist
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostBlocklistTest {
    private val sample = HostBlocklist(
        HostListParser.parse(
            """
            # comment
            127.0.0.1 doubleclick.net
            0.0.0.0 google-analytics.com
            ||ads.example.net^
            localhost
            !hidden
            not a host with spaces
            """.trimIndent(),
        ),
    )

    @Test
    fun parsesHostsAndMatchesSubdomains() {
        assertTrue(sample.blocks("https://ad.doubleclick.net/pagead/js"))
        assertTrue(sample.blocks("https://www.google-analytics.com/collect"))
        assertTrue(sample.blocks("https://ads.example.net/pixel"))
        assertFalse(sample.blocks("https://example.com/video.mp4"))
        assertFalse(sample.hosts.contains("localhost"))
    }

    @Test
    fun toggleAndPerSiteException() {
        assertFalse(
            AdBlockDecision.shouldBlock(
                enabled = false,
                mainFrame = false,
                requestUrl = "https://ad.doubleclick.net/a",
                pageUrl = "https://news.example/a",
                list = sample,
                exceptions = emptySet(),
            ),
        )
        assertFalse(
            AdBlockDecision.shouldBlock(
                enabled = true,
                mainFrame = true,
                requestUrl = "https://ad.doubleclick.net/a",
                pageUrl = "https://news.example/a",
                list = sample,
                exceptions = emptySet(),
            ),
        )
        assertTrue(
            AdBlockDecision.shouldBlock(
                enabled = true,
                mainFrame = false,
                requestUrl = "https://ad.doubleclick.net/a",
                pageUrl = "https://news.example/a",
                list = sample,
                exceptions = emptySet(),
            ),
        )
        assertFalse(
            AdBlockDecision.shouldBlock(
                enabled = true,
                mainFrame = false,
                requestUrl = "https://ad.doubleclick.net/a",
                pageUrl = "https://www.news.example/story",
                list = sample,
                exceptions = setOf("news.example"),
            ),
        )
    }

    @Test
    fun exceptionDoesNotUnblockYouTube() {
        assertTrue(YouTubeBlocklist.isBlocked("https://www.youtube.com/watch?v=1"))
        assertFalse(
            AdBlockDecision.shouldBlock(
                enabled = true,
                mainFrame = false,
                requestUrl = "https://www.youtube.com/watch?v=1",
                pageUrl = "https://example.com/",
                list = sample,
                exceptions = setOf("youtube.com"),
            ),
        )
        assertTrue(YouTubeBlocklist.isBlocked("https://www.youtube.com/watch?v=1"))
    }

    @Test
    fun bundledListLoadsWithAttributionAndKnownHost() {
        val bundled = HostBlocklist.bundled()
        assertTrue(bundled.hosts.size > 100)
        assertTrue(bundled.blocks("https://ad.doubleclick.net/pagead"))
        val notice = HostBlocklist::class.java.getResourceAsStream("/adblock/NOTICE.txt")!!
            .bufferedReader()
            .use { it.readText() }
        assertTrue(notice.contains("Peter Lowe"))
        assertTrue(notice.contains("pgl.yoyo.org"))
        assertTrue(notice.contains("not EasyList"))
        assertFalse(bundled.hosts.contains("youtube.com"))
    }
}
