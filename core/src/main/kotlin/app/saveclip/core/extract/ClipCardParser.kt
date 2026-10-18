package app.saveclip.core.extract

import app.saveclip.core.detect.MediaKind
import org.json.JSONArray
import org.json.JSONObject

sealed class CardParse {
    data class Found(val items: List<ExtractedMedia>) : CardParse()
    data object Empty : CardParse()
    data object Protected : CardParse()
    data object Invalid : CardParse()
}

object ClipCardParser {
    fun parse(json: String, pageUrl: String): CardParse {
        return try {
            parseUnsafe(json, pageUrl)
        } catch (_: Exception) {
            CardParse.Invalid
        }
    }

    private fun parseUnsafe(json: String, pageUrl: String): CardParse {
        if (json.isBlank()) return CardParse.Invalid
        val root = JSONObject(json)
        val code = root.optJSONObject("header")?.optInt("code", 0) ?: 0
        if (code != 0) return CardParse.Empty
        val content = root.optJSONObject("body")
            ?.optJSONObject("card")
            ?.optJSONObject("content")
            ?: return CardParse.Empty
        val vod = content.optJSONObject("vod") ?: return CardParse.Empty
        if (vod.has("playable") && !vod.optBoolean("playable", false)) return CardParse.Empty
        val playback = vod.opt("playback")
        if (containsProtection(playback)) return CardParse.Protected
        val title = titleOf(content)
        val progressive = mutableListOf<Candidate>()
        val playlists = mutableListOf<Candidate>()
        collect(playback, progressive, playlists)
        val chosen = (if (progressive.isNotEmpty()) progressive else playlists)
            .distinctBy { it.url }
            .sortedByDescending { it.bandwidth }
            .take(6)
        if (chosen.isEmpty()) return CardParse.Empty
        return CardParse.Found(
            chosen.map { candidate ->
                ExtractedMedia(
                    url = candidate.url,
                    mime = candidate.mime,
                    kind = candidate.kind,
                    pageUrl = pageUrl,
                    title = title,
                    label = candidate.label,
                )
            },
        )
    }

    private fun collect(node: Any?, progressive: MutableList<Candidate>, playlists: MutableList<Candidate>) {
        when (node) {
            is JSONObject -> {
                val source = node.optString("source")
                consider(source, node.optString("@codecs").ifBlank { node.optString("codecs") }, node, progressive, playlists)
                val m3u = node.optString("@nvod:m3u")
                consider(m3u, node.optString("@codecs"), node, progressive, playlists)
                urlsOf(node.opt("BaseURL")).forEach { url ->
                    consider(url, node.optString("@codecs"), node, progressive, playlists)
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key == "source" || key == "BaseURL" || key == "@nvod:m3u") continue
                    collect(node.opt(key), progressive, playlists)
                }
            }
            is JSONArray -> {
                for (index in 0 until node.length()) collect(node.opt(index), progressive, playlists)
            }
        }
    }

    private fun consider(
        rawUrl: String,
        codecs: String,
        owner: JSONObject,
        progressive: MutableList<Candidate>,
        playlists: MutableList<Candidate>,
    ) {
        val url = rawUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val path = url.substringBefore('?').substringBefore('#')
        val lowerCodecs = codecs.lowercase()
        if (path.endsWith(".mp4", ignoreCase = true) && isMuxedMp4(lowerCodecs)) {
            progressive += Candidate(
                url = url,
                mime = "video/mp4",
                kind = MediaKind.PROGRESSIVE_MP4,
                label = qualityLabel(owner, "MP4"),
                bandwidth = bandwidth(owner),
            )
        } else if (path.endsWith(".m3u8", ignoreCase = true) && !isAudioOnly(lowerCodecs)) {
            playlists += Candidate(
                url = url,
                mime = "application/vnd.apple.mpegurl",
                kind = MediaKind.HLS,
                label = qualityLabel(owner, "HLS"),
                bandwidth = bandwidth(owner),
            )
        } else if (path.endsWith(".mpd", ignoreCase = true)) {
            playlists += Candidate(
                url = url,
                mime = "application/dash+xml",
                kind = MediaKind.DASH,
                label = qualityLabel(owner, "DASH"),
                bandwidth = bandwidth(owner),
            )
        }
    }

    private fun isMuxedMp4(codecs: String): Boolean {
        if (codecs.isBlank()) return true
        val audio = "mp4a" in codecs || "aac" in codecs
        val video = "avc1" in codecs || "hev1" in codecs || "hvc1" in codecs || "vp09" in codecs || "vp9" in codecs
        return audio && video
    }

    private fun isAudioOnly(codecs: String): Boolean {
        if (codecs.isBlank()) return false
        val audio = "mp4a" in codecs || "aac" in codecs
        val video = "avc1" in codecs || "hev1" in codecs || "hvc1" in codecs || "vp09" in codecs || "vp9" in codecs
        return audio && !video
    }

    private fun urlsOf(node: Any?): List<String> {
        return when (node) {
            is String -> listOf(node)
            is JSONArray -> (0 until node.length()).mapNotNull { textValue(node.opt(it)) }
            is JSONObject -> listOfNotNull(textValue(node))
            else -> emptyList()
        }
    }

    private fun textValue(node: Any?): String? {
        return when (node) {
            is String -> node.takeIf { it.isNotBlank() }
            is JSONObject -> node.optString("#text").takeIf { it.isNotBlank() }
                ?: node.optString("url").takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun bandwidth(owner: JSONObject): Long {
        val raw = owner.opt("@bandwidth") ?: owner.opt("bitrate")
        val direct = when (raw) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull()
            is JSONObject -> raw.optLong("video", 0L).takeIf { it > 0 }
            else -> null
        }
        if (direct != null && direct > 0) return direct
        return 0L
    }

    private fun qualityLabel(owner: JSONObject, fallbackKind: String): String {
        val labels = owner.optJSONArray("nvod:Label")
        if (labels != null) {
            for (index in 0 until labels.length()) {
                val item = labels.optJSONObject(index) ?: continue
                if (item.optString("@kind") == "resolution") {
                    val text = item.optString("#text")
                    if (text.isNotBlank()) return "${text}p · $fallbackKind"
                }
            }
        }
        val name = owner.optJSONObject("encodingOption")?.optString("name").orEmpty()
        if (name.isNotBlank()) return "$name · $fallbackKind"
        val height = owner.optString("@height").toIntOrNull()
        val width = owner.optString("@width").toIntOrNull()
        val shortEdge = listOfNotNull(height, width).filter { it > 0 }.minOrNull()
        return if (shortEdge != null) "${shortEdge}p · $fallbackKind" else fallbackKind
    }

    private fun titleOf(content: JSONObject): String {
        val title = content.optString("title").trim()
        if (title.isNotEmpty()) return title.take(80)
        val line = content.optString("description")
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
        return line?.take(80) ?: "네이버 클립"
    }

    private fun containsProtection(node: Any?): Boolean {
        return when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key.contains("ContentProtection", ignoreCase = true)) return true
                    if (key.equals("drm", ignoreCase = true) && !isEmpty(node.opt(key))) return true
                    val value = node.opt(key)
                    if (value is String && drmMarker(value)) return true
                    if (key.equals("useDrm", ignoreCase = true) && node.optBoolean(key, false)) return true
                    if (containsProtection(value)) return true
                }
                false
            }
            is JSONArray -> {
                for (index in 0 until node.length()) {
                    if (containsProtection(node.opt(index))) return true
                }
                false
            }
            else -> false
        }
    }

    private fun isEmpty(node: Any?): Boolean {
        return node == null || node == JSONObject.NULL || (node is JSONObject && node.length() == 0)
    }

    private fun drmMarker(value: String): Boolean {
        val lower = value.lowercase()
        return "widevine" in lower ||
            "playready" in lower ||
            "edef8ba9-79d6-4ace" in lower ||
            "com.widevine" in lower ||
            "urn:mpeg:dash:mp4protection" in lower
    }
}

private data class Candidate(
    val url: String,
    val mime: String,
    val kind: MediaKind,
    val label: String,
    val bandwidth: Long,
)
