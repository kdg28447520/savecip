package app.saveclip.core

object BrowserProfile {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36"

    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"

    fun userAgent(desktopSite: Boolean): String = if (desktopSite) DESKTOP_USER_AGENT else USER_AGENT
}
