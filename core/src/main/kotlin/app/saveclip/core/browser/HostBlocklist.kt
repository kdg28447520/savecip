package app.saveclip.core.browser

import app.saveclip.core.policy.YouTubeBlocklist
import java.util.Locale

object HostListParser {
    fun parse(text: String): Set<String> {
        val hosts = linkedSetOf<String>()
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) continue
            val body = trimmed.substringBefore('#')
            val tokens = body.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) continue
            val raw = when {
                tokens[0] == "0.0.0.0" || tokens[0] == "127.0.0.1" -> tokens.getOrNull(1)
                tokens[0].startsWith("||") -> tokens[0].removePrefix("||").substringBefore('^').substringBefore('/')
                else -> tokens[0]
            } ?: continue
            val host = raw.trim().trimEnd('.').lowercase(Locale.US)
            if (!isHost(host)) continue
            hosts += host
        }
        return hosts
    }

    private fun isHost(host: String): Boolean {
        if (host.isEmpty() || host == "localhost" || host.contains('/') || host.contains(' ')) return false
        if (host.any { it.isISOControl() }) return false
        return host.contains('.')
    }
}

class HostBlocklist(val hosts: Set<String>) {
    fun blocks(url: String): Boolean {
        val host = YouTubeBlocklist.hostOf(url) ?: return false
        var cursor = host
        while (cursor.isNotEmpty()) {
            if (cursor in hosts) return true
            val dot = cursor.indexOf('.')
            if (dot < 0 || dot == cursor.lastIndex) return false
            cursor = cursor.substring(dot + 1)
        }
        return false
    }

    companion object {
        fun bundled(): HostBlocklist {
            val text = HostBlocklist::class.java.getResourceAsStream("/adblock/hosts.txt")
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            return HostBlocklist(HostListParser.parse(text))
        }
    }
}

object AdBlockDecision {
    fun shouldBlock(
        enabled: Boolean,
        mainFrame: Boolean,
        requestUrl: String,
        pageUrl: String,
        list: HostBlocklist,
        exceptions: Set<String>,
    ): Boolean {
        if (!enabled || mainFrame) return false
        if (YouTubeBlocklist.isBlocked(requestUrl) || YouTubeBlocklist.isBlocked(pageUrl)) return false
        val pageHost = YouTubeBlocklist.hostOf(pageUrl)
        if (pageHost != null && exceptions.any { matches(pageHost, it) }) return false
        return list.blocks(requestUrl)
    }

    fun matches(host: String, exception: String): Boolean {
        val root = exception.trim().trimEnd('.').lowercase(Locale.US)
        if (root.isEmpty()) return false
        return host == root || host.endsWith(".$root")
    }
}
