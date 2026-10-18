package app.saveclip.core.browser

import app.saveclip.core.net.UrlResolver
import app.saveclip.core.policy.ExecutableGuard
import app.saveclip.core.policy.YouTubeBlocklist
import org.json.JSONObject

data class SrcCandidate(
    val url: String,
    val score: Int,
    val densityOnly: Boolean,
    val density: Double,
)

object ImageSelector {
    const val MIN_EDGE = 400

    fun choose(pageUrl: String, rawUrl: String, width: Int, height: Int, srcset: String): String? {
        val fromSet = largestSrcset(pageUrl, srcset)
        val candidate = fromSet?.url ?: resolve(pageUrl, rawUrl) ?: return null
        if (!candidate.startsWith("http://") && !candidate.startsWith("https://")) return null
        if (YouTubeBlocklist.isBlocked(candidate) || YouTubeBlocklist.isBlocked(pageUrl)) return null
        if (ExecutableGuard.isBlockedUrl(candidate)) return null
        val largeByElement = width >= MIN_EDGE || height >= MIN_EDGE
        val largeBySet = fromSet != null && !fromSet.densityOnly && fromSet.score >= MIN_EDGE
        val largeByDensity = fromSet != null && fromSet.densityOnly && fromSet.density >= 2.0
        if (!largeByElement && !largeBySet && !largeByDensity) return null
        return candidate
    }

    fun largestSrcset(pageUrl: String, srcset: String): SrcCandidate? {
        if (srcset.isBlank()) return null
        var best: SrcCandidate? = null
        for (part in srcset.split(',')) {
            val bits = part.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (bits.isEmpty()) continue
            val url = resolve(pageUrl, bits[0]) ?: continue
            var width = 0
            var density = 1.0
            var sawWidth = false
            var sawDensity = false
            for (descriptor in bits.drop(1)) {
                if (descriptor.endsWith("w")) {
                    width = descriptor.dropLast(1).toIntOrNull() ?: width
                    sawWidth = true
                } else if (descriptor.endsWith("x")) {
                    density = descriptor.dropLast(1).toDoubleOrNull() ?: density
                    sawDensity = true
                }
            }
            val item = if (sawWidth) {
                SrcCandidate(url, width, densityOnly = false, density = if (sawDensity) density else 1.0)
            } else {
                SrcCandidate(url, (density * 1000).toInt(), densityOnly = true, density = density)
            }
            val current = best
            if (current == null || item.score > current.score) best = item
        }
        return best
    }

    private fun resolve(pageUrl: String, raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("blob:") || trimmed.startsWith("data:")) return null
        val absolute = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else if (pageUrl.startsWith("http://") || pageUrl.startsWith("https://")) {
            runCatching { UrlResolver.resolve(pageUrl, trimmed) }.getOrNull() ?: return null
        } else {
            return null
        }
        return absolute
    }
}

object ImageReport {
    fun urls(json: String, pageUrl: String): List<String> {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val images = obj.optJSONArray("images") ?: return emptyList()
        val out = linkedSetOf<String>()
        for (index in 0 until images.length()) {
            val item = images.optJSONObject(index) ?: continue
            val chosen = ImageSelector.choose(
                pageUrl = item.optString("page").ifBlank { pageUrl },
                rawUrl = item.optString("url"),
                width = item.optInt("width"),
                height = item.optInt("height"),
                srcset = item.optString("srcset"),
            )
            if (chosen != null) out += chosen
            if (out.size >= 20) break
        }
        return out.toList()
    }
}
