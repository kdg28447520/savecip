package app.saveclip.core.quality

import app.saveclip.core.dash.DashManifest
import app.saveclip.core.dash.DashMpdParser
import app.saveclip.core.dash.DashTrack
import app.saveclip.core.drm.DrmDetector
import app.saveclip.core.hls.HlsParseResult
import app.saveclip.core.hls.HlsPlaylistParser
import app.saveclip.core.hls.HlsRendition
import app.saveclip.core.hls.HlsVariant
import app.saveclip.core.http.SaveClipHttp
import app.saveclip.core.policy.DownloadGuard
import app.saveclip.core.policy.ProtectedMediaException

data class QualityChoice(
    val id: String,
    val label: String,
    val bandwidth: Long,
    val height: Int?,
)

object QualityPicker {
    fun defaultHls(variants: List<HlsVariant>): HlsVariant {
        val capped = variants.filter { (it.height ?: 0) in 1..720 }
        return (if (capped.isNotEmpty()) capped else variants).maxBy { it.bandwidth }
    }

    fun lowestHls(variants: List<HlsVariant>): HlsVariant = variants.minBy { it.bandwidth }

    fun defaultVideo(tracks: List<DashTrack>): DashTrack {
        val videos = tracks.filter { it.contentType == "video" }
        require(videos.isNotEmpty()) { "영상 트랙이 없습니다" }
        val capped = videos.filter { (it.height ?: 0) in 1..720 }
        return (if (capped.isNotEmpty()) capped else videos).maxBy { it.bandwidth }
    }

    fun lowestVideo(tracks: List<DashTrack>): DashTrack {
        val videos = tracks.filter { it.contentType == "video" }
        require(videos.isNotEmpty()) { "영상 트랙이 없습니다" }
        return videos.minBy { it.bandwidth }
    }

    fun defaultAudio(tracks: List<DashTrack>, preferredId: String? = null): DashTrack? {
        val audios = tracks.filter { it.contentType == "audio" }
        if (!preferredId.isNullOrBlank()) {
            audios.firstOrNull { it.id == preferredId }?.let { return it }
        }
        if (audios.isEmpty()) return null
        val main = audios.filter { track -> track.roles.any { it.equals("main", true) } }
        return (if (main.isNotEmpty()) main else audios).maxBy { it.bandwidth }
    }

    fun hlsLabel(variant: HlsVariant): String = when {
        variant.height != null -> "${variant.height}p · ${variant.bandwidth / 1000} kbps"
        !variant.name.isNullOrBlank() -> variant.name
        else -> "${variant.bandwidth / 1000} kbps"
    }

    fun dashLabel(track: DashTrack): String = when {
        track.height != null -> "${track.height}p · ${track.bandwidth / 1000} kbps"
        else -> "${track.bandwidth / 1000} kbps"
    }

    fun audioRendition(variant: HlsVariant, renditions: List<HlsRendition>): HlsRendition? {
        if (codecsIncludeAudio(variant.codecs)) return null
        val group = variant.audioGroup ?: return null
        val matches = renditions.filter {
            it.type.equals("AUDIO", true) && it.groupId == group && !it.url.isNullOrBlank()
        }
        return matches.firstOrNull { it.default } ?: matches.firstOrNull()
    }

    fun codecsIncludeAudio(codecs: String?): Boolean {
        if (codecs.isNullOrBlank()) return true
        val value = codecs.lowercase()
        return listOf("mp4a", "aac", "ac-3", "ec-3", "opus", "mp3").any { value.contains(it) }
    }
}

object PlaylistInspector {
    suspend fun hlsChoices(url: String, headers: Map<String, String>, http: SaveClipHttp = SaveClipHttp()): List<QualityChoice> {
        DownloadGuard.checkUrl(url)
        val text = http.getText(url, headers)
        if (DrmDetector.playlistIsProtected(text)) throw ProtectedMediaException()
        return when (val parsed = HlsPlaylistParser.parse(text, url)) {
            is HlsParseResult.Master -> parsed.variants
                .sortedWith(compareByDescending<HlsVariant> { it.height ?: -1 }.thenByDescending { it.bandwidth })
                .map { QualityChoice(it.url, QualityPicker.hlsLabel(it), it.bandwidth.toLong(), it.height) }
            is HlsParseResult.Media -> listOf(QualityChoice(url, "기본 화질", 0, null))
        }
    }

    suspend fun dashChoices(url: String, headers: Map<String, String>, http: SaveClipHttp = SaveClipHttp()): List<QualityChoice> {
        DownloadGuard.checkUrl(url)
        val manifest = inspectDash(url, headers, http)
        if (manifest.containsContentProtection) throw ProtectedMediaException()
        return manifest.tracks
            .filter { it.contentType == "video" && !it.protected }
            .sortedWith(compareByDescending<DashTrack> { it.height ?: -1 }.thenByDescending { it.bandwidth })
            .map { QualityChoice(it.id, QualityPicker.dashLabel(it), it.bandwidth, it.height) }
    }

    suspend fun inspectDash(url: String, headers: Map<String, String>, http: SaveClipHttp = SaveClipHttp()): DashManifest {
        DownloadGuard.checkUrl(url)
        return DashMpdParser.parse(http.getText(url, headers), url)
    }
}
