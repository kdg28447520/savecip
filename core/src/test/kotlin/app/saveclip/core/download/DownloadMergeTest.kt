package app.saveclip.core.download

import app.saveclip.core.BrowserProfile
import app.saveclip.core.dash.DashMpdParser
import app.saveclip.core.hls.HlsParseResult
import app.saveclip.core.hls.HlsPlaylistParser
import app.saveclip.core.http.SaveClipHttp
import app.saveclip.core.merge.ContainerKind
import app.saveclip.core.merge.ContainerProbe
import app.saveclip.core.merge.SegmentJoiner
import app.saveclip.core.quality.QualityPicker
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class DownloadMergeTest {
    private val headers = mapOf("User-Agent" to BrowserProfile.USER_AGENT)

    @Test
    fun rangeResumeAppendsPartialFile() {
        val body = ByteArray(64) { (it + 1).toByte() }
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .addHeader("Content-Range", "bytes 16-63/64")
                .setBody(Buffer().write(body.copyOfRange(16, 64))),
        )
        server.start()
        val dir = createTempDir(prefix = "saveclip-range")
        try {
            val dest = File(dir, "movie.mp4")
            File(dir, "movie.mp4.part").writeBytes(body.copyOfRange(0, 16))
            val http = SaveClipHttp()
            runBlocking { http.download(server.url("/movie.mp4").toString(), dest, headers) }
            val request = server.takeRequest()
            assertEquals("bytes=16-", request.getHeader("Range"))
            assertArrayEquals(body, dest.readBytes())
        } finally {
            server.shutdown()
            dir.deleteRecursively()
        }
    }

    @Test
    fun joinsTransportStreamSegmentsInOrder() = runBlocking {
        val server = MockWebServer()
        val packetA = tsPacket(1)
        val packetB = tsPacket(2)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("master.m3u8") -> MockResponse()
                        .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                        .setBody(
                            """
                            #EXTM3U
                            #EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=320x180,CODECS="avc1.42E00D,mp4a.40.2"
                            low.m3u8
                            """.trimIndent(),
                        )
                    path.contains("low.m3u8") -> MockResponse().setBody(
                        """
                        #EXTM3U
                        #EXT-X-TARGETDURATION:2
                        #EXTINF:2,
                        a.ts
                        #EXTINF:2,
                        b.ts
                        #EXT-X-ENDLIST
                        """.trimIndent(),
                    )
                    path.endsWith("a.ts") -> MockResponse().setBody(Buffer().write(packetA))
                    path.endsWith("b.ts") -> MockResponse().setBody(Buffer().write(packetB))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val dir = createTempDir(prefix = "saveclip-hls")
        try {
            val result = DownloadMergePipeline().downloadHls(
                playlistUrl = server.url("/master.m3u8").toString(),
                workDir = dir,
                headers = headers,
                parallelism = 2,
                maxMediaSegments = 2,
            )
            assertEquals(ContainerKind.MPEG_TS, result.container)
            assertTrue(ContainerProbe.looksLikeTransportStream(result.primary, packets = 2))
            assertArrayEquals(packetA + packetB, result.primary.readBytes())
        } finally {
            server.shutdown()
            dir.deleteRecursively()
        }
    }

    @Test
    fun joinerKeepsByteOrder() {
        val dir = createTempDir(prefix = "saveclip-join")
        try {
            val first = File(dir, "a").apply { writeBytes(byteArrayOf(1)) }
            val second = File(dir, "b").apply { writeBytes(byteArrayOf(2, 3)) }
            val output = File(dir, "out")
            SegmentJoiner.join(listOf(first, second), output)
            assertArrayEquals(byteArrayOf(1, 2, 3), output.readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test(timeout = 180_000)
    fun publicMp4DownloadsAndResumes() = runBlocking {
        val dir = createTempDir(prefix = "saveclip-mp4")
        try {
            val full = File(dir, "full.mp4")
            DownloadMergePipeline().downloadDirect(SampleStreams.MP4, full, headers)
            assertTrue(ContainerProbe.hasBox(full, "ftyp"))
            assertTrue(ContainerProbe.hasBox(full, "moov"))
            assertTrue(full.length() > 100_000)
            val resume = File(dir, "resume.mp4")
            File(dir, "resume.mp4.part").writeBytes(full.readBytes().copyOf(100_000))
            DownloadMergePipeline().downloadDirect(SampleStreams.MP4, resume, headers)
            assertEquals(sha256(full), sha256(resume))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test(timeout = 180_000)
    fun publicHlsParsesAndMergesAPrefix() = runBlocking {
        val http = SaveClipHttp()
        val masterText = http.getText(SampleStreams.HLS, headers)
        val master = HlsPlaylistParser.parse(masterText, SampleStreams.HLS) as HlsParseResult.Master
        val lowest = QualityPicker.lowestHls(master.variants)
        assertEquals(246440, lowest.bandwidth)
        assertEquals(184, lowest.height)
        assertEquals(
            "https://test-streams.mux.dev/x36xhzz/url_2/193039199_mp4_h264_aac_ld_7.m3u8",
            lowest.url,
        )
        val media = HlsPlaylistParser.parse(http.getText(lowest.url, headers), lowest.url) as HlsParseResult.Media
        assertTrue(media.playlist.segments.size > 10)
        assertEquals(
            "https://test-streams.mux.dev/x36xhzz/url_2/url_526/193039199_mp4_h264_aac_ld_7.ts",
            media.playlist.segments.first().url,
        )
        assertFalseEncrypted(media.playlist.encrypted)
        val dir = createTempDir(prefix = "saveclip-public-hls")
        try {
            val result = DownloadMergePipeline(http).downloadHls(
                playlistUrl = SampleStreams.HLS,
                workDir = dir,
                headers = headers,
                parallelism = 4,
                maxMediaSegments = 3,
                variantUrl = lowest.url,
            )
            assertEquals(ContainerKind.MPEG_TS, result.container)
            assertTrue(ContainerProbe.looksLikeTransportStream(result.primary))
            assertTrue(result.primary.length() > 100_000)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test(timeout = 180_000)
    fun publicDashParsesAndMergesAPrefix() = runBlocking {
        val manifest = DashMpdParser.parse(SaveClipHttp().getText(SampleStreams.DASH, headers), SampleStreams.DASH)
        assertEquals(634_566L, manifest.durationMillis)
        assertTrue(!manifest.containsContentProtection)
        val video = QualityPicker.lowestVideo(manifest.tracks)
        assertEquals("bbb_30fps_320x180_200k", video.id)
        assertEquals(159, video.mediaUrls.size)
        assertEquals(
            "https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps_320x180_200k/bbb_30fps_320x180_200k_0.m4v",
            video.initializationUrl,
        )
        assertEquals(
            "https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps_320x180_200k/bbb_30fps_320x180_200k_1.m4v",
            video.mediaUrls.first(),
        )
        val audio = QualityPicker.defaultAudio(manifest.tracks)
        assertEquals("bbb_a64k", audio?.id)
        assertEquals(159, audio?.mediaUrls?.size)
        val dir = createTempDir(prefix = "saveclip-public-dash")
        try {
            val result = DownloadMergePipeline().downloadDash(
                manifestUrl = SampleStreams.DASH,
                workDir = dir,
                headers = headers,
                parallelism = 4,
                maxMediaSegments = 3,
                videoRepresentationId = video.id,
                audioRepresentationId = audio?.id,
            )
            assertEquals(ContainerKind.FRAGMENTED_MP4, result.container)
            assertTrue(ContainerProbe.hasBox(result.video, "ftyp"))
            assertTrue(ContainerProbe.hasBox(result.video, "moov"))
            assertTrue(ContainerProbe.hasBox(result.video, "moof"))
            assertTrue(ContainerProbe.hasBox(result.video, "mdat"))
            val audioFile = result.audio ?: error("audio")
            assertTrue(ContainerProbe.hasBox(audioFile, "ftyp"))
            assertTrue(audioFile.length() > 1000)
            assertTrue(result.video.length() > 10_000)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun assertFalseEncrypted(encrypted: Boolean) {
        assertTrue(!encrypted)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun tsPacket(marker: Byte): ByteArray = ByteArray(188).also {
        it[0] = 0x47
        it[1] = marker
    }
}

object SampleStreams {
    const val HLS = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
    const val DASH = "https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd"
    const val MP4 = "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_1MB.mp4"
}
