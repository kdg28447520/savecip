package app.saveclip.core.detect

import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import kotlin.math.roundToLong

/**
 * What the detection sheet can show without downloading the file.
 * [approximate] is set when [bytes] is a bandwidth × duration estimate.
 */
data class MediaFacts(
    val bytes: Long? = null,
    val approximate: Boolean = false,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
)

data class MediaName(
    val typeLabel: String,
    val primary: String,
    val secondary: String?,
)

data class VideoRowInput(
    val url: String,
    val kind: MediaKind,
    val pageTitle: String,
)

data class ImageRowInput(
    val url: String,
    val pageTitle: String,
)

data class ListedMedia(
    val key: String,
    val url: String,
    val typeLabel: String,
    val primary: String,
    val secondary: String?,
    val meta: String?,
    val bytes: Long?,
    val pending: Boolean,
    val video: Boolean,
    val order: Int,
)

data class ArrangedMedia(
    val main: List<ListedMedia>,
    val tiny: List<ListedMedia>,
) {
    val tinyCount: Int get() = tiny.size
}

object DetectedNames {
    const val IMAGE = "사진"

    fun typeOf(kind: MediaKind): String = when (kind) {
        MediaKind.HLS -> "HLS"
        MediaKind.DASH -> "DASH"
        MediaKind.WEBM -> "WebM"
        MediaKind.PROGRESSIVE_MP4 -> "MP4"
    }

    fun of(pageTitle: String, index: Int, url: String, fallback: String, typeLabel: String): MediaName {
        val safeIndex = index.coerceAtLeast(1)
        val file = fileName(url)
        val title = pageTitle.replace(Regex("\\s+"), " ").trim()
        val safeType = typeLabel.ifBlank { if (fallback == IMAGE) IMAGE else "MP4" }
        val primary = when {
            title.isNotEmpty() -> "$title $safeIndex"
            file.isNotEmpty() && !isOpaque(file) -> file
            else -> "$fallback $safeIndex"
        }.ifBlank { "$fallback $safeIndex" }
        return MediaName(
            typeLabel = safeType,
            primary = primary,
            secondary = file.takeIf { it.isNotEmpty() && it != primary },
        )
    }

    fun fileName(url: String): String {
        val path = runCatching { URI(url.trim()).rawPath }.getOrNull().orEmpty()
        val segment = path.substringAfterLast('/')
        if (segment.isBlank() || segment == "." || segment == "..") return ""
        val decoded = runCatching { URLDecoder.decode(segment, Charsets.UTF_8.name()) }.getOrDefault(segment)
        return decoded.trim().trim('.')
    }

    /** Long hex-like ids (`5449…ed8.mp4`, `ol_f71f….mp4`) are not usable titles. */
    fun isOpaque(fileName: String): Boolean {
        val base = fileName.substringBeforeLast('.', fileName).ifBlank { fileName }
        if (base.length < 12) return false
        val hex = base.count { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        return hex >= 16 && hex.toDouble() / base.length >= 0.7
    }
}

object StreamEstimate {
    fun bytes(bandwidthBitsPerSecond: Long, durationSeconds: Double): Long? {
        if (bandwidthBitsPerSecond <= 0 || durationSeconds <= 0.0 || durationSeconds.isNaN()) return null
        val value = bandwidthBitsPerSecond.toDouble() * durationSeconds / 8.0
        if (!value.isFinite() || value <= 0.0 || value >= Long.MAX_VALUE.toDouble()) return null
        val rounded = value.roundToLong()
        return rounded.takeIf { it > 0L && it != Long.MAX_VALUE }
    }

    fun bytesFromMillis(bandwidthBitsPerSecond: Long, durationMillis: Long): Long? {
        if (durationMillis <= 0L) return null
        return bytes(bandwidthBitsPerSecond, durationMillis / 1000.0)
    }
}

object FactFormat {
    fun size(bytes: Long?, approximate: Boolean): String? {
        if (bytes == null || bytes < 0L) return null
        val body = formatByteCount(bytes)
        return if (approximate) "약 $body" else body
    }

    fun resolution(width: Int?, height: Int?): String? {
        if (width != null && height != null && width > 0 && height > 0) return "${width}×${height}"
        if (height != null && height > 0) return "${height}p"
        return null
    }

    fun duration(durationMs: Long?): String? {
        if (durationMs == null || durationMs <= 0L) return null
        val totalSeconds = durationMs / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    fun line(facts: MediaFacts?): String? {
        if (facts == null) return null
        val parts = listOfNotNull(
            size(facts.bytes, facts.approximate),
            resolution(facts.width, facts.height),
            duration(facts.durationMs),
        )
        return parts.joinToString(" · ").ifBlank { null }
    }

    fun formatByteCount(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }
}

object DetectionList {
    const val TINY_BYTES = 1_000_000L

    fun arrange(
        videos: List<VideoRowInput>,
        images: List<ImageRowInput>,
        facts: Map<String, MediaFacts>,
    ): ArrangedMedia {
        val rows = ArrayList<ListedMedia>(videos.size + images.size)
        videos.forEachIndexed { index, video ->
            rows += row(
                key = "v:" + video.url,
                url = video.url,
                name = DetectedNames.of(
                    video.pageTitle,
                    index + 1,
                    video.url,
                    "영상",
                    DetectedNames.typeOf(video.kind),
                ),
                facts = facts,
                order = index,
                video = true,
                fallbackType = "MP4",
                fallbackName = "영상 ${index + 1}",
            )
        }
        images.forEachIndexed { index, image ->
            rows += row(
                key = "i:" + image.url,
                url = image.url,
                name = DetectedNames.of(image.pageTitle, index + 1, image.url, "사진", DetectedNames.IMAGE),
                facts = facts,
                order = videos.size + index,
                video = false,
                fallbackType = "사진",
                fallbackName = "사진 ${index + 1}",
            )
        }
        val sorted = rows.sortedWith(
            compareByDescending<ListedMedia> { it.bytes != null }
                .thenByDescending { it.bytes ?: Long.MIN_VALUE }
                .thenBy { it.order },
        )
        return ArrangedMedia(
            main = sorted.filter { row ->
                val bytes = row.bytes
                bytes == null || bytes >= TINY_BYTES
            },
            tiny = sorted.filter { row ->
                val bytes = row.bytes
                bytes != null && bytes < TINY_BYTES
            },
        )
    }

    private fun row(
        key: String,
        url: String,
        name: MediaName,
        facts: Map<String, MediaFacts>,
        order: Int,
        video: Boolean,
        fallbackType: String,
        fallbackName: String,
    ): ListedMedia {
        val known = facts[url]
        return ListedMedia(
            key = key,
            url = url,
            typeLabel = name.typeLabel.ifBlank { fallbackType },
            primary = name.primary.ifBlank { fallbackName },
            secondary = name.secondary,
            meta = FactFormat.line(known),
            bytes = known?.bytes,
            pending = !facts.containsKey(url),
            video = video,
            order = order,
        )
    }
}

/**
 * Duration and picture size from an MP4 prefix, only when `mvhd` / `tkhd`
 * sit fully inside that prefix. A moov atom at the end of the file is left unknown.
 */
object Mp4Peek {
    private val containers = setOf("moov", "trak", "mdia", "minf", "stbl")
    private const val MAX_DIMENSION = 10_000
    private const val MAX_DURATION_MS = 10L * 24L * 60L * 60L * 1000L

    fun read(prefix: ByteArray): MediaFacts {
        if (!hasFtyp(prefix)) return MediaFacts()
        var durationMs: Long? = null
        var width: Int? = null
        var height: Int? = null
        var area = 0
        walk(prefix, 0, prefix.size, 0) { type, start, end ->
            when (type) {
                "mvhd" -> readMvhd(prefix, start, end)?.let { durationMs = it }
                "tkhd" -> {
                    val (w, h) = readTkhd(prefix, start, end)
                    if (w != null && h != null && w * h >= area) {
                        area = w * h
                        width = w
                        height = h
                    }
                }
            }
        }
        return MediaFacts(width = width, height = height, durationMs = durationMs)
    }

    private fun hasFtyp(prefix: ByteArray): Boolean {
        if (prefix.size < 8) return false
        return prefix[4] == 'f'.code.toByte() &&
            prefix[5] == 't'.code.toByte() &&
            prefix[6] == 'y'.code.toByte() &&
            prefix[7] == 'p'.code.toByte()
    }

    private fun walk(
        data: ByteArray,
        start: Int,
        end: Int,
        depth: Int,
        onBox: (type: String, payloadStart: Int, payloadEnd: Int) -> Unit,
    ) {
        if (depth > 8) return
        var offset = start
        val limit = minOf(end, data.size)
        while (offset + 8 <= limit) {
            val size32 = readU32(data, offset)
            val type = runCatching { String(data, offset + 4, 4, Charsets.ISO_8859_1) }.getOrNull() ?: break
            val header = if (size32 == 1L) 16 else 8
            if (header == 16 && offset + 16 > limit) break
            val boxSize = when {
                size32 == 1L -> readU64(data, offset + 8)
                size32 == 0L -> (limit - offset).toLong()
                else -> size32
            }
            if (boxSize < header || boxSize > Int.MAX_VALUE) break
            val boxEnd = offset + boxSize.toInt()
            if (boxEnd <= offset || boxEnd > limit) break
            val payloadStart = offset + header
            if (type in containers) {
                walk(data, payloadStart, boxEnd, depth + 1, onBox)
            } else {
                onBox(type, payloadStart, boxEnd)
            }
            offset = boxEnd
        }
    }

    private fun readMvhd(data: ByteArray, start: Int, end: Int): Long? {
        if (end - start < 20) return null
        val version = data[start].toInt() and 0xff
        val (timescale, duration) = if (version == 1) {
            if (end - start < 32) return null
            readU32(data, start + 20) to readU64(data, start + 24)
        } else {
            readU32(data, start + 12) to readU32(data, start + 16)
        }
        if (timescale <= 0L || duration < 0L) return null
        val millis = (duration.toDouble() * 1000.0 / timescale.toDouble()).roundToLong()
        if (millis <= 0L || millis > MAX_DURATION_MS) return null
        return millis
    }

    private fun readTkhd(data: ByteArray, start: Int, end: Int): Pair<Int?, Int?> {
        if (end - start < 4) return null to null
        val version = data[start].toInt() and 0xff
        val widthAt = if (version == 1) 88 else 76
        if (end - start < widthAt + 8) return null to null
        val width = fixed16(data, start + widthAt)
        val height = fixed16(data, start + widthAt + 4)
        if (width !in 1..MAX_DIMENSION || height !in 1..MAX_DIMENSION) return null to null
        return width to height
    }

    private fun fixed16(data: ByteArray, offset: Int): Int = (readU32(data, offset) shr 16).toInt()

    private fun readU32(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xff) shl 24) or
            ((data[offset + 1].toLong() and 0xff) shl 16) or
            ((data[offset + 2].toLong() and 0xff) shl 8) or
            (data[offset + 3].toLong() and 0xff)
    }

    private fun readU64(data: ByteArray, offset: Int): Long {
        val high = readU32(data, offset)
        val low = readU32(data, offset + 4)
        if (high != 0L) return Long.MAX_VALUE
        return low
    }
}
