package app.saveclip.core.browser

import app.saveclip.core.net.UrlResolver
import app.saveclip.core.policy.YouTubeBlocklist

data class Shortcut(val label: String, val url: String)

object PageAddress {
    fun openable(raw: String): String? {
        val url = UrlResolver.normalizeUserInput(raw) ?: return null
        if (YouTubeBlocklist.isBlocked(url)) return null
        return url
    }
}

object HomeShortcuts {
    /** No built-in site shortcuts. Users add their own. Third-party names and logos are not shipped. */
    val defaults: List<Shortcut> = emptyList()

    val retiredSeedUrls: Set<String> = setOf(
        "https://www.naver.com/",
        "https://tv.naver.com/",
        "https://vimeo.com/",
        "https://www.dailymotion.com/",
        "https://archive.org/",
        "https://commons.wikimedia.org/",
    )

    fun accepted(url: String): String? = PageAddress.openable(url)

    fun withoutBlocked(items: List<Shortcut>): List<Shortcut> {
        return items.mapNotNull { item ->
            val url = accepted(item.url) ?: return@mapNotNull null
            item.copy(url = url)
        }
    }
}

object TabLimits {
    const val MAX_TABS = 8

    fun canAdd(openCount: Int): Boolean = openCount < MAX_TABS
}
