package app.saveclip.core.detect

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaClassifierProbeTest {
    @Test
    fun magicBytesClassifyHlsDashAndMp4() {
        assertEquals(MediaKind.HLS, MediaClassifier.sniff("#EXTM3U\n#EXTINF:1,\n".toByteArray()))
        assertEquals(MediaKind.HLS, MediaClassifier.sniff("\uFEFF\n#EXTM3U\n".toByteArray()))
        assertEquals(MediaKind.DASH, MediaClassifier.sniff("<MPD xmlns=\"urn:mpeg:dash\">".toByteArray()))
        assertEquals(
            MediaKind.DASH,
            MediaClassifier.sniff("<?xml version=\"1.0\"?><MPD profiles=\"urn:mpeg:dash\">".toByteArray()),
        )
        val mp4 = ByteArray(16)
        mp4[4] = 'f'.code.toByte()
        mp4[5] = 't'.code.toByte()
        mp4[6] = 'y'.code.toByte()
        mp4[7] = 'p'.code.toByte()
        assertEquals(MediaKind.PROGRESSIVE_MP4, MediaClassifier.sniff(mp4))
        assertNull(MediaClassifier.sniff("<html><body>no</body></html>".toByteArray()))
    }

    @Test
    fun genericTypeUsesBytesAndKnownTypeDoesNotNeedThem() {
        val mp4 = ByteArray(12)
        mp4[4] = 'f'.code.toByte()
        mp4[5] = 't'.code.toByte()
        mp4[6] = 'y'.code.toByte()
        mp4[7] = 'p'.code.toByte()
        assertEquals(
            MediaKind.PROGRESSIVE_MP4,
            MediaClassifier.classifyContent("application/octet-stream", mp4),
        )
        assertEquals(
            MediaKind.HLS,
            MediaClassifier.classifyContent("application/vnd.apple.mpegurl; charset=utf-8", ByteArray(0)),
        )
        assertEquals(MediaKind.DASH, MediaClassifier.classifyContent("application/dash+xml", ByteArray(0)))
        assertNull(MediaClassifier.classifyContent("application/vnd.android.package-archive", mp4))
    }

    @Test
    fun worthProbingLooksAtPathQueryAndAcceptOnly() {
        assertTrue(MediaClassifier.worthProbing("https://cdn.example/video/123?token=abc"))
        assertTrue(MediaClassifier.worthProbing("https://cdn.example/a/playlist?id=1"))
        assertTrue(MediaClassifier.worthProbing("https://cdn.example/opaque", "application/vnd.apple.mpegurl"))
        assertFalse(MediaClassifier.worthProbing("https://cdn.example/static/app.js"))
        assertFalse(MediaClassifier.worthProbing("https://cdn.example/clip.mp4"))
        assertFalse(MediaClassifier.worthProbing("https://video.example/page"))
        assertFalse(MediaClassifier.worthProbing("https://cdn.example/setup.apk"))
    }

    @Test
    fun probeCachesAndSkipsYouTube() = runBlocking {
        var calls = 0
        val probe = ExtensionlessProbe(
            fetch = { _, _ ->
                calls += 1
                ProbeFetch("application/octet-stream", "#EXTM3U\n".toByteArray())
            },
        )
        val url = "https://cdn.example/video/1?token=a"
        assertEquals(MediaKind.HLS, probe.classify(url))
        assertEquals(MediaKind.HLS, probe.classify(url))
        assertEquals(1, calls)
        assertNull(probe.classify("https://www.youtube.com/video/1"))
        assertEquals(1, calls)
    }

    @Test
    fun probeKeepsANegativeResult() = runBlocking {
        var calls = 0
        val probe = ExtensionlessProbe(
            fetch = { _, _ ->
                calls += 1
                ProbeFetch("text/html", "<html></html>".toByteArray())
            },
        )
        val url = "https://cdn.example/stream/item"
        assertNull(probe.classify(url))
        assertNull(probe.classify(url))
        assertEquals(1, calls)
    }

    @Test
    fun oneHostDoesNotRunUnlimitedProbes() = runBlocking {
        var active = 0
        var peak = 0
        val probe = ExtensionlessProbe(
            fetch = { _, _ ->
                active += 1
                peak = maxOf(peak, active)
                delay(40)
                active -= 1
                ProbeFetch("video/mp4", ByteArray(0))
            },
            maxPerHost = 2,
        )
        (1..6).map { index ->
            async { probe.classify("https://cdn.example/video/$index") }
        }.awaitAll()
        assertTrue(peak <= 2)
    }
}
