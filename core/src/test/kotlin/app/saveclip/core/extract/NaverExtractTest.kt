package app.saveclip.core.extract

import app.saveclip.core.UserMessages
import app.saveclip.core.detect.MediaKind
import app.saveclip.core.hls.ByteRange
import app.saveclip.core.http.SaveClipHttp
import app.saveclip.core.http.TextPeek
import app.saveclip.core.net.UrlResolver
import app.saveclip.core.policy.YouTubeBlockedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class NaverExtractTest {
    private val naverUrl =
        "https://m.naver.com/shorts/?seedMediaId=1A48E71FEEB364891FC1B932DE9C736D0478&serviceType=CLIP"

    @Test
    fun normalizesTheReportedShortsUrl() {
        assertEquals(naverUrl, UrlResolver.normalizeUserInput(naverUrl))
        val ref = NaverLinks.parse(naverUrl)
        assertEquals("1A48E71FEEB364891FC1B932DE9C736D0478", ref?.mediaId)
        assertEquals("CLIP", ref?.serviceType)
        assertEquals("SHORT_FORM", ref?.mediaType)
    }

    @Test
    fun recognizesShortLinkAndTvClipUrls() {
        assertTrue(NaverLinks.isShortLink("https://naver.me/xAb12"))
        assertEquals(
            "1A48E71FEEB364891FC1B932DE9C736D0478",
            NaverLinks.parse("https://tv.naver.com/clip/1A48E71FEEB364891FC1B932DE9C736D0478")?.mediaId,
        )
        assertEquals("NAVER_TV", NaverLinks.parse("https://tv.naver.com/v/29000000")?.serviceType)
        assertEquals("29000000", NaverLinks.parse("https://tv.naver.com/v/29000000")?.mediaId)
        assertEquals(
            "CLIP",
            NaverLinks.parse("https://clip.naver.com/contents?serviceType=CLIP&seedMediaId=1A48E71FEEB364891FC1B932DE9C736D0478")?.serviceType,
        )
        assertEquals(null, NaverLinks.parse("https://example.com/shorts/?seedMediaId=1A48E71FEEB364891FC1B932DE9C736D0478"))
    }

    @Test
    fun cardJsonYieldsProgressiveMp4AndSkipsPlaylists() {
        val parsed = ClipCardParser.parse(cardJson(clear = true), naverUrl)
        val found = parsed as CardParse.Found
        assertEquals(2, found.items.size)
        assertEquals(MediaKind.PROGRESSIVE_MP4, found.items[0].kind)
        assertTrue(found.items[0].url.endsWith(".mp4") || found.items[0].url.contains(".mp4?"))
        assertTrue(found.items[0].label.startsWith("1080p"))
        assertTrue(found.items.all { it.kind == MediaKind.PROGRESSIVE_MP4 })
        assertTrue(found.items[0].title.contains("조카들이"))
    }

    @Test
    fun protectedCardIsRejectedEvenIfMp4IsPresent() {
        assertTrue(ClipCardParser.parse(cardJson(clear = false), naverUrl) is CardParse.Protected)
    }

    @Test
    fun brokenCardJsonDoesNotThrow() {
        listOf("", "{", "null", "[]", "<html></html>", "{not json").forEach { body ->
            val parsed = ClipCardParser.parse(body, naverUrl)
            assertTrue(parsed is CardParse.Invalid || parsed is CardParse.Empty)
        }
    }

    @Test
    fun reportedUrlAndGarbageInputsNeverThrow() = runBlocking {
        val extractor = PageMediaExtractor(ScriptFetcher { error("boom $it") })
        val inputs = listOf(
            naverUrl,
            "  $naverUrl  ",
            "",
            "   ",
            "not a url",
            "javascript:alert(1)",
            "file:///tmp/a.mp4",
            "https://example.com/%zz",
            "https://example.com/a|b",
            "https://naver.me/abc",
            "https://tv.naver.com/v/29000000",
            "https://www.youtube.com/watch?v=abc",
            "https://youtu.be/abc",
        )
        inputs.forEach { input ->
            try {
                val result = extractor.extract(input)
                assertTrue(result is ExtractResult.Rejected)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                fail("$input threw ${error.javaClass.simpleName}: ${error.message}")
            }
        }
    }

    @Test
    fun youtubeIsRejectedBeforeAnyFetch() = runBlocking {
        var calls = 0
        val extractor = PageMediaExtractor(ScriptFetcher {
            calls += 1
            error("network")
        })
        val result = extractor.extract("https://m.youtube.com/watch?v=abc")
        assertEquals(UserMessages.YOUTUBE, (result as ExtractResult.Rejected).message)
        assertEquals(0, calls)
    }

    @Test
    fun naverShortLinkFollowsRedirectThenReadsCard() = runBlocking {
        val extractor = PageMediaExtractor(object : TextFetcher {
            override suspend fun get(url: String, headers: Map<String, String>): String {
                assertTrue(url.contains("seedMediaId=1A48E71FEEB364891FC1B932DE9C736D0478"))
                assertTrue(url.contains("serviceType=CLIP"))
                return cardJson(clear = true)
            }

            override suspend fun resolve(url: String, headers: Map<String, String>): String = naverUrl

            override suspend fun peek(url: String, headers: Map<String, String>, maxChars: Int): TextPeek {
                error("html peek is not used for a clip url")
            }
        })
        val result = extractor.extract("https://naver.me/clipShare")
        val media = (result as ExtractResult.Media).items
        assertEquals(MediaKind.PROGRESSIVE_MP4, media.first().kind)
        assertTrue(media.first().url.contains("1080.mp4"))
    }

    @Test
    fun tvNumericClipWithoutPlaybackShowsNotFound() = runBlocking {
        val extractor = PageMediaExtractor(ScriptFetcher {
            """{"header":{"code":0},"body":{"card":{"content":{"vod":{}}}}}"""
        })
        val result = extractor.extract("https://tv.naver.com/v/29000000")
        assertEquals(UserMessages.NOT_FOUND, (result as ExtractResult.Rejected).message)
    }

    @Test
    fun naverTvFallsBackToClipService() = runBlocking {
        val extractor = PageMediaExtractor(object : TextFetcher {
            override suspend fun get(url: String, headers: Map<String, String>): String {
                return if (url.contains("serviceType=NAVER_TV")) {
                    """{"header":{"code":0},"body":{"card":{"content":{"vod":{}}}}}"""
                } else {
                    cardJson(clear = true)
                }
            }

            override suspend fun resolve(url: String, headers: Map<String, String>): String = url

            override suspend fun peek(url: String, headers: Map<String, String>, maxChars: Int): TextPeek {
                error("peek")
            }
        })
        val result = extractor.extract("https://tv.naver.com/clip/1A48E71FEEB364891FC1B932DE9C736D0478")
        assertTrue(result is ExtractResult.Media)
    }

    @Test
    fun htmlFinderIgnoresBadUrlsAndYouTube() {
        val html = """
            <html><head>
            <meta property="og:title" content="샘플">
            <meta property="og:video" content="%%">
            <meta property="og:video:url" content="https://www.youtube.com/watch?v=abc">
            </head><body>
            <video src="https://cdn.example.com/clip.mp4"></video>
            </body></html>
        """.trimIndent()
        val items = HtmlMediaFinder.find("https://example.com/watch", html)
        assertEquals(1, items.size)
        assertEquals("https://cdn.example.com/clip.mp4", items[0].url)
        assertEquals("샘플", items[0].title)
    }

    @Test
    fun cancellationIsNotSwallowed() = runBlocking {
        val extractor = PageMediaExtractor(ScriptFetcher { throw CancellationException("stop") })
        try {
            extractor.extract(naverUrl)
            fail("expected cancellation")
        } catch (error: CancellationException) {
            assertEquals("stop", error.message)
        }
    }

    @Test
    fun redirectToYouTubeIsRejected() = runBlocking {
        val extractor = PageMediaExtractor(object : TextFetcher {
            override suspend fun get(url: String, headers: Map<String, String>): String = error("get")
            override suspend fun resolve(url: String, headers: Map<String, String>): String {
                throw YouTubeBlockedException()
            }
            override suspend fun peek(url: String, headers: Map<String, String>, maxChars: Int): TextPeek = error("peek")
        })
        val result = extractor.extract("https://naver.me/to-youtube")
        assertEquals(UserMessages.YOUTUBE, (result as ExtractResult.Rejected).message)
    }

    @Test
    fun naverShortsUrlExtractsDownloadableMp4() {
        val http = SaveClipHttp()
        val extractor = PageMediaExtractor(SaveClipTextFetcher(http))
        val result = runBlocking { extractor.extract(naverUrl) }
        val item = (result as ExtractResult.Media).items.first()
        assertEquals(MediaKind.PROGRESSIVE_MP4, item.kind)
        assertTrue(item.url.contains(".mp4"))
        assertFalse(item.url.contains("youtube", ignoreCase = true))
        assertTrue(item.title.isNotBlank())
        val file = File.createTempFile("naver-head", ".mp4")
        try {
            runBlocking {
                http.download(
                    item.url,
                    file,
                    headers = mapOf("Referer" to naverUrl),
                    range = ByteRange(0, 31),
                )
            }
            val bytes = file.readBytes()
            assertTrue(bytes.size >= 12)
            assertEquals("ftyp", String(bytes, 4, 4, Charsets.ISO_8859_1))
        } finally {
            file.delete()
        }
    }

    private fun cardJson(clear: Boolean): String {
        val protection = if (clear) "" else """"ContentProtection":{"schemeIdUri":"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"},"""
        return """
            {
              "header": {"code": 0, "message": ""},
              "body": {
                "card": {
                  "content": {
                    "title": "",
                    "description": "조카들이 떼로 몰려왔네요.",
                    "vod": {
                      "playable": true,
                      "type": "PLAY_API_3_0",
                      "playback": {
                        $protection
                        "MPD": [{
                          "Period": [{
                            "AdaptationSet": [{
                              "@mimeType": "video/mp4",
                              "@nvod:m3u": "https://cdn.example/master.m3u8",
                              "Representation": [
                                {
                                  "@bandwidth": "2206000",
                                  "@width": "720",
                                  "@height": "1280",
                                  "@codecs": "avc1.4d401f,mp4a.40.2",
                                  "BaseURL": ["https://cdn.example/720.mp4?token=1"],
                                  "nvod:Label": [{"@kind": "resolution", "#text": "720"}]
                                },
                                {
                                  "@bandwidth": "4335000",
                                  "@width": "1080",
                                  "@height": "1920",
                                  "@codecs": "avc1.640028,mp4a.40.2",
                                  "BaseURL": ["https://cdn.example/1080.mp4?token=1"],
                                  "nvod:Label": [{"@kind": "resolution", "#text": "1080"}]
                                },
                                {
                                  "@bandwidth": "128000",
                                  "@codecs": "mp4a.40.2",
                                  "BaseURL": ["https://cdn.example/audio.m4a"]
                                }
                              ]
                            }]
                          }]
                        }]
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()
    }
}

private class ScriptFetcher(
    private val body: (String) -> String,
) : TextFetcher {
    override suspend fun get(url: String, headers: Map<String, String>): String = body(url)

    override suspend fun resolve(url: String, headers: Map<String, String>): String = body(url)

    override suspend fun peek(url: String, headers: Map<String, String>, maxChars: Int): TextPeek {
        return TextPeek(url, "text/html", body(url))
    }
}
