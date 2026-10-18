package app.saveclip.core.detect

import app.saveclip.core.http.SaveClipHttp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DetectionSheetTest {
    private val hashName = "544956cc01fca32c70a52a28875354f61bde6ed8.mp4"
    private val prefixedHash = "ol_f71f7e274039119685022d7150656048.mp4"

    @Test
    fun friendlyNameKeepsHashFilenameSecondary() {
        val named = DetectedNames.of(
            "뉴스 클립",
            2,
            "https://cdn.example/media/$hashName",
            "영상",
            "MP4",
        )
        assertEquals("MP4", named.typeLabel)
        assertEquals("뉴스 클립 2", named.primary)
        assertEquals(hashName, named.secondary)
        assertTrue(DetectedNames.isOpaque(prefixedHash))
        assertFalse(DetectedNames.isOpaque("playlist.txt"))
    }

    @Test
    fun blankPathStillHasTypeAndName() {
        val arranged = DetectionList.arrange(
            videos = listOf(
                VideoRowInput("https://cdn.example/watch/", MediaKind.PROGRESSIVE_MP4, ""),
                VideoRowInput("https://cdn.example", MediaKind.HLS, "   "),
            ),
            images = listOf(ImageRowInput("https://cdn.example/img/", "")),
            facts = emptyMap(),
        )
        val rows = arranged.main
        assertEquals(3, rows.size)
        rows.forEach { row ->
            assertTrue(row.typeLabel.isNotBlank())
            assertTrue(row.primary.isNotBlank())
        }
        assertEquals("MP4", rows[0].typeLabel)
        assertEquals("영상 1", rows[0].primary)
        assertNull(rows[0].secondary)
        assertEquals("HLS", rows[1].typeLabel)
        assertEquals("영상 2", rows[1].primary)
        assertEquals("사진", rows[2].typeLabel)
        assertEquals("사진 1", rows[2].primary)
    }

    @Test
    fun readableFileIsPrimaryOnlyWhenThePageHasNoTitle() {
        val titled = DetectedNames.of("페이지", 1, "https://cdn.example/clip.mp4", "영상", "MP4")
        assertEquals("페이지 1", titled.primary)
        assertEquals("clip.mp4", titled.secondary)
        val bare = DetectedNames.of("", 1, "https://cdn.example/clip.mp4", "영상", "MP4")
        assertEquals("clip.mp4", bare.primary)
        assertNull(bare.secondary)
    }

    @Test
    fun sortsLargestFirstAndCollapsesTinyFiles() {
        val videos = listOf(
            VideoRowInput("https://cdn.example/tiny.mp4", MediaKind.PROGRESSIVE_MP4, "페이지"),
            VideoRowInput("https://cdn.example/$hashName", MediaKind.PROGRESSIVE_MP4, "페이지"),
            VideoRowInput("https://cdn.example/playlist.txt", MediaKind.HLS, "페이지"),
            VideoRowInput("https://cdn.example/mid.mp4", MediaKind.WEBM, "페이지"),
        )
        val images = listOf(
            ImageRowInput("https://cdn.example/1652834595.webp", "페이지"),
            ImageRowInput("https://cdn.example/poster.webp", "페이지"),
        )
        val facts = mapOf(
            videos[0].url to MediaFacts(bytes = 500),
            videos[1].url to MediaFacts(bytes = 52_428_800),
            videos[3].url to MediaFacts(bytes = 2_000_000),
            images[0].url to MediaFacts(bytes = 80_000),
            images[1].url to MediaFacts(bytes = 5_000_000),
        )
        val arranged = DetectionList.arrange(videos, images, facts)
        assertEquals(
            listOf(videos[1].url, images[1].url, videos[3].url, videos[2].url),
            arranged.main.map { it.url },
        )
        assertEquals(listOf(images[0].url, videos[0].url), arranged.tiny.map { it.url })
        assertEquals(2, arranged.tinyCount)
        val lead = arranged.main.first()
        assertEquals("MP4", lead.typeLabel)
        assertEquals("페이지 2", lead.primary)
        assertEquals(hashName, lead.secondary)
        assertEquals("50.0 MB", lead.meta)
        assertTrue(arranged.main.all { it.typeLabel.isNotBlank() && it.primary.isNotBlank() })
        assertTrue(arranged.tiny.all { it.typeLabel.isNotBlank() && it.primary.isNotBlank() })
        assertTrue(arranged.main[2].pending.not())
        assertTrue(arranged.main[3].pending)
    }

    @Test
    fun oneMegabyteStaysWithTheMainList() {
        val url = "https://cdn.example/edge.mp4"
        val arranged = DetectionList.arrange(
            listOf(VideoRowInput(url, MediaKind.PROGRESSIVE_MP4, "페이지")),
            emptyList(),
            mapOf(url to MediaFacts(bytes = DetectionList.TINY_BYTES)),
        )
        assertEquals(listOf(url), arranged.main.map { it.url })
        assertTrue(arranged.tiny.isEmpty())
    }

    @Test
    fun hlsEstimateIsMarkedApproximate() {
        val bytes = StreamEstimate.bytes(10_400_000, 10.0)
        assertEquals(13_000_000L, bytes)
        assertEquals("약 12.4 MB", FactFormat.size(bytes, approximate = true))
        assertEquals("1920×1080", FactFormat.resolution(1920, 1080))
        assertEquals("3:05", FactFormat.duration(185_000))
        assertEquals("1:02:03", FactFormat.duration(3_723_000))
        assertNull(StreamEstimate.bytes(0, 10.0))
        assertNull(StreamEstimate.bytes(Long.MAX_VALUE, 1.0e20))
    }

    @Test
    fun dashEstimateUsesBandwidthAndDuration() {
        assertEquals(1_200_000L, StreamEstimate.bytesFromMillis(800_000, 12_000))
        assertEquals("약 1.1 MB", FactFormat.size(1_200_000, approximate = true))
        assertNull(StreamEstimate.bytesFromMillis(800_000, 0))
    }

    @Test
    fun mp4PrefixReadsMvhdAndTkhdWhenTheyFit() {
        val facts = Mp4Peek.read(sampleMp4())
        assertEquals(12_500L, facts.durationMs)
        assertEquals(1920, facts.width)
        assertEquals(1080, facts.height)
        assertEquals("1920×1080 · 0:12", FactFormat.line(facts))
        assertEquals(MediaFacts(), Mp4Peek.read(ByteArray(32)))
        assertEquals(MediaFacts(), Mp4Peek.read(sampleMp4().copyOf(24)))
    }

    @Test
    fun lookupUsesContentLengthCachesAndSkipsYouTube() = runBlocking {
        var calls = 0
        var seen: Map<String, String> = emptyMap()
        val sizer = DetectionSizer(
            sample = { _, headers ->
                calls += 1
                seen = headers
                ProbeSample(4_200_000, "video/mp4", ByteArray(0))
            },
            loadText = { _, _ -> error("playlist") },
        )
        val headers = mapOf(
            "User-Agent" to "SaveClip",
            "Referer" to "https://page.example/watch",
            "Cookie" to "sid=1",
        )
        val url = "https://cdn.example/movie.mp4"
        val first = sizer.lookup(url, SizeKind.PROGRESSIVE, headers)
        val second = sizer.lookup(url, SizeKind.PROGRESSIVE, headers)
        assertEquals(4_200_000L, first?.bytes)
        assertFalse(first?.approximate == true)
        assertEquals(first, second)
        assertEquals(1, calls)
        assertEquals("SaveClip", seen["User-Agent"])
        assertEquals("https://page.example/watch", seen["Referer"])
        assertEquals("sid=1", seen["Cookie"])
        assertNull(sizer.lookup("https://www.youtube.com/watch?v=abc", SizeKind.PROGRESSIVE, headers))
        assertNull(sizer.lookup("https://rr1---sn.googlevideo.com/videoplayback?id=1", SizeKind.HLS, headers))
        assertEquals(1, calls)
    }

    @Test
    fun oneHostAllowsTwoSizeProbes() = runBlocking {
        val inFlight = AtomicInteger(0)
        val max = AtomicInteger(0)
        val sizer = DetectionSizer(
            sample = { _, _ ->
                val now = inFlight.incrementAndGet()
                max.updateAndGet { current -> maxOf(current, now) }
                delay(40)
                inFlight.decrementAndGet()
                ProbeSample(10, "video/mp4", ByteArray(0))
            },
            loadText = { _, _ -> error("playlist") },
            maxPerHost = 2,
        )
        coroutineScope {
            (1..6).map { index ->
                async { sizer.lookup("https://cdn.example/v$index.mp4", SizeKind.PROGRESSIVE) }
            }.awaitAll()
        }
        assertTrue(max.get() in 1..2)
    }

    @Test
    fun hlsUsesChosenVariantBandwidthTimesDuration() = runBlocking {
        val masterUrl = "https://cdn.example/video/master.m3u8"
        val highUrl = "https://cdn.example/video/high.m3u8"
        val lowUrl = "https://cdn.example/video/low.m3u8"
        val fetched = mutableListOf<String>()
        val sizer = DetectionSizer(
            sample = { _, _ -> error("segments") },
            loadText = { url, headers ->
                fetched += url
                assertEquals("sid=1", headers["Cookie"])
                when (url) {
                    masterUrl -> """
                        #EXTM3U
                        #EXT-X-STREAM-INF:BANDWIDTH=10400000,RESOLUTION=1920x1080
                        high.m3u8
                        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
                        low.m3u8
                    """.trimIndent()
                    highUrl -> mediaPlaylist(10.0)
                    lowUrl -> mediaPlaylist(4.0)
                    else -> error(url)
                }
            },
        )
        val high = sizer.lookup(masterUrl, SizeKind.HLS, mapOf("Cookie" to "sid=1"), null)
        assertEquals(13_000_000L, high?.bytes)
        assertTrue(high?.approximate == true)
        assertEquals(1920, high?.width)
        assertEquals(1080, high?.height)
        assertEquals(10_000L, high?.durationMs)
        assertEquals("약 12.4 MB · 1920×1080 · 0:10", FactFormat.line(high))
        val low = sizer.lookup(masterUrl, SizeKind.HLS, mapOf("Cookie" to "sid=1"), lowUrl)
        assertEquals(400_000L, low?.bytes)
        assertEquals(360, low?.height)
        assertEquals(1, fetched.count { it == masterUrl })
        assertTrue(fetched.contains(highUrl))
        assertTrue(fetched.contains(lowUrl))
        assertTrue(fetched.none { it.endsWith(".ts") })
    }

    @Test
    fun dashEstimateComesFromTheLargestVideoTrack() = runBlocking {
        val manifest = "https://cdn.example/dash/manifest.mpd"
        var calls = 0
        val sizer = DetectionSizer(
            sample = { _, _ -> error("segments") },
            loadText = { _, _ ->
                calls += 1
                """
                    <MPD mediaPresentationDuration="PT12S" xmlns="urn:mpeg:dash:schema:mpd:2011">
                      <Period>
                        <AdaptationSet contentType="video" mimeType="video/mp4">
                          <Representation id="low" bandwidth="200000" width="320" height="180"/>
                          <Representation id="hi" bandwidth="800000" width="1280" height="720"/>
                        </AdaptationSet>
                      </Period>
                    </MPD>
                """.trimIndent()
            },
        )
        val facts = sizer.lookup(manifest, SizeKind.DASH)
        assertEquals(1_200_000L, facts?.bytes)
        assertTrue(facts?.approximate == true)
        assertEquals(1280, facts?.width)
        assertEquals(720, facts?.height)
        assertEquals(12_000L, facts?.durationMs)
        val chosen = sizer.lookup(manifest, SizeKind.DASH, variantId = "low")
        assertEquals(300_000L, chosen?.bytes)
        assertEquals(180, chosen?.height)
        assertEquals(1, calls)
    }

    @Test
    fun headContentLengthAndContentRangeTotal() = runBlocking {
        val ranged = MockWebServer()
        ranged.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "HEAD") {
                    return MockResponse()
                        .setResponseCode(200)
                        .addHeader("Content-Type", "video/mp4")
                        .addHeader("Content-Length", "12345678")
                        .addHeader("Accept-Ranges", "bytes")
                }
                return MockResponse().setResponseCode(500)
            }
        }
        ranged.start()
        val rangedOff = MockWebServer()
        rangedOff.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "HEAD") {
                    return MockResponse()
                        .setResponseCode(200)
                        .addHeader("Content-Type", "video/mp4")
                }
                assertEquals("bytes=0-1023", request.getHeader("Range"))
                return MockResponse()
                    .setResponseCode(206)
                    .addHeader("Content-Type", "video/mp4")
                    .addHeader("Content-Range", "bytes 0-3/9876543")
                    .addHeader("Content-Length", "4")
                    .setBody(Buffer().write(byteArrayOf(0, 0, 0, 24)))
            }
        }
        rangedOff.start()
        try {
            val http = SaveClipHttp()
            val headed = http.inspect(ranged.url("/movie.mp4").toString(), mapOf("Cookie" to "sid=1"))
            assertEquals(12_345_678L, headed.totalBytes)
            assertEquals(1, ranged.requestCount)
            val rangedSnapshot = http.inspect(rangedOff.url("/movie.mp4").toString())
            assertEquals(9_876_543L, rangedSnapshot.totalBytes)
            assertTrue(rangedSnapshot.rangesAccepted)
        } finally {
            ranged.shutdown()
            rangedOff.shutdown()
        }
    }

    private fun mediaPlaylist(vararg durations: Double): String {
        val body = durations.joinToString("\n") { "#EXTINF:$it,\nseg.ts" }
        return "#EXTM3U\n$body\n#EXT-X-ENDLIST\n"
    }

    private fun sampleMp4(): ByteArray {
        val ftyp = box("ftyp", "isom".toByteArray(Charsets.ISO_8859_1) + ByteArray(4))
        val mvhd = ByteArray(20)
        writeInt(mvhd, 12, 1000)
        writeInt(mvhd, 16, 12_500)
        val tkhd = ByteArray(84)
        writeInt(tkhd, 76, 1920 shl 16)
        writeInt(tkhd, 80, 1080 shl 16)
        val moov = box("moov", box("mvhd", mvhd) + box("trak", box("tkhd", tkhd)))
        return ftyp + moov
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        writeInt(out, 0, out.size)
        type.toByteArray(Charsets.ISO_8859_1).copyInto(out, 4)
        payload.copyInto(out, 8)
        return out
    }

    private fun writeInt(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value ushr 24).toByte()
        out[offset + 1] = (value ushr 16).toByte()
        out[offset + 2] = (value ushr 8).toByte()
        out[offset + 3] = value.toByte()
    }
}
