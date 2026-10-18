package app.saveclip.core.extract

import app.saveclip.core.detect.MediaClassifier
import app.saveclip.core.detect.MediaKind
import app.saveclip.core.net.UrlResolver
import app.saveclip.core.policy.ExecutableGuard
import app.saveclip.core.policy.YouTubeBlocklist

object HtmlMediaFinder {
    private val metaTag = Regex(
        """<meta\b[^>]*>""",
        setOf(RegexOption.IGNORE_CASE),
    )
    private val contentAttr = Regex(
        """\bcontent\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val propertyAttr = Regex(
        """\b(?:property|name)\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val mediaSrc = Regex(
        """<(?:video|audio|source)\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""",
        setOf(RegexOption.IGNORE_CASE),
    )
    private val absoluteMedia = Regex(
        """https?://[^\s"'<>\\]+?\.(?:mp4|webm|m3u8|mpd)(?:\?[^\s"'<>\\]*)?""",
        RegexOption.IGNORE_CASE,
    )

    fun find(pageUrl: String, html: String): List<ExtractedMedia> {
        if (html.isBlank()) return emptyList()
        val found = linkedMapOf<String, ExtractedMedia>()
        fun add(raw: String) {
            val resolved = resolve(pageUrl, raw) ?: return
            if (resolved in found) return
            if (YouTubeBlocklist.isBlocked(resolved) || ExecutableGuard.isBlockedUrl(resolved)) return
            val kind = MediaClassifier.classify(resolved, null) ?: return
            found[resolved] = ExtractedMedia(
                url = resolved,
                mime = mimeOf(kind),
                kind = kind,
                pageUrl = pageUrl,
                title = titleOf(html),
                label = kind.name,
            )
            if (found.size >= 12) return
        }
        for (tag in metaTag.findAll(html).take(80)) {
            val source = tag.value
            val property = propertyAttr.find(source)?.groupValues?.getOrNull(1)?.lowercase().orEmpty()
            if (property != "og:video" &&
                property != "og:video:url" &&
                property != "og:video:secure_url" &&
                property != "twitter:player:stream"
            ) {
                continue
            }
            val content = contentAttr.find(source)?.groupValues?.getOrNull(1) ?: continue
            add(content)
        }
        for (match in mediaSrc.findAll(html).take(40)) {
            add(match.groupValues[1])
            if (found.size >= 12) break
        }
        if (found.isEmpty()) {
            for (match in absoluteMedia.findAll(html).take(20)) add(match.value)
        }
        return found.values.toList()
    }

    fun direct(url: String, mime: String? = null): ExtractedMedia? {
        if (YouTubeBlocklist.isBlocked(url) || ExecutableGuard.isBlockedUrl(url)) return null
        val kind = MediaClassifier.classify(url, mime) ?: return null
        return ExtractedMedia(
            url = url,
            mime = mime?.substringBefore(';')?.trim().orEmpty().ifBlank { mimeOf(kind) },
            kind = kind,
            pageUrl = url,
            title = "영상",
            label = kind.name,
        )
    }

    private fun resolve(base: String, reference: String): String? {
        val trimmed = reference.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("blob:") || trimmed.startsWith("data:")) return null
        return try {
            val resolved = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                UrlResolver.resolve(base, trimmed)
            }
            if (resolved.startsWith("http://") || resolved.startsWith("https://")) resolved else null
        } catch (_: Exception) {
            null
        }
    }

    private fun titleOf(html: String): String {
        val og = Regex(
            """<meta\b[^>]*(?:property|name)\s*=\s*["']og:title["'][^>]*>""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.value
        val content = og?.let { contentAttr.find(it)?.groupValues?.getOrNull(1) }
        if (!content.isNullOrBlank()) return decode(content).take(80)
        val title = Regex("""<title>([^<]{1,160})</title>""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.getOrNull(1)
        return decode(title.orEmpty()).trim().take(80).ifBlank { "영상" }
    }

    private fun decode(value: String): String {
        return value
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    private fun mimeOf(kind: MediaKind): String = when (kind) {
        MediaKind.HLS -> "application/vnd.apple.mpegurl"
        MediaKind.DASH -> "application/dash+xml"
        MediaKind.WEBM -> "video/webm"
        MediaKind.PROGRESSIVE_MP4 -> "video/mp4"
    }
}
