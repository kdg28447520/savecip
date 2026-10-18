package app.saveclip.core.drm

import app.saveclip.core.UserMessages
import app.saveclip.core.dash.DashMpdParser
import app.saveclip.core.hls.HlsParseResult
import app.saveclip.core.hls.HlsPlaylistParser
import app.saveclip.core.policy.ProtectedMediaException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrmDetectorTest {
    @Test
    fun hlsKeyTagsExceptNoneAreProtected() {
        val clear = """
            #EXTM3U
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:4,
            a.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        assertFalse(DrmDetector.playlistIsProtected(clear))
        assertFalse((HlsPlaylistParser.parse(clear, "https://cdn.example/a.m3u8") as HlsParseResult.Media).playlist.encrypted)

        val sampleAes = """
            #EXTM3U
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://key",KEYFORMAT="com.apple.streamingkeydelivery"
            #EXTINF:4,
            a.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        assertTrue(DrmDetector.playlistIsProtected(sampleAes))
        assertTrue((HlsPlaylistParser.parse(sampleAes, "https://cdn.example/a.m3u8") as HlsParseResult.Media).playlist.encrypted)

        val aesThenNone = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://cdn.example/key"
            #EXTINF:4,
            a.ts
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:4,
            b.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        assertTrue(DrmDetector.playlistIsProtected(aesThenNone))

        val session = """
            #EXTM3U
            #EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI="skd://x"
            #EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=320x180
            v.m3u8
        """.trimIndent()
        assertTrue(DrmDetector.playlistIsProtected(session))
    }

    @Test
    fun dashContentProtectionIsProtected() {
        val widevine = """
            <MPD mediaPresentationDuration="PT4S" xmlns="urn:mpeg:dash:schema:mpd:2011">
              <Period>
                <AdaptationSet contentType="video" mimeType="video/mp4">
                  <ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"/>
                  <Representation id="v" bandwidth="1000" width="320" height="180"/>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        assertTrue(DrmDetector.mpdIsProtected(widevine))
        val manifest = DashMpdParser.parse(widevine, "https://cdn.example/drm.mpd")
        assertTrue(manifest.containsContentProtection)
        assertTrue(manifest.tracks.single().protected)

        val playReady = widevine.replace("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed", "9a04f079-9840-4286-ab92-e65be0885f95")
        assertTrue(DrmDetector.mpdIsProtected(playReady))

        val cenc = """
            <MPD mediaPresentationDuration="PT4S" xmlns="urn:mpeg:dash:schema:mpd:2011">
              <Period>
                <AdaptationSet contentType="video" mimeType="video/mp4">
                  <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc"/>
                  <Representation id="v" bandwidth="1000" width="160" height="90"/>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        assertTrue(DashMpdParser.parse(cenc, "https://cdn.example/cenc.mpd").containsContentProtection)

        val clear = """
            <MPD mediaPresentationDuration="PT4S" xmlns="urn:mpeg:dash:schema:mpd:2011">
              <Period>
                <AdaptationSet contentType="video" mimeType="video/mp4">
                  <Representation id="v" bandwidth="1000" width="160" height="90"/>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        assertFalse(DrmDetector.mpdIsProtected(clear))
    }

    @Test
    fun protectedMessageIsExact() {
        assertEquals("보호된 영상은 받을 수 없어요", ProtectedMediaException().message)
        assertEquals(UserMessages.PROTECTED, ProtectedMediaException().message)
    }
}
