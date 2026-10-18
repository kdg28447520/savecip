package app.saveclip.core.download

import app.saveclip.core.hls.ByteRange
import app.saveclip.core.http.RangeRejectedException
import app.saveclip.core.http.SaveClipHttp
import app.saveclip.core.merge.SegmentJoiner
import app.saveclip.core.policy.DownloadGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

data class SlicePlan(
    val total: Long,
    val ranges: List<ByteRange>,
) {
    fun encode(): String {
        val body = ranges.joinToString("\n") { "${it.start}-${it.endInclusive}" }
        return "v1\n$total\n$body\n"
    }

    companion object {
        fun decode(text: String): SlicePlan? {
            val lines = text.replace("\r\n", "\n").replace('\r', '\n').lines().filter { it.isNotBlank() }
            if (lines.size < 2 || lines.first() != "v1") return null
            val total = lines[1].toLongOrNull() ?: return null
            if (total <= 0L) return null
            val ranges = lines.drop(2).map { line ->
                val start = line.substringBefore('-').toLongOrNull() ?: return null
                val end = line.substringAfter('-').toLongOrNull() ?: return null
                if (end < start) return null
                ByteRange(start, end)
            }
            if (ranges.isEmpty()) return null
            if (ranges.first().start != 0L || ranges.last().endInclusive != total - 1) return null
            for (index in 1 until ranges.size) {
                if (ranges[index].start != ranges[index - 1].endInclusive + 1) return null
            }
            return SlicePlan(total, ranges)
        }
    }
}

object RangeSplit {
    fun parts(total: Long, connections: Int): List<ByteRange> {
        require(total > 0L)
        val count = connections.coerceIn(1, 6)
        if (count == 1 || total < count) return listOf(ByteRange(0, total - 1))
        val base = total / count
        val ranges = ArrayList<ByteRange>(count)
        var start = 0L
        for (index in 0 until count) {
            val end = if (index == count - 1) total - 1 else start + base - 1
            ranges += ByteRange(start, end)
            start = end + 1
        }
        return ranges
    }
}

/**
 * Splits one large file into 3–6 Range requests. A server that answers 200
 * instead of 206 is downloaded again on a single connection. Slice files and
 * a plan on disk keep a later resume on the same boundaries.
 */
class ParallelRangeDownloader(
    private val http: SaveClipHttp = SaveClipHttp(),
) {
    suspend fun download(
        url: String,
        dest: File,
        headers: Map<String, String> = emptyMap(),
        connections: Int = 4,
        minBytes: Long = MIN_BYTES,
        progress: (done: Long, total: Long?) -> Unit = { _, _ -> },
    ): File {
        DownloadGuard.checkUrl(url)
        dest.parentFile?.mkdirs()
        val singlePartial = File(dest.parentFile, dest.name + ".part")
        if (singlePartial.exists() && singlePartial.length() > 0L) {
            http.download(url, dest, headers) { done, total -> progress(done, total) }
            return dest
        }
        if (dest.exists() && dest.length() > 0L) {
            progress(dest.length(), dest.length())
            return dest
        }
        val snapshot = http.inspect(url, headers)
        val total = snapshot.totalBytes
        val count = fileConnections(connections)
        if (!snapshot.rangesAccepted || total == null || total < minBytes) {
            http.download(url, dest, headers) { done, length -> progress(done, length) }
            return dest
        }
        val dir = dest.parentFile ?: dest.absoluteFile.parentFile ?: error("저장 경로가 없습니다")
        val plan = loadPlan(dir, total, count)
        try {
            downloadSlices(url, dir, headers, plan, progress)
        } catch (rejected: RangeRejectedException) {
            clearSlices(dir, plan.ranges.size)
            http.download(url, dest, headers) { done, length -> progress(done, length) }
            return dest
        }
        val parts = (0 until plan.ranges.size).map { sliceFile(dir, it) }
        val complete = parts.zip(plan.ranges).all { (file, range) -> file.length() == range.length }
        if (!complete) throw IOException("조각을 받지 못했습니다")
        SegmentJoiner.join(parts, dest)
        if (dest.length() != total) {
            dest.delete()
            throw IOException("받은 길이가 다릅니다")
        }
        clearSlices(dir, plan.ranges.size)
        progress(dest.length(), total)
        return dest
    }

    private suspend fun downloadSlices(
        url: String,
        dir: File,
        headers: Map<String, String>,
        plan: SlicePlan,
        progress: (done: Long, total: Long?) -> Unit,
    ) {
        val perSlice = ConcurrentHashMap<Int, Long>()
        fun publish() {
            progress(perSlice.values.sum(), plan.total)
        }
        coroutineScope {
            plan.ranges.mapIndexed { index, range ->
                async(Dispatchers.IO) {
                    coroutineContext.ensureActive()
                    val file = sliceFile(dir, index)
                    http.download(url, file, headers, range, strictRange = true) { done, _ ->
                        perSlice[index] = done
                        publish()
                    }
                    perSlice[index] = file.length()
                    publish()
                }
            }.awaitAll()
        }
    }

    private fun loadPlan(dir: File, total: Long, connections: Int): SlicePlan {
        val file = File(dir, PLAN_NAME)
        val saved = file.takeIf { it.isFile }?.let { SlicePlan.decode(it.readText()) }
        if (saved != null && saved.total == total) return saved
        clearSlices(dir, 8)
        val created = SlicePlan(total, RangeSplit.parts(total, connections))
        file.writeText(created.encode())
        return created
    }

    private fun clearSlices(dir: File, count: Int) {
        File(dir, PLAN_NAME).delete()
        for (index in 0 until count) {
            sliceFile(dir, index).delete()
            File(dir, sliceFile(dir, index).name + ".part").delete()
        }
    }

    private fun sliceFile(dir: File, index: Int): File = File(dir, "slice-$index.bin")

    companion object {
        const val MIN_BYTES = 4L * 1024 * 1024
        private const val PLAN_NAME = "slices.plan"

        fun fileConnections(setting: Int): Int = setting.coerceIn(3, 6)
    }
}
