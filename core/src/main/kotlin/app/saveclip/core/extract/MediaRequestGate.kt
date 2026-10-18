package app.saveclip.core.extract

import app.saveclip.core.detect.MediaClassifier
import app.saveclip.core.policy.ExecutableGuard
import app.saveclip.core.policy.YouTubeBlocklist

sealed class RequestDecision {
    data object Allow : RequestDecision()
    data class Block(val youtube: Boolean) : RequestDecision()
    data class Observe(val url: String, val pageUrl: String) : RequestDecision()
}

/**
 * Decides how a WebView resource request should be treated.
 * Callers must pass a page URL captured on the UI thread. Reading WebView.getUrl()
 * from shouldInterceptRequest runs on a background thread and can kill the process.
 */
object MediaRequestGate {
    fun inspect(url: String, pageUrl: String, mainFrame: Boolean): RequestDecision {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return RequestDecision.Allow
        val scheme = schemeOf(trimmed)
        if (scheme != "http" && scheme != "https") return RequestDecision.Block(youtube = false)
        if (YouTubeBlocklist.isBlocked(trimmed) || YouTubeBlocklist.isBlocked(pageUrl)) {
            return RequestDecision.Block(youtube = true)
        }
        if (ExecutableGuard.isBlockedUrl(trimmed)) return RequestDecision.Block(youtube = false)
        if (mainFrame) return RequestDecision.Allow
        runCatching { MediaClassifier.classify(trimmed, null) }.getOrNull() ?: return RequestDecision.Allow
        return RequestDecision.Observe(trimmed, pageUrl)
    }

    private fun schemeOf(url: String): String {
        val end = url.indexOf(':')
        if (end <= 0 || end > 16) return ""
        val scheme = url.substring(0, end)
        if (!scheme.all { it.isLetter() }) return ""
        return scheme.lowercase()
    }
}
