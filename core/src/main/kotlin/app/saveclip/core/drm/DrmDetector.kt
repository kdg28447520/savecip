package app.saveclip.core.drm

import app.saveclip.core.dash.DashMpdParser

object DrmDetector {
    fun playlistIsProtected(text: String): Boolean {
        var protected = false
        val lines = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').lineSequence()
        for (raw in lines) {
            val line = raw.trim()
            val isKey = line.startsWith("#EXT-X-KEY:", ignoreCase = true) ||
                line.startsWith("#EXT-X-SESSION-KEY:", ignoreCase = true)
            if (!isKey) continue
            val method = HlsAttributes.parse(line.substringAfter(':'))["METHOD"]
                ?.trim()
                ?.uppercase()
                .orEmpty()
            if (method != "NONE") protected = true
        }
        return protected
    }

    fun mpdIsProtected(xml: String): Boolean {
        return try {
            DashMpdParser.parse(xml, "https://invalid.local/manifest.mpd").containsContentProtection
        } catch (_: Exception) {
            Regex("""<\s*(?:[\w.-]+:)?ContentProtection\b""").containsMatchIn(xml)
        }
    }
}

internal object HlsAttributes {
    fun parse(header: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var index = 0
        val source = header.trim()
        while (index < source.length) {
            while (index < source.length && (source[index] == ',' || source[index].isWhitespace())) index++
            if (index >= source.length) break
            val equals = source.indexOf('=', index)
            if (equals < 0) break
            val key = source.substring(index, equals).trim()
            index = equals + 1
            if (index < source.length && source[index] == '"') {
                val end = source.indexOf('"', index + 1)
                if (end < 0) {
                    result[key] = source.substring(index + 1)
                    break
                }
                result[key] = source.substring(index + 1, end)
                index = end + 1
            } else {
                val comma = source.indexOf(',', index)
                if (comma < 0) {
                    result[key] = source.substring(index).trim()
                    break
                }
                result[key] = source.substring(index, comma).trim()
                index = comma + 1
            }
        }
        return result
    }
}
