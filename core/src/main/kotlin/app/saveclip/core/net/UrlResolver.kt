package app.saveclip.core.net

import java.net.URI

object UrlResolver {
    fun normalizeUserInput(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        return withScheme
    }

    fun resolve(base: String, reference: String): String {
        val ref = reference.trim().replace(" ", "%20")
        if (ref.isEmpty()) return base
        val baseUri = URI(base)
        return baseUri.resolve(ref).toString()
    }
}
