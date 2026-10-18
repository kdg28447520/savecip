package app.saveclip.core.dash

import app.saveclip.core.net.UrlResolver
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToLong

data class DashTrack(
    val id: String,
    val contentType: String,
    val mimeType: String?,
    val codecs: String?,
    val bandwidth: Long,
    val width: Int?,
    val height: Int?,
    val roles: List<String>,
    val initializationUrl: String?,
    val mediaUrls: List<String>,
    val protected: Boolean,
)

data class DashManifest(
    val url: String,
    val durationMillis: Long,
    val tracks: List<DashTrack>,
    val containsContentProtection: Boolean,
)

object DashMpdParser {
    fun parse(xml: String, manifestUrl: String): DashManifest {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isExpandEntityReferences = false
        runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        val document = factory.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))
        val root = document.documentElement
        val durationMillis = root.attr("mediaPresentationDuration")?.let(::parseXsDurationMillis) ?: 0L
        val protectedDocument = root.hasDescendant("ContentProtection")
        val mpdBase = resolveBase(manifestUrl, root.children("BaseURL").mapNotNull { it.textContent?.trim() })
        val periods = root.children("Period")
        val tracks = mutableListOf<DashTrack>()
        periods.forEachIndexed { index, period ->
            val start = period.attr("start")?.let(::parseXsDurationMillis) ?: 0L
            val periodDuration = period.attr("duration")?.let(::parseXsDurationMillis)
                ?: if (index < periods.lastIndex) {
                    val nextStart = periods[index + 1].attr("start")?.let(::parseXsDurationMillis) ?: start
                    (nextStart - start).coerceAtLeast(0L)
                } else {
                    (durationMillis - start).coerceAtLeast(0L)
                }
            val periodBase = resolveBase(mpdBase, period.children("BaseURL").mapNotNull { it.textContent?.trim() })
            val periodInfo = readSegmentInfo(period)
            for (adaptation in period.children("AdaptationSet")) {
                val adaptationBase = resolveBase(periodBase, adaptation.children("BaseURL").mapNotNull { it.textContent?.trim() })
                val adaptationInfo = readSegmentInfo(adaptation) ?: periodInfo
                val adaptationProtected = adaptation.hasDescendant("ContentProtection")
                val roles = adaptation.children("Role").mapNotNull { it.attr("value") }
                val adaptationMime = adaptation.attr("mimeType")
                val adaptationType = adaptation.attr("contentType")
                for (representation in adaptation.children("Representation")) {
                    val id = representation.attr("id") ?: continue
                    val mime = representation.attr("mimeType") ?: adaptationMime
                    val contentType = inferType(representation.attr("contentType") ?: adaptationType, mime) ?: continue
                    if (contentType != "video" && contentType != "audio") continue
                    val repRoles = roles + representation.children("Role").mapNotNull { it.attr("value") }
                    if (repRoles.any { it.equals("subtitle", true) || it.equals("caption", true) }) continue
                    val repBase = resolveBase(
                        adaptationBase,
                        representation.children("BaseURL").mapNotNull { it.textContent?.trim() },
                    )
                    val info = readSegmentInfo(representation) ?: adaptationInfo
                    val bandwidth = representation.longAttr("bandwidth") ?: 0L
                    val built = buildSegments(info, repBase, id, bandwidth, periodDuration)
                    val trackProtected = adaptationProtected || representation.hasDescendant("ContentProtection")
                    tracks += DashTrack(
                        id = id,
                        contentType = contentType,
                        mimeType = mime,
                        codecs = representation.attr("codecs") ?: adaptation.attr("codecs"),
                        bandwidth = bandwidth,
                        width = representation.intAttr("width") ?: adaptation.intAttr("width"),
                        height = representation.intAttr("height") ?: adaptation.intAttr("height"),
                        roles = repRoles,
                        initializationUrl = built.first,
                        mediaUrls = built.second,
                        protected = trackProtected,
                    )
                }
            }
        }
        return DashManifest(
            url = manifestUrl,
            durationMillis = durationMillis,
            tracks = tracks,
            containsContentProtection = protectedDocument,
        )
    }

    internal fun parseXsDurationMillis(raw: String): Long {
        val match = DURATION.matchEntire(raw.trim()) ?: error("duration: $raw")
        val sign = if (match.groupValues[1] == "-") -1L else 1L
        val years = match.groupValues[2].toLongOrNull() ?: 0L
        val months = match.groupValues[3].toLongOrNull() ?: 0L
        val days = match.groupValues[4].toLongOrNull() ?: 0L
        val hours = match.groupValues[5].toLongOrNull() ?: 0L
        val minutes = match.groupValues[6].toLongOrNull() ?: 0L
        val seconds = match.groupValues[7].toDoubleOrNull() ?: 0.0
        val whole = (years * 365L + months * 30L + days) * 86_400_000L +
            hours * 3_600_000L +
            minutes * 60_000L
        return sign * (whole + (seconds * 1000.0).roundToLong())
    }
}

private val DURATION = Regex(
    """^(-)?P(?:(\d+)Y)?(?:(\d+)M)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$""",
)

private data class TimelineEntry(val t: Long?, val d: Long, val r: Long)

private data class SegmentTemplate(
    val media: String?,
    val initialization: String?,
    val duration: Long?,
    val timescale: Long,
    val startNumber: Long,
    val timeline: List<TimelineEntry>?,
)

private data class SegmentListInfo(val initialization: String?, val media: List<String>)

private data class SegmentInfo(
    val template: SegmentTemplate?,
    val list: SegmentListInfo?,
)

private fun readSegmentInfo(element: Element): SegmentInfo? {
    val template = element.children("SegmentTemplate").firstOrNull()
    val list = element.children("SegmentList").firstOrNull()
    val base = element.children("SegmentBase").firstOrNull()
    if (template == null && list == null && base == null) return null
    val parsedTemplate = template?.let { node ->
        val timeline = node.children("SegmentTimeline").firstOrNull()?.children("S")?.map { s ->
            TimelineEntry(
                t = s.longAttr("t"),
                d = s.longAttr("d") ?: 0L,
                r = s.longAttr("r") ?: 0L,
            )
        }
        SegmentTemplate(
            media = node.attr("media"),
            initialization = node.attr("initialization"),
            duration = node.longAttr("duration"),
            timescale = node.longAttr("timescale") ?: 1L,
            startNumber = node.longAttr("startNumber") ?: 1L,
            timeline = timeline,
        )
    }
    val parsedList = when {
        list != null -> SegmentListInfo(
            initialization = list.children("Initialization").firstOrNull()?.attr("sourceURL"),
            media = list.children("SegmentURL").mapNotNull { it.attr("media") },
        )
        base != null -> SegmentListInfo(
            initialization = base.children("Initialization").firstOrNull()?.attr("sourceURL"),
            media = emptyList(),
        )
        else -> null
    }
    return SegmentInfo(parsedTemplate, parsedList)
}

private fun buildSegments(
    info: SegmentInfo?,
    base: String,
    repId: String,
    bandwidth: Long,
    periodMillis: Long,
): Pair<String?, List<String>> {
    val template = info?.template
    if (template?.media != null) {
        val init = template.initialization?.let {
            UrlResolver.resolve(base, substitute(it, template.startNumber, 0L, repId, bandwidth))
        }
        val urls = mutableListOf<String>()
        val timescale = template.timescale.coerceAtLeast(1L)
        val timeline = template.timeline
        if (timeline != null) {
            var number = template.startNumber
            var cursor: Long? = null
            for (entry in timeline) {
                if (urls.size >= 20_000) break
                var time = entry.t ?: cursor ?: 0L
                val repeats = if (entry.r < 0) {
                    val periodUnits = periodUnits(periodMillis, timescale)
                    val remaining = periodUnits - time
                    if (entry.d <= 0L) 1 else ((remaining + entry.d - 1) / entry.d).toInt().coerceAtLeast(1)
                } else {
                    (entry.r + 1).toInt()
                }
                repeat(repeats.coerceAtMost(20_000 - urls.size)) {
                    urls += UrlResolver.resolve(base, substitute(template.media, number, time, repId, bandwidth))
                    time += entry.d
                    number += 1
                }
                cursor = time
            }
        } else if (template.duration != null && template.duration > 0) {
            val periodUnits = periodUnits(periodMillis, timescale)
            val count = ((periodUnits + template.duration - 1) / template.duration).toInt().coerceIn(0, 20_000)
            for (index in 0 until count) {
                val number = template.startNumber + index
                val time = index.toLong() * template.duration
                urls += UrlResolver.resolve(base, substitute(template.media, number, time, repId, bandwidth))
            }
        }
        return init to urls
    }
    val list = info?.list
    if (list != null && (list.media.isNotEmpty() || list.initialization != null)) {
        val init = list.initialization?.let { UrlResolver.resolve(base, it) }
        val media = list.media.map { UrlResolver.resolve(base, it) }
        if (media.isEmpty() && !base.endsWith("/")) {
            return init to listOf(base)
        }
        return init to media
    }
    if (!base.endsWith("/")) return null to listOf(base)
    return null to emptyList()
}

private fun periodUnits(periodMillis: Long, timescale: Long): Long {
    return (periodMillis * timescale + 500L) / 1000L
}

internal fun substitute(template: String, number: Long, time: Long, repId: String, bandwidth: Long): String {
    val out = StringBuilder()
    var index = 0
    while (index < template.length) {
        if (template[index] != '$') {
            out.append(template[index])
            index++
            continue
        }
        if (index + 1 < template.length && template[index + 1] == '$') {
            out.append('$')
            index += 2
            continue
        }
        val end = template.indexOf('$', index + 1)
        if (end < 0) {
            out.append(template.substring(index))
            break
        }
        val token = template.substring(index + 1, end)
        val ident = token.substringBefore('%')
        val format = if ('%' in token) token.substring(token.indexOf('%')) else ""
        val value: Any = when (ident) {
            "RepresentationID" -> repId
            "Bandwidth" -> bandwidth
            "Number" -> number
            "Time" -> time
            else -> {
                out.append(template.substring(index, end + 1))
                index = end + 1
                continue
            }
        }
        out.append(formatNumber(value, format))
        index = end + 1
    }
    return out.toString()
}

private fun formatNumber(value: Any, format: String): String {
    if (format.isEmpty() || value !is Long) return value.toString()
    return try {
        String.format(Locale.US, format, value)
    } catch (_: Exception) {
        value.toString()
    }
}

private fun resolveBase(current: String, raws: List<String>): String {
    val raw = raws.firstOrNull { it.isNotBlank() } ?: return current
    val resolved = UrlResolver.resolve(current, raw)
    if (!resolved.endsWith("/") && (raw.endsWith("/") || raw == "." || raw == "./")) {
        return "$resolved/"
    }
    return resolved
}

private fun inferType(contentType: String?, mime: String?): String? {
    val declared = contentType?.lowercase(Locale.US)
    if (declared == "video" || declared == "audio") return declared
    val normalized = mime?.substringBefore(';')?.trim()?.lowercase(Locale.US) ?: return null
    return when {
        normalized.startsWith("video/") -> "video"
        normalized.startsWith("audio/") -> "audio"
        else -> null
    }
}

private fun Element.children(local: String): List<Element> {
    val out = mutableListOf<Element>()
    val nodes = childNodes
    for (i in 0 until nodes.length) {
        val node = nodes.item(i)
        if (node is Element && node.localName == local) out += node
    }
    return out
}

private fun Element.hasDescendant(local: String): Boolean {
    if (localName == local) return true
    val nodes = childNodes
    for (i in 0 until nodes.length) {
        val node = nodes.item(i)
        if (node is Element && node.hasDescendant(local)) return true
    }
    return false
}

private fun Element.attr(name: String): String? = getAttribute(name).takeIf { it.isNotBlank() }

private fun Element.longAttr(name: String): Long? = attr(name)?.toLongOrNull()

private fun Element.intAttr(name: String): Int? = attr(name)?.toIntOrNull()

@Suppress("unused")
private fun Node.localNameOrEmpty(): String = (this as? Element)?.localName.orEmpty()
