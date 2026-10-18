package app.saveclip.core.library

import app.saveclip.core.net.UrlResolver
import app.saveclip.core.policy.YouTubeBlocklist

enum class DeleteMode { DIRECT, SYSTEM_REQUEST }

object LibraryOps {
    fun sanitizeDisplayName(raw: String, fallbackExtension: String): String {
        val cleaned = raw
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), " ")
            .replace("..", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('.')
            .trim()
        val base = cleaned.ifBlank { "영상" }.take(80)
        val extension = fallbackExtension.trim().trim('.').lowercase().ifBlank { "mp4" }
        return if ('.' in base) base else "$base.$extension"
    }

    fun deleteMode(sdk: Int, count: Int, appPrivate: Boolean): DeleteMode {
        if (appPrivate || count <= 1) return DeleteMode.DIRECT
        if (sdk >= 30) return DeleteMode.SYSTEM_REQUEST
        return DeleteMode.DIRECT
    }

    fun redownloadUrl(pageUrl: String): String? {
        val url = UrlResolver.normalizeUserInput(pageUrl) ?: return null
        if (YouTubeBlocklist.isBlocked(url)) return null
        return url
    }
}
