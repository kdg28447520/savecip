package app.saveclip.core.net

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlResolverTest {
    @Test
    fun resolvesMuxPlaylistAndSegment() {
        val master = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
        val media = UrlResolver.resolve(master, "url_2/193039199_mp4_h264_aac_ld_7.m3u8")
        assertEquals(
            "https://test-streams.mux.dev/x36xhzz/url_2/193039199_mp4_h264_aac_ld_7.m3u8",
            media,
        )
        assertEquals(
            "https://test-streams.mux.dev/x36xhzz/url_2/url_526/193039199_mp4_h264_aac_ld_7.ts",
            UrlResolver.resolve(media, "url_526/193039199_mp4_h264_aac_ld_7.ts"),
        )
    }

    @Test
    fun resolvesRootProtocolAbsoluteAndQueryBases() {
        assertEquals(
            "https://example.com/root.mp4",
            UrlResolver.resolve("https://example.com/a/b.m3u8", "/root.mp4"),
        )
        assertEquals(
            "https://cdn.example.com/x.mp4",
            UrlResolver.resolve("https://example.com/a/b.m3u8", "//cdn.example.com/x.mp4"),
        )
        assertEquals(
            "https://cdn.example.com/x.mp4",
            UrlResolver.resolve("https://example.com/a/b.m3u8", "https://cdn.example.com/x.mp4"),
        )
        assertEquals(
            "https://example.com/a/c.ts",
            UrlResolver.resolve("https://example.com/a/b.m3u8?token=1", "c.ts"),
        )
        assertEquals(
            "https://example.com/a/b.m3u8",
            UrlResolver.resolve("https://example.com/a/b.m3u8", "  "),
        )
        assertEquals(
            "https://example.com/a/my%20clip.ts",
            UrlResolver.resolve("https://example.com/a/b.m3u8", "my clip.ts"),
        )
    }

    @Test
    fun normalizesBareHostsAndRejectsOtherSchemes() {
        assertEquals("https://example.com/a", UrlResolver.normalizeUserInput("example.com/a"))
        assertEquals("http://example.com/a", UrlResolver.normalizeUserInput("http://example.com/a"))
        assertEquals(null, UrlResolver.normalizeUserInput("javascript:alert(1)"))
        assertEquals(null, UrlResolver.normalizeUserInput("file:///tmp/a.mp4"))
    }

    @Test
    fun resolvesDashBaseAgainstManifest() {
        val manifest = "https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd"
        val directory = UrlResolver.resolve(manifest, "./")
        assertEquals("https://dash.akamaized.net/akamai/bbb_30fps/", directory)
        assertEquals(
            "https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps_320x180_200k/bbb_30fps_320x180_200k_0.m4v",
            UrlResolver.resolve(directory, "bbb_30fps_320x180_200k/bbb_30fps_320x180_200k_0.m4v"),
        )
    }
}
