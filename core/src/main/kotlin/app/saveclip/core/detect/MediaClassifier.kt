package app.saveclip.core.detect

import app.saveclip.core.policy.ExecutableGuard
import java.net.URI
import java.util.Locale

enum class MediaKind {
    PROGRESSIVE_MP4,
    WEBM,
    HLS,
    DASH,
}

object MediaClassifier {
    private val probeHints = listOf(
        "video",
        "stream",
        "playlist",
        "manifest",
        "m3u8",
        "mpd",
        "mp4",
        "hls",
        "dash",
    )

    private val ignoredExtensions = setOf(
        "js", "css", "png", "jpg", "jpeg", "gif", "webp", "svg", "ico",
        "woff", "woff2", "ttf", "map", "html", "htm", "json",
    )

    fun classify(url: String, mime: String? = null): MediaKind? {
        when (ExecutableGuard.pathExtension(url)) {
            "m3u8", "m3u" -> return MediaKind.HLS
            "mpd" -> return MediaKind.DASH
            "webm" -> return MediaKind.WEBM
            "mp4", "m4v" -> return MediaKind.PROGRESSIVE_MP4
        }
        return fromMime(mime)
    }

    /**
     * Extensionless addresses that are worth a HEAD or a 1KB range peek.
     * Path and query tokens are checked. The host is not, so a CDN name alone
     * does not trigger a probe. [accept] is the request Accept header.
     */
    fun worthProbing(url: String, accept: String? = null): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        if (ExecutableGuard.isBlockedUrl(url)) return false
        if (classify(url, null) != null) return false
        val extension = ExecutableGuard.pathExtension(url)
        if (extension != null && extension in ignoredExtensions) return false
        if (acceptSuggestsMedia(accept)) return true
        val path = runCatching { URI(url.trim()).rawPath }.getOrNull().orEmpty()
        val query = runCatching { URI(url.trim()).rawQuery }.getOrNull().orEmpty()
        val haystack = (path + "?" + query).lowercase(Locale.US)
        return probeHints.any { hint -> haystack.contains(hint) }
    }

    fun classifyContent(contentType: String?, prefix: ByteArray): MediaKind? {
        if (ExecutableGuard.isBlockedMime(contentType)) return null
        fromMime(contentType)?.let { return it }
        return sniff(prefix)
    }

    fun sniff(prefix: ByteArray): MediaKind? {
        if (prefix.isEmpty()) return null
        if (hasFtyp(prefix)) return MediaKind.PROGRESSIVE_MP4
        if (prefix.size >= 4 &&
            prefix[0] == 0x1A.toByte() &&
            prefix[1] == 0x45.toByte() &&
            prefix[2] == 0xDF.toByte() &&
            prefix[3] == 0xA3.toByte()
        ) {
            return MediaKind.WEBM
        }
        val text = prefix.decodeToString().trimStart { it <= ' ' || it == '\uFEFF' }
        if (text.startsWith("#EXTM3U")) return MediaKind.HLS
        val body = stripXmlDeclaration(text).trimStart()
        if (body.startsWith("<MPD", ignoreCase = true)) return MediaKind.DASH
        return null
    }

    fun mimeOf(kind: MediaKind): String = when (kind) {
        MediaKind.HLS -> "application/vnd.apple.mpegurl"
        MediaKind.DASH -> "application/dash+xml"
        MediaKind.WEBM -> "video/webm"
        MediaKind.PROGRESSIVE_MP4 -> "video/mp4"
    }

    private fun fromMime(mime: String?): MediaKind? {
        val normalized = mime?.substringBefore(';')?.trim()?.lowercase(Locale.US) ?: return null
        if (normalized.isEmpty() || normalized == "application/octet-stream") return null
        return when (normalized) {
            "application/vnd.apple.mpegurl",
            "application/x-mpegurl",
            "audio/mpegurl",
            "audio/x-mpegurl",
            -> MediaKind.HLS
            "application/dash+xml" -> MediaKind.DASH
            "video/webm", "audio/webm" -> MediaKind.WEBM
            "video/mp4", "video/m4v", "application/mp4" -> MediaKind.PROGRESSIVE_MP4
            else -> null
        }
    }

    private fun acceptSuggestsMedia(accept: String?): Boolean {
        val value = accept?.lowercase(Locale.US).orEmpty()
        if (value.isBlank() || value == "*/*") return false
        return value.contains("mpegurl") ||
            value.contains("dash+xml") ||
            value.contains("video/") ||
            value.contains("application/mp4")
    }

    private fun hasFtyp(prefix: ByteArray): Boolean {
        val limit = minOf(prefix.size - 3, 32)
        if (limit <= 0) return false
        for (index in 0 until limit) {
            if (prefix[index] == 'f'.code.toByte() &&
                prefix[index + 1] == 't'.code.toByte() &&
                prefix[index + 2] == 'y'.code.toByte() &&
                prefix[index + 3] == 'p'.code.toByte()
            ) {
                return true
            }
        }
        return false
    }

    private fun stripXmlDeclaration(text: String): String {
        if (!text.startsWith("<?xml")) return text
        val end = text.indexOf("?>")
        if (end < 0) return text
        return text.substring(end + 2)
    }
}
