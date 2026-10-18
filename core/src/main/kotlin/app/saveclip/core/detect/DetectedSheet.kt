package app.saveclip.core.detect

import app.saveclip.core.policy.YouTubeBlocklist
import kotlin.math.abs
import kotlin.math.max

data class ClipInput(
    val url: String,
    val kind: MediaKind,
    val pageUrl: String,
    val pageTitle: String,
    val posterUrl: String? = null,
)

data class SheetVariant(
    val id: String,
    val height: Int? = null,
    val bandwidth: Long = 0,
)

data class PhotoInput(
    val url: String,
    val pageUrl: String,
    val pageTitle: String,
)

data class QualityChip(
    val id: String,
    val sourceUrl: String,
    val variantId: String?,
    val kind: MediaKind,
    val headline: String,
    val sizeLabel: String?,
    val original: Boolean,
    val bytes: Long?,
    val height: Int?,
    val pending: Boolean = false,
)

data class VideoCard(
    val id: String,
    val title: String,
    val domain: String,
    val meta: String,
    val durationLabel: String?,
    val durationMs: Long?,
    val posterUrl: String?,
    val chips: List<QualityChip>,
    val detailUrls: List<String>,
) {
    val short: Boolean get() = durationMs != null && durationMs < DetectedSheet.SHORT_MS
}

data class PhotoCard(
    val url: String,
    val title: String,
)

data class BuiltSheet(
    val videos: List<VideoCard>,
    val short: List<VideoCard>,
    val photos: List<PhotoCard>,
    val selectedId: String?,
    val selectedChipId: String?,
) {
    val videoCount: Int get() = videos.size + short.size
    val photoCount: Int get() = photos.size
}

object Notices {
    const val COPYRIGHT = "저작권이 있는 영상은 권리자의 허락을 받은 경우에만 저장하고, 다시 올리지 마세요."
    const val PRIVATE_FOLDER = "여기 있는 파일은 갤러리와 다른 앱에 보이지 않아요. 앱을 삭제하면 이 폴더의 파일도 함께 지워져요."
    const val DRM = "이 영상은 받을 수 없어요. 보호된 스트림(DRM)이라 저장이 막혀 있어요."
}

object DetectedSheet {
    const val SHORT_MS = 10_000L

    fun build(
        clips: List<ClipInput>,
        photos: List<PhotoInput>,
        facts: Map<String, MediaFacts>,
        variants: Map<String, List<SheetVariant>>,
    ): BuiltSheet {
        val groups = group(clips, facts, variants)
        val cards = groups.mapIndexed { index, group -> card(group, facts, variants, index + 1) }
        val ordered = cards.sortedWith(
            compareBy<VideoCard> { it.durationMs == null }
                .thenByDescending { it.durationMs ?: 0L }
                .thenByDescending { it.chips.maxOfOrNull { chip -> chip.bytes ?: 0L } ?: 0L },
        )
        val main = ordered.filter { !it.short }
        val short = ordered.filter { it.short }
        val selected = main.firstOrNull() ?: short.firstOrNull()
        return BuiltSheet(
            videos = main,
            short = short,
            photos = photos.mapIndexed { index, photo ->
                PhotoCard(photo.url, photoTitle(photo.pageTitle, photo.pageUrl, index + 1))
            },
            selectedId = selected?.id,
            selectedChipId = selected?.chips?.firstOrNull { it.original }?.id ?: selected?.chips?.firstOrNull()?.id,
        )
    }

    fun cta(audio: Boolean, chip: QualityChip?): String {
        if (audio) return "음성만 받기"
        val action = if (chip != null && chip.headline.endsWith("p")) "${chip.headline}로 받기" else "영상 받기"
        val size = chip?.sizeLabel
        return if (size.isNullOrBlank()) action else "$action · $size"
    }

    fun photoCta(count: Int): String = "사진 ${count}장 받기"

    fun shortLabel(count: Int, open: Boolean): String {
        return if (open) "10초 미만 영상 ${count}개 · 접기" else "10초 미만 영상 ${count}개 숨김 · 보기"
    }

    fun visibleCopy(card: VideoCard): String {
        return listOf(card.title, card.meta, card.durationLabel.orEmpty())
            .plus(card.chips.flatMap { listOf(it.headline, it.sizeLabel.orEmpty()) })
            .joinToString(" ")
    }

    private fun group(
        clips: List<ClipInput>,
        facts: Map<String, MediaFacts>,
        variants: Map<String, List<SheetVariant>>,
    ): List<List<ClipInput>> {
        if (clips.isEmpty()) return emptyList()
        val parent = IntArray(clips.size) { it }
        fun find(index: Int): Int {
            var cursor = index
            while (parent[cursor] != cursor) {
                parent[cursor] = parent[parent[cursor]]
                cursor = parent[cursor]
            }
            return cursor
        }
        fun union(left: Int, right: Int) {
            val a = find(left)
            val b = find(right)
            if (a != b) parent[b] = a
        }
        for (i in clips.indices) {
            for (j in i + 1 until clips.size) {
                if (sameVideo(clips[i], clips[j], facts, variants)) union(i, j)
            }
        }
        val buckets = linkedMapOf<Int, MutableList<ClipInput>>()
        clips.forEachIndexed { index, clip ->
            buckets.getOrPut(find(index)) { mutableListOf() }.add(clip)
        }
        return buckets.values.toList()
    }

    private fun sameVideo(
        left: ClipInput,
        right: ClipInput,
        facts: Map<String, MediaFacts>,
        variants: Map<String, List<SheetVariant>>,
    ): Boolean {
        if (left.pageUrl != right.pageUrl) return false
        if (left.url == right.url) return true
        val leftDuration = facts[left.url]?.durationMs
        val rightDuration = facts[right.url]?.durationMs
        if (leftDuration != null && rightDuration != null && !durationsMatch(leftDuration, rightDuration)) return false
        if (listsVariant(variants[left.url], right.url) || listsVariant(variants[right.url], left.url)) return true
        val leftStem = stem(left.url)
        val rightStem = stem(right.url)
        if (leftStem != null && rightStem != null && leftStem == rightStem && !leftStem.startsWith("generic:")) return true
        if (leftDuration != null && rightDuration != null && durationsMatch(leftDuration, rightDuration)) {
            val leftGeneric = leftStem == null || leftStem.startsWith("generic:")
            val rightGeneric = rightStem == null || rightStem.startsWith("generic:")
            if (leftGeneric && rightGeneric) return true
        }
        return false
    }

    private fun durationsMatch(left: Long, right: Long): Boolean {
        val diff = abs(left - right)
        val limit = max(1_500L, max(left, right) / 8L)
        return diff <= limit
    }

    private fun listsVariant(options: List<SheetVariant>?, url: String): Boolean {
        return options.orEmpty().any { it.id == url }
    }

    private fun card(
        group: List<ClipInput>,
        facts: Map<String, MediaFacts>,
        variants: Map<String, List<SheetVariant>>,
        index: Int,
    ): VideoCard {
        val chips = group.flatMap { clip -> chipsFor(clip, facts, variants) }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<QualityChip> { it.height ?: -1 }.thenByDescending { it.bytes ?: -1L })
        val marked = chips.mapIndexed { chipIndex, chip -> chip.copy(original = chipIndex == 0 && chips.size > 1) }
            .ifEmpty {
                listOf(
                    QualityChip(
                        id = group.first().url,
                        sourceUrl = group.first().url,
                        variantId = null,
                        kind = group.first().kind,
                        headline = "기본",
                        sizeLabel = null,
                        original = false,
                        bytes = null,
                        height = null,
                    ),
                )
            }
        val duration = group.mapNotNull { facts[it.url]?.durationMs }.maxOrNull()
        val pageTitle = group.firstNotNullOfOrNull { cleanTitle(it.pageTitle).takeIf { title -> title.isNotBlank() } }.orEmpty()
        val domain = domainOf(group.first().pageUrl).ifBlank { domainOf(group.first().url) }
        val title = when {
            pageTitle.isNotBlank() -> pageTitle
            domain.isNotBlank() -> "$domain 영상 $index"
            else -> "영상 $index"
        }
        val bestHeight = marked.firstOrNull { it.height != null }?.height
        val meta = listOfNotNull(domain.takeIf { it.isNotBlank() }, bestHeight?.let { "최고 ${it}p" })
            .joinToString(" · ")
            .ifBlank { "영상" }
        return VideoCard(
            id = group.joinToString("|") { it.url },
            title = title,
            domain = domain,
            meta = meta,
            durationLabel = FactFormat.duration(duration),
            durationMs = duration,
            posterUrl = group.firstNotNullOfOrNull { it.posterUrl?.takeIf { url -> url.startsWith("http") } },
            chips = marked,
            detailUrls = group.map { it.url },
        )
    }

    private fun chipsFor(
        clip: ClipInput,
        facts: Map<String, MediaFacts>,
        variants: Map<String, List<SheetVariant>>,
    ): List<QualityChip> {
        val fact = facts[clip.url]
        val options = variants[clip.url].orEmpty()
        if ((clip.kind == MediaKind.HLS || clip.kind == MediaKind.DASH) && options.isNotEmpty()) {
            return options.map { option ->
                val height = option.height ?: fact?.height
                val bytes = estimatedBytes(option.bandwidth, fact?.durationMs) ?: fact?.bytes?.takeIf { options.size == 1 }
                QualityChip(
                    id = clip.url + "|" + option.id,
                    sourceUrl = clip.url,
                    variantId = option.id,
                    kind = clip.kind,
                    headline = height?.let { "${it}p" } ?: "기본",
                    sizeLabel = FactFormat.size(bytes, approximate = bytes != null),
                    original = false,
                    bytes = bytes,
                    height = height,
                    pending = bytes == null && !facts.containsKey(clip.url),
                )
            }
        }
        val height = fact?.height ?: heightInUrl(clip.url)
        return listOf(
            QualityChip(
                id = clip.url,
                sourceUrl = clip.url,
                variantId = null,
                kind = clip.kind,
                headline = height?.let { "${it}p" } ?: "기본",
                sizeLabel = FactFormat.size(fact?.bytes, fact?.approximate == true),
                original = false,
                bytes = fact?.bytes,
                height = height,
                pending = fact == null,
            ),
        )
    }

    private fun estimatedBytes(bandwidth: Long, durationMs: Long?): Long? {
        if (durationMs == null) return null
        return StreamEstimate.bytesFromMillis(bandwidth, durationMs)
    }

    private fun heightInUrl(url: String): Int? {
        val match = Regex(
            """(?:^|[_\-.])(2160|1440|1080|720|480|360|240|144)p?(?:$|[_\-.])""",
            RegexOption.IGNORE_CASE,
        ).find(url) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private fun stem(url: String): String? {
        val name = DetectedNames.fileName(url).substringBeforeLast('.', DetectedNames.fileName(url))
        if (name.isBlank()) return null
        if (DetectedNames.isOpaque(name) || DetectedNames.isOpaque("$name.mp4")) return null
        var body = name.lowercase()
        body = body.replace(Regex("""(2160|1440|1080|720|480|360|240|144)p?"""), "")
        body = body.replace(Regex("""[_-]?(4k|uhd|fhd|hd|sd|high|low|mid|hq|lq)"""), "")
        body = body.replace(Regex("""[_-]+"""), " ").trim()
        if (body.isBlank()) return null
        if (body in GENERIC) return "generic:$body"
        return body
    }

    fun cleanTitle(raw: String): String {
        val text = raw.replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return ""
        val parts = text.split(Regex("""\s+[-|–—]\s+"""))
        if (parts.size >= 2) {
            val head = parts.dropLast(1).joinToString(" - ").trim()
            val tail = parts.last().trim()
            if (head.length >= 2 && tail.length in 1..40) return head
        }
        return text
    }

    fun domainOf(url: String): String {
        val host = YouTubeBlocklist.hostOf(url) ?: return ""
        return host.removePrefix("www.")
    }

    private fun photoTitle(pageTitle: String, pageUrl: String, index: Int): String {
        val cleaned = cleanTitle(pageTitle)
        if (cleaned.isNotBlank()) return cleaned
        val domain = domainOf(pageUrl)
        return if (domain.isNotBlank()) "$domain 사진 $index" else "사진 $index"
    }

    private val GENERIC = setOf("playlist", "master", "index", "video", "media", "manifest", "stream")
}
