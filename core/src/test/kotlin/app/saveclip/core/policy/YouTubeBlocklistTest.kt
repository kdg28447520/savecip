package app.saveclip.core.policy

import app.saveclip.core.UserMessages
import app.saveclip.core.download.DownloadMergePipeline
import app.saveclip.core.http.SaveClipHttp
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class YouTubeBlocklistTest {
    @Test
    fun blocksListedHostsAndSubdomains() {
        val blocked = listOf(
            "https://www.youtube.com/watch?v=abc",
            "https://m.youtube.com/watch?v=abc",
            "https://youtu.be/abc",
            "https://www.youtube-nocookie.com/embed/abc",
            "https://rr3---sn.googlevideo.com/videoplayback?id=1",
            "http://youtube.com/watch?v=abc",
            "https://music.youtube.com/watch?v=abc",
            "HTTPS://WWW.YOUTUBE.COM/watch?v=1",
            "https://user:pass@youtube.com/watch?v=1",
            "https://youtube.com./watch?v=1",
        )
        blocked.forEach { url ->
            assertTrue(url, YouTubeBlocklist.isBlocked(url))
        }
    }

    @Test
    fun allowsUnrelatedHosts() {
        val allowed = listOf(
            "https://example.com/youtube",
            "https://notyoutube.com/watch",
            "https://youtube.com.evil.example/watch",
            "https://www.google.com/search?q=youtube",
            "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
            "https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd",
        )
        allowed.forEach { url ->
            assertFalse(url, YouTubeBlocklist.isBlocked(url))
        }
    }

    @Test
    fun refusesYouTubeAndExecutablesBeforeAnyRequest() {
        val http = SaveClipHttp(OkHttpClient.Builder().addInterceptor {
            fail("network ${it.request().url}")
            it.proceed(it.request())
        }.build())
        val pipeline = DownloadMergePipeline(http)
        val dir = createTempDir(prefix = "saveclip-policy")
        try {
            assertPolicy(YouTubeBlockedException::class.java, UserMessages.YOUTUBE) {
                runBlocking {
                    pipeline.downloadDirect("https://www.youtube.com/watch?v=abc", File(dir, "a.mp4"))
                }
            }
            assertPolicy(YouTubeBlockedException::class.java, UserMessages.YOUTUBE) {
                runBlocking {
                    pipeline.downloadHls("https://youtu.be/abc", dir, maxMediaSegments = 1)
                }
            }
            assertPolicy(ExecutableBlockedException::class.java, UserMessages.EXECUTABLE) {
                runBlocking {
                    pipeline.downloadDirect("https://example.com/app.apk", File(dir, "a.apk"))
                }
            }
            assertTrue(ExecutableGuard.isBlockedUrl("https://example.com/get?file=setup.exe"))
            assertTrue(ExecutableGuard.isBlockedMime("application/vnd.android.package-archive"))
            assertFalse(ExecutableGuard.isBlockedUrl("https://example.com/clip.mp4"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun redirectToGooglevideoIsRefused() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "https://rr1.googlevideo.com/videoplayback?id=1"),
        )
        server.start()
        try {
            val http = SaveClipHttp()
            assertPolicy(YouTubeBlockedException::class.java, UserMessages.YOUTUBE) {
                runBlocking { http.getText(server.url("/clip.mp4").toString()) }
            }
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    private fun assertPolicy(type: Class<out Exception>, message: String, block: () -> Unit) {
        try {
            block()
            fail("expected ${type.simpleName}")
        } catch (error: Exception) {
            if (!type.isInstance(error)) throw error
            assertEquals(message, error.message)
        }
    }
}
