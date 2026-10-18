package app.saveclip.core.hls

import app.saveclip.core.drm.DrmDetector
import app.saveclip.core.drm.HlsAttributes
import app.saveclip.core.net.UrlResolver

data class HlsVariant(
    val url: String,
    val bandwidth: Int,
    val resolution: String?,
    val width: Int?,
    val height: Int?,
    val codecs: String?,
    val name: String?,
    val audioGroup: String?,
)

data class HlsRendition(
    val type: String,
    val groupId: String,
    val name: String?,
    val url: String?,
    val default: Boolean,
)

data class ByteRange(val start: Long, val endInclusive: Long) {
    val length: Long get() = endInclusive - start + 1
}

data class HlsSegment(
    val url: String,
    val durationSeconds: Double,
    val byteRange: ByteRange?,
)

data class HlsMediaPlaylist(
    val url: String,
    val segments: List<HlsSegment>,
    val initializationUrl: String?,
    val initializationRange: ByteRange?,
    val encrypted: Boolean,
    val targetDurationSeconds: Double?,
    val hasEndList: Boolean,
)

sealed class HlsParseResult {
    data class Master(
        val url: String,
        val variants: List<HlsVariant>,
        val renditions: List<HlsRendition>,
    ) : HlsParseResult()

    data class Media(val playlist: HlsMediaPlaylist) : HlsParseResult()
}

object HlsPlaylistParser {
    fun parse(text: String, playlistUrl: String): HlsParseResult {
        val lines = text
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
            .map { it.trim() }
        if (lines.none { it.startsWith("#EXTM3U") }) {
            error("HLS 재생 목록이 아닙니다")
        }
        val variants = mutableListOf<HlsVariant>()
        val renditions = mutableListOf<HlsRendition>()
        val segments = mutableListOf<HlsSegment>()
        var pendingVariant: Map<String, String>? = null
        var pendingDuration: Double? = null
        var pendingRange: ByteRange? = null
        var nextByteOffset = 0L
        var initUrl: String? = null
        var initRange: ByteRange? = null
        var targetDuration: Double? = null
        var endList = false
        var sawStreamInf = false

        for (line in lines) {
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                when {
                    line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true) -> {
                        sawStreamInf = true
                        pendingVariant = HlsAttributes.parse(line.substringAfter(':'))
                    }
                    line.startsWith("#EXT-X-MEDIA:", ignoreCase = true) -> {
                        val attrs = HlsAttributes.parse(line.substringAfter(':'))
                        val group = attrs["GROUP-ID"] ?: continue
                        renditions += HlsRendition(
                            type = attrs["TYPE"].orEmpty(),
                            groupId = group,
                            name = attrs["NAME"],
                            url = attrs["URI"]?.let { UrlResolver.resolve(playlistUrl, it) },
                            default = attrs["DEFAULT"].equals("YES", ignoreCase = true),
                        )
                    }
                    line.startsWith("#EXTINF:", ignoreCase = true) -> {
                        pendingDuration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                    }
                    line.startsWith("#EXT-X-BYTERANGE:", ignoreCase = true) -> {
                        val parsed = parseByteRange(line.substringAfter(':'), nextByteOffset)
                        pendingRange = parsed
                        nextByteOffset = parsed.endInclusive + 1
                    }
                    line.startsWith("#EXT-X-MAP:", ignoreCase = true) -> {
                        val attrs = HlsAttributes.parse(line.substringAfter(':'))
                        initUrl = attrs["URI"]?.let { UrlResolver.resolve(playlistUrl, it) }
                        val range = attrs["BYTERANGE"]
                        if (range != null) {
                            initRange = parseByteRange(range, 0L)
                        }
                    }
                    line.startsWith("#EXT-X-TARGETDURATION:", ignoreCase = true) -> {
                        targetDuration = line.substringAfter(':').trim().toDoubleOrNull()
                    }
                    line.equals("#EXT-X-ENDLIST", ignoreCase = true) -> endList = true
                }
                continue
            }
            if (pendingVariant != null) {
                val attrs = pendingVariant
                val resolution = attrs["RESOLUTION"]
                val (width, height) = splitResolution(resolution)
                variants += HlsVariant(
                    url = UrlResolver.resolve(playlistUrl, line),
                    bandwidth = attrs["BANDWIDTH"]?.toIntOrNull()
                        ?: attrs["AVERAGE-BANDWIDTH"]?.toIntOrNull()
                        ?: 0,
                    resolution = resolution,
                    width = width,
                    height = height,
                    codecs = attrs["CODECS"],
                    name = attrs["NAME"],
                    audioGroup = attrs["AUDIO"],
                )
                pendingVariant = null
            } else {
                val range = pendingRange
                segments += HlsSegment(
                    url = UrlResolver.resolve(playlistUrl, line),
                    durationSeconds = pendingDuration ?: 0.0,
                    byteRange = range,
                )
                pendingDuration = null
                pendingRange = null
            }
        }
        val encrypted = DrmDetector.playlistIsProtected(text)
        if (sawStreamInf) {
            if (variants.isEmpty()) error("재생 목록에 화질 정보가 없습니다")
            return HlsParseResult.Master(playlistUrl, variants, renditions)
        }
        if (segments.isEmpty()) error("재생 목록에 조각이 없습니다")
        return HlsParseResult.Media(
            HlsMediaPlaylist(
                url = playlistUrl,
                segments = segments,
                initializationUrl = initUrl,
                initializationRange = initRange,
                encrypted = encrypted,
                targetDurationSeconds = targetDuration,
                hasEndList = endList,
            ),
        )
    }

    private fun splitResolution(resolution: String?): Pair<Int?, Int?> {
        if (resolution.isNullOrBlank()) return null to null
        val parts = resolution.lowercase().split('x')
        return parts.getOrNull(0)?.toIntOrNull() to parts.getOrNull(1)?.toIntOrNull()
    }

    private fun parseByteRange(raw: String, fallbackStart: Long): ByteRange {
        val body = raw.trim().trim('"')
        val length = body.substringBefore('@').toLong()
        val start = body.substringAfter('@', "").toLongOrNull() ?: fallbackStart
        return ByteRange(start, start + length - 1)
    }
}
