package app.saveclip.core.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaRequestGateTest {
    private val page =
        "https://m.naver.com/shorts/?seedMediaId=1A48E71FEEB364891FC1B932DE9C736D0478&serviceType=CLIP"

    @Test
    fun naverScriptRequestCrashesWhenTheOldCodeReadsWebViewUrl() {
        val script = "https://m.naver.com/shorts/assets/main.js"
        val error = runCatching {
            legacyIntercept(script, mainFrame = false) {
                throw IllegalStateException(
                    "A WebView method was called on thread 'Chrome_IOThread'. " +
                        "All WebView methods must be called on the same thread.",
                )
            }
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("WebView"))
    }

    @Test
    fun fixedGateDoesNotNeedWebViewAndAcceptsTheSameRequest() {
        val script = "https://m.naver.com/shorts/assets/main.js"
        assertEquals(RequestDecision.Allow, MediaRequestGate.inspect(script, page, mainFrame = false))
        val media = MediaRequestGate.inspect("https://cdn.example/clip.mp4", page, mainFrame = false)
        assertTrue(media is RequestDecision.Observe)
        assertEquals(page, (media as RequestDecision.Observe).pageUrl)
    }

    @Test
    fun extensionlessMediaCandidateIsProbedAndYouTubeStaysBlocked() {
        val candidate = MediaRequestGate.inspect(
            "https://cdn.example/video/123?token=abc",
            page,
            mainFrame = false,
        )
        assertTrue(candidate is RequestDecision.Probe)
        assertEquals(page, (candidate as RequestDecision.Probe).pageUrl)
        val byAccept = MediaRequestGate.inspect(
            "https://cdn.example/opaque/id",
            page,
            mainFrame = false,
            accept = "application/vnd.apple.mpegurl",
        )
        assertTrue(byAccept is RequestDecision.Probe)
        val script = MediaRequestGate.inspect("https://m.naver.com/shorts/assets/main.js", page, false)
        assertEquals(RequestDecision.Allow, script)
        val youtube = MediaRequestGate.inspect("https://youtu.be/abc", page, false)
        assertTrue(youtube is RequestDecision.Block && youtube.youtube)
    }

    @Test
    fun intentAndYouTubeAndGarbageDoNotThrow() {
        assertTrue(MediaRequestGate.inspect("intent://open#Intent;scheme=naver;end", page, false) is RequestDecision.Block)
        val youtube = MediaRequestGate.inspect("https://www.youtube.com/watch?v=abc", page, true)
        assertTrue(youtube is RequestDecision.Block && youtube.youtube)
        assertEquals(RequestDecision.Allow, MediaRequestGate.inspect("https://example.com/%zz", page, false))
        assertEquals(RequestDecision.Allow, MediaRequestGate.inspect("", page, false))
        assertTrue(
            MediaRequestGate.inspect("https://example.com/setup.apk", page, false) is RequestDecision.Block,
        )
    }

    private fun legacyIntercept(url: String, mainFrame: Boolean, webViewUrl: () -> String) {
        if (!url.startsWith("http")) return
        if (!mainFrame) {
            val pageUrl = webViewUrl()
            check(pageUrl.isNotEmpty())
        }
    }
}
