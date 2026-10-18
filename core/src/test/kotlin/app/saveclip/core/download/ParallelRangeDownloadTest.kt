package app.saveclip.core.download

import app.saveclip.core.BrowserProfile
import app.saveclip.core.hls.ByteRange
import app.saveclip.core.http.SaveClipHttp
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
import java.util.concurrent.CopyOnWriteArrayList

class ParallelRangeDownloadTest {
    private val headers = mapOf("User-Agent" to BrowserProfile.USER_AGENT)

    @Test
    fun splitCoversTheFileWithoutGaps() {
        val parts = RangeSplit.parts(10_000, 3)
        assertEquals(3, parts.size)
        assertEquals(0L, parts.first().start)
        assertEquals(9_999L, parts.last().endInclusive)
        for (index in 1 until parts.size) {
            assertEquals(parts[index - 1].endInclusive + 1, parts[index].start)
        }
        assertEquals(parts.sumOf { it.length }, 10_000L)
        val roundTrip = SlicePlan.decode(SlicePlan(10_000, parts).encode())
        assertEquals(parts, roundTrip?.ranges)
    }

    @Test
    fun fileConnectionsStayBetweenThreeAndSix() {
        assertEquals(3, ParallelRangeDownloader.fileConnections(1))
        assertEquals(3, ParallelRangeDownloader.fileConnections(3))
        assertEquals(6, ParallelRangeDownloader.fileConnections(6))
        assertEquals(6, ParallelRangeDownloader.fileConnections(9))
    }

    @Test
    fun parallelRangesAssembleAndResume() = runBlocking {
        val body = ByteArray(3_000) { (it % 251).toByte() }
        val ranges = CopyOnWriteArrayList<String>()
        val server = rangedServer(body, honorRange = true, seen = ranges)
        server.start()
        val dir = createTempDir(prefix = "saveclip-parallel")
        try {
            val dest = File(dir, "movie.mp4")
            val downloader = ParallelRangeDownloader(SaveClipHttp())
            downloader.download(
                url = server.url("/movie.mp4").toString(),
                dest = dest,
                headers = headers,
                connections = 3,
                minBytes = 1,
            )
            assertArrayEquals(body, dest.readBytes())
            val sliceRanges = ranges.filter { it != "bytes=0-1023" && it.startsWith("bytes=") }
            assertTrue(sliceRanges.size >= 3)

            val again = File(dir, "again.mp4")
            val plan = SlicePlan(body.size.toLong(), RangeSplit.parts(body.size.toLong(), 3))
            File(dir, "slices.plan").writeText(plan.encode())
            File(dir, "slice-0.bin").writeBytes(slice(body, plan.ranges[0]))
            File(dir, "slice-1.bin.part").writeBytes(slice(body, plan.ranges[1]).copyOf(20))
            ranges.clear()
            downloader.download(
                url = server.url("/movie.mp4").toString(),
                dest = again,
                headers = headers,
                connections = 3,
                minBytes = 1,
            )
            assertArrayEquals(body, again.readBytes())
            assertTrue(ranges.any { it.startsWith("bytes=${plan.ranges[1].start + 20}-") })
        } finally {
            server.shutdown()
            dir.deleteRecursively()
        }
    }

    @Test
    fun fullResponseFallsBackToOneConnection() = runBlocking {
        val body = ByteArray(2_500) { (it % 97).toByte() }
        val server = rangedServer(body, honorRange = false, seen = CopyOnWriteArrayList())
        server.start()
        val dir = createTempDir(prefix = "saveclip-fallback")
        try {
            val dest = File(dir, "movie.mp4")
            ParallelRangeDownloader(SaveClipHttp()).download(
                url = server.url("/movie.mp4").toString(),
                dest = dest,
                headers = headers,
                connections = 4,
                minBytes = 1,
            )
            assertArrayEquals(body, dest.readBytes())
        } finally {
            server.shutdown()
            dir.deleteRecursively()
        }
    }

    @Test
    fun userInitiatedJobsStartAtAndroid14() {
        assertEquals(false, TransferPolicy.userInitiatedJob(33))
        assertEquals(true, TransferPolicy.userInitiatedJob(34))
        assertEquals(true, TransferPolicy.userInitiatedJob(35))
    }

    private fun slice(body: ByteArray, range: ByteRange): ByteArray {
        return body.copyOfRange(range.start.toInt(), range.endInclusive.toInt() + 1)
    }

    private fun rangedServer(
        body: ByteArray,
        honorRange: Boolean,
        seen: CopyOnWriteArrayList<String>,
    ): MockWebServer {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                if (range != null) seen += range
                if (request.method == "HEAD") {
                    val head = MockResponse()
                        .setResponseCode(200)
                        .addHeader("Content-Type", "video/mp4")
                        .addHeader("Content-Length", body.size)
                    if (honorRange) head.addHeader("Accept-Ranges", "bytes")
                    return head
                }
                if (honorRange && range != null) {
                    val start = range.substringAfter("bytes=").substringBefore("-").toInt()
                    val endRaw = range.substringAfter("-")
                    val end = if (endRaw.isBlank()) body.size - 1 else endRaw.toInt()
                    val slice = body.copyOfRange(start, end + 1)
                    return MockResponse()
                        .setResponseCode(206)
                        .addHeader("Content-Type", "video/mp4")
                        .addHeader("Content-Range", "bytes $start-$end/${body.size}")
                        .setBody(Buffer().write(slice))
                }
                return MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "video/mp4")
                    .addHeader("Content-Length", body.size)
                    .setBody(Buffer().write(body))
            }
        }
        return server
    }
}
