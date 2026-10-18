package app.saveclip.core.net

object SharedLinks {
    private val urlPattern = Regex("""https?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)

    /**
     * Pulls the first http(s) address out of shared or copied text.
     * A single token without a scheme is accepted only when the whole value
     * is that token, so a sentence is not turned into a host guess.
     */
    fun firstHttpUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val match = urlPattern.find(raw)
        if (match != null) {
            return UrlResolver.normalizeUserInput(trimTrailers(match.value))
        }
        val trimmed = raw.trim()
        if (trimmed.any { it.isWhitespace() }) return null
        return UrlResolver.normalizeUserInput(trimmed)
    }

    private fun trimTrailers(value: String): String {
        return value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '>', '"', '\'', '。')
    }
}
