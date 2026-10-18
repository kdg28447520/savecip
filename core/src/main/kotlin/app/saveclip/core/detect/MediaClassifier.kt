package app.saveclip.core.detect

import app.saveclip.core.policy.ExecutableGuard
import java.util.Locale

enum class MediaKind {
    PROGRESSIVE_MP4,
    WEBM,
    HLS,
    DASH,
}

object MediaClassifier {
    fun classify(url: String, mime: String? = null): MediaKind? {
        when (ExecutableGuard.pathExtension(url)) {
            "m3u8", "m3u" -> return MediaKind.HLS
            "mpd" -> return MediaKind.DASH
            "webm" -> return MediaKind.WEBM
            "mp4", "m4v" -> return MediaKind.PROGRESSIVE_MP4
        }
        val normalized = mime?.substringBefore(';')?.trim()?.lowercase(Locale.US) ?: return null
        return when (normalized) {
            "application/vnd.apple.mpegurl",
            "application/x-mpegurl",
            "audio/mpegurl",
            "audio/x-mpegurl",
            -> MediaKind.HLS
            "application/dash+xml" -> MediaKind.DASH
            "video/webm", "audio/webm" -> MediaKind.WEBM
            "video/mp4", "video/m4v" -> MediaKind.PROGRESSIVE_MP4
            else -> null
        }
    }
}
