package app.saveclip.core.policy

import app.saveclip.core.UserMessages
import java.net.URI
import java.util.Locale

class YouTubeBlockedException : Exception(UserMessages.YOUTUBE)

class ExecutableBlockedException : Exception(UserMessages.EXECUTABLE)

class ProtectedMediaException : Exception(UserMessages.PROTECTED)

object YouTubeBlocklist {
    private val roots = listOf(
        "youtube.com",
        "youtu.be",
        "youtube-nocookie.com",
        "googlevideo.com",
    )

    fun isBlocked(raw: String): Boolean {
        val host = hostOf(raw) ?: return false
        return roots.any { root -> host == root || host.endsWith(".$root") }
    }

    fun hostOf(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val direct = runCatching { URI(trimmed).host }.getOrNull()
        val host = direct ?: authorityFallback(trimmed)
        return host
            ?.trim()
            ?.trimEnd('.')
            ?.lowercase(Locale.US)
            ?.takeIf { it.isNotEmpty() }
    }

    private fun authorityFallback(raw: String): String? {
        val match = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#]+)""").find(raw) ?: return null
        var authority = match.groupValues[1].substringAfter('@')
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            return if (end >= 0) authority.substring(0, end + 1) else authority
        }
        authority = authority.substringBefore(':')
        return authority
    }
}

object ExecutableGuard {
    private val extensions = setOf(
        "apk", "exe", "dex", "jar", "bat", "cmd", "sh", "msi", "dmg", "pkg", "scr", "dll", "so", "com",
    )

    private val mimeTypes = setOf(
        "application/vnd.android.package-archive",
        "application/x-msdos-program",
        "application/x-msdownload",
        "application/x-executable",
        "application/x-dosexec",
        "application/java-archive",
        "application/x-sh",
        "application/x-shellscript",
        "application/x-sharedlib",
    )

    fun isBlockedUrl(raw: String): Boolean {
        val extension = pathExtension(raw)
        if (extension != null && extension in extensions) return true
        val query = runCatching { URI(raw.trim()).rawQuery }.getOrNull() ?: return false
        val lower = query.lowercase(Locale.US)
        return extensions.any { ext ->
            Regex("""(?:^|[=/])[^&]*\.$ext(?:$|&)""").containsMatchIn(lower)
        }
    }

    fun isBlockedMime(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val mime = raw.substringBefore(';').trim().lowercase(Locale.US)
        return mime in mimeTypes
    }

    fun pathExtension(raw: String): String? {
        val path = runCatching { URI(raw.trim()).path }.getOrNull() ?: return null
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return null
        return name.substring(dot + 1).lowercase(Locale.US)
    }
}

object DownloadGuard {
    fun checkUrl(url: String) {
        if (YouTubeBlocklist.isBlocked(url)) throw YouTubeBlockedException()
        if (ExecutableGuard.isBlockedUrl(url)) throw ExecutableBlockedException()
    }
}
