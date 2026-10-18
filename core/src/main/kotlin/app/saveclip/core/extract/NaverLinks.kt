package app.saveclip.core.extract

import app.saveclip.core.policy.YouTubeBlocklist
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class NaverClipRef(
    val mediaId: String,
    val serviceType: String,
    val mediaType: String,
)

object NaverLinks {
    fun isShortLink(url: String): Boolean {
        val host = YouTubeBlocklist.hostOf(url) ?: return false
        return host == "naver.me" || host.endsWith(".naver.me")
    }

    fun parse(url: String): NaverClipRef? {
        val host = YouTubeBlocklist.hostOf(url) ?: return null
        if (!isClipHost(host)) return null
        val params = queryParams(url)
        val id = sequenceOf("seedMediaId", "mediaId", "videoId")
            .mapNotNull { key -> params.value(key) }
            .firstOrNull { isMediaId(it) }
            ?: pathId(url)
            ?: return null
        return NaverClipRef(
            mediaId = id,
            serviceType = normalizeService(params.value("serviceType"), host),
            mediaType = normalizeMediaType(params.value("mediaType") ?: params.value("seedMediaType")),
        )
    }

    fun cardApiUrl(ref: NaverClipRef): String {
        val id = encode(ref.mediaId)
        val service = encode(ref.serviceType)
        val type = encode(ref.mediaType)
        return "https://creatorhub-api.naver.com/api/v7.0/clipviewer/card" +
            "?seedMediaId=$id&serviceType=$service&seedType=SPECIFIC&mediaType=$type&userInteraction=true"
    }

    fun serviceAttempts(ref: NaverClipRef): List<String> {
        return buildList {
            add(ref.serviceType)
            if (ref.serviceType != "CLIP") add("CLIP")
            if (ref.serviceType != "NAVER_TV" && !contains("NAVER_TV")) add("NAVER_TV")
        }
    }

    private fun isClipHost(host: String): Boolean {
        if (host == "naver.me" || host.endsWith(".naver.me")) return false
        return host == "naver.com" || host.endsWith(".naver.com")
    }

    private fun normalizeService(raw: String?, host: String): String {
        val mapped = when (raw?.trim()?.uppercase()) {
            "NTV", "NOW" -> "NAVER_TV"
            "MOMENT" -> "BLOG"
            "SHOPPINGLIVE" -> "SHOPPING"
            "MYPLACE" -> "MY_PLACE"
            null, "" -> null
            else -> raw.trim().uppercase()
        }
        if (mapped != null && mapped.matches(Regex("[A-Z0-9_]{2,32}"))) return mapped
        return if (host == "tv.naver.com" || host.endsWith(".tv.naver.com")) "NAVER_TV" else "CLIP"
    }

    private fun normalizeMediaType(raw: String?): String {
        return when (raw?.trim()?.uppercase()) {
            "LONG_FORM" -> "LONG_FORM"
            "POST" -> "POST"
            else -> "SHORT_FORM"
        }
    }

    private fun isMediaId(value: String): Boolean {
        return value.matches(Regex("[A-Fa-f0-9]{8,64}")) || value.matches(Regex("[0-9]{4,20}"))
    }

    private fun pathId(url: String): String? {
        val path = runCatching { URI(url).path }.getOrNull() ?: return null
        val match = Regex("""/(?:v|clip|shorts)/([A-Fa-f0-9]{8,64}|[0-9]{4,20})(?:/|$)""").find(path)
        return match?.groupValues?.getOrNull(1)
    }

    private fun queryParams(url: String): Map<String, String> {
        val rawQuery = runCatching { URI(url).rawQuery }.getOrNull()
            ?: url.substringAfter('?', missingDelimiterValue = "").substringBefore('#')
        if (rawQuery.isBlank()) return emptyMap()
        val result = linkedMapOf<String, String>()
        for (part in rawQuery.split('&')) {
            if (part.isBlank()) continue
            val key = decode(part.substringBefore('='))
            val value = decode(part.substringAfter('=', ""))
            if (key.isNotBlank() && !result.containsKey(key)) result[key] = value
        }
        return result
    }

    private fun decode(value: String): String {
        return runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8) }.getOrDefault(value)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun Map<String, String>.value(name: String): String? {
        return entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }
}
