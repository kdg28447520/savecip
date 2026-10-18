package app.saveclip.core.hls

import app.saveclip.core.quality.QualityPicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistParserTest {
    @Test
    fun parsesMasterWithQuotedCodecsAndRelativeUrls() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="Korean",DEFAULT=YES,URI="audio/main.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=246440,CODECS="avc1.42000d",RESOLUTION=320x184,NAME="240",AUDIO="aac"
            url_2/low.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2149280,CODECS="mp4a.40.2,avc1.64001f",RESOLUTION=1280x720,NAME="720"
            url_0/hd.m3u8
        """.trimIndent()
        val parsed = HlsPlaylistParser.parse(master, "https://cdn.example/x36xhzz/master.m3u8")
        val result = parsed as HlsParseResult.Master
        assertEquals(2, result.variants.size)
        val low = result.variants[0]
        assertEquals("https://cdn.example/x36xhzz/url_2/low.m3u8", low.url)
        assertEquals(246440, low.bandwidth)
        assertEquals(320, low.width)
        assertEquals(184, low.height)
        assertEquals("avc1.42000d", low.codecs)
        assertEquals("aac", low.audioGroup)
        assertEquals("mp4a.40.2,avc1.64001f", result.variants[1].codecs)
        assertEquals(720, result.variants[1].height)
        val audio = QualityPicker.audioRendition(low, result.renditions)
        assertEquals("https://cdn.example/x36xhzz/audio/main.m3u8", audio?.url)
        assertEquals(null, QualityPicker.audioRendition(result.variants[1], result.renditions))
        assertEquals(low.url, QualityPicker.lowestHls(result.variants).url)
        assertEquals(result.variants[1].url, QualityPicker.defaultHls(result.variants).url)
    }

    @Test
    fun parsesMediaPlaylistMapAndByteRange() {
        val media = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:4
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-MAP:URI="init.mp4",BYTERANGE="800@0"
            #EXTINF:4.0,
            #EXT-X-BYTERANGE:1000@100
            seg-1.m4s
            #EXTINF:4.0,
            #EXT-X-BYTERANGE:1200
            seg-2.m4s
            #EXT-X-ENDLIST
        """.trimIndent()
        val parsed = HlsPlaylistParser.parse(media, "https://cdn.example/vod/index.m3u8") as HlsParseResult.Media
        assertFalse(parsed.playlist.encrypted)
        assertTrue(parsed.playlist.hasEndList)
        assertEquals("https://cdn.example/vod/init.mp4", parsed.playlist.initializationUrl)
        assertEquals(0L, parsed.playlist.initializationRange?.start)
        assertEquals(799L, parsed.playlist.initializationRange?.endInclusive)
        assertEquals(2, parsed.playlist.segments.size)
        assertEquals("https://cdn.example/vod/seg-1.m4s", parsed.playlist.segments[0].url)
        assertEquals(100L, parsed.playlist.segments[0].byteRange?.start)
        assertEquals(1099L, parsed.playlist.segments[0].byteRange?.endInclusive)
        assertEquals(1100L, parsed.playlist.segments[1].byteRange?.start)
        assertEquals(2299L, parsed.playlist.segments[1].byteRange?.endInclusive)
    }
}
