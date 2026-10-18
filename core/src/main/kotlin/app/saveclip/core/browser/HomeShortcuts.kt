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
    val defaults: List<Shortcut> = listOf(
        Shortcut("네이버", "https://www.naver.com/"),
        Shortcut("네이버TV", "https://tv.naver.com/"),
        Shortcut("비메오", "https://vimeo.com/"),
        Shortcut("데일리모션", "https://www.dailymotion.com/"),
        Shortcut("인터넷 아카이브", "https://archive.org/"),
        Shortcut("위키미디어", "https://commons.wikimedia.org/"),
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
