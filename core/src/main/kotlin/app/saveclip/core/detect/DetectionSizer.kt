package app.saveclip.core.detect

import app.saveclip.core.dash.DashMpdParser
import app.saveclip.core.dash.DashTrack
import app.saveclip.core.hls.HlsParseResult
import app.saveclip.core.hls.HlsPlaylistParser
import app.saveclip.core.hls.HlsVariant
import app.saveclip.core.http.SaveClipHttp
import app.saveclip.core.policy.YouTubeBlocklist
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToLong

enum class SizeKind {
    PROGRESSIVE,
    HLS,
    DASH,
    IMAGE,
}

data class ProbeSample(
    val totalBytes: Long?,
    val contentType: String?,
    val prefix: ByteArray,
)

/**
 * Size, resolution, and duration for the detection sheet.
 * Lookups are cached, one host never has more than [maxPerHost] probes in flight,
 * and the caller's page headers (user agent, referer, cookie) are forwarded as-is.
 * YouTube addresses are refused before any request. Playlists are read as text only;
 * segments and keys are not fetched.
 */
fun defaultDetectionSizer(http: SaveClipHttp = SaveClipHttp()): DetectionSizer {
    return DetectionSizer(
        sample = { url, headers ->
            val snapshot = http.inspect(url, headers)
            ProbeSample(snapshot.totalBytes, snapshot.contentType, snapshot.prefix)
        },
        loadText = { url, headers -> http.getText(url, headers) },
    )
}

class DetectionSizer(
    private val sample: suspend (url: String, headers: Map<String, String>) -> ProbeSample,
    private val loadText: suspend (url: String, headers: Map<String, String>) -> String,
    private val maxPerHost: Int = 2,
    cacheCapacity: Int = 128,
) {
    private val capacity = cacheCapacity.coerceAtLeast(8)
    private val facts = LinkedHashMap<String, MediaFacts>(capacity, 0.75f, true)
    private val texts = LinkedHashMap<String, String>(capacity, 0.75f, true)
    private val gates = ConcurrentHashMap<String, Semaphore>()
    private val inflight = ConcurrentHashMap<String, CompletableDeferred<MediaFacts?>>()

    suspend fun lookup(
        url: String,
        kind: SizeKind,
        headers: Map<String, String> = emptyMap(),
        variantId: String? = null,
    ): MediaFacts? {
        if (YouTubeBlocklist.isBlocked(url)) return null
        val key = factKey(url, kind, variantId)
        synchronized(facts) {
            if (facts.containsKey(key)) return facts[key]
        }
        val created = CompletableDeferred<MediaFacts?>()
        val prior = inflight.putIfAbsent(key, created)
        if (prior != null) return prior.await()
        return try {
            val value = compute(url, kind, headers, variantId)
            if (value != null) remember(facts, key, value)
            created.complete(value)
            value
        } catch (cancelled: CancellationException) {
            created.cancel(cancelled)
            throw cancelled
        } catch (_: Exception) {
            created.complete(null)
            null
        } finally {
            inflight.remove(key, created)
        }
    }

    private suspend fun compute(
        url: String,
        kind: SizeKind,
        headers: Map<String, String>,
        variantId: String?,
    ): MediaFacts? {
        return when (kind) {
            SizeKind.HLS -> hls(url, headers, variantId)
            SizeKind.DASH -> dash(url, headers, variantId)
            SizeKind.PROGRESSIVE -> progressive(url, headers, peek = true)
            SizeKind.IMAGE -> progressive(url, headers, peek = false)
        }
    }

    private suspend fun progressive(url: String, headers: Map<String, String>, peek: Boolean): MediaFacts {
        val probed = cachedSample(url, headers)
        val meta = if (peek) Mp4Peek.read(probed.prefix) else MediaFacts()
        return MediaFacts(
            bytes = probed.totalBytes?.takeIf { it >= 0L },
            approximate = false,
            width = meta.width,
            height = meta.height,
            durationMs = meta.durationMs,
        )
    }

    private suspend fun hls(url: String, headers: Map<String, String>, variantId: String?): MediaFacts? {
        val text = cachedText(url, headers) ?: return null
        return when (val parsed = HlsPlaylistParser.parse(text, url)) {
            is HlsParseResult.Master -> {
                val variant = chooseVariant(parsed.variants, variantId) ?: return MediaFacts()
                if (YouTubeBlocklist.isBlocked(variant.url)) {
                    return MediaFacts(width = variant.width, height = variant.height)
                }
                val seconds = durationSeconds(variant.url, text, url, headers)
                val bytes = if (seconds != null && variant.bandwidth > 0) {
                    StreamEstimate.bytes(variant.bandwidth.toLong(), seconds)
                } else {
                    null
                }
                MediaFacts(
                    bytes = bytes,
                    approximate = bytes != null,
                    width = variant.width,
                    height = variant.height,
                    durationMs = seconds?.let { (it * 1000.0).roundToLong() },
                )
            }
            is HlsParseResult.Media -> {
                val seconds = parsed.playlist.segments.sumOf { it.durationSeconds }
                MediaFacts(
                    durationMs = seconds.takeIf { it > 0.0 }?.let { (it * 1000.0).roundToLong() },
                )
            }
        }
    }

    private suspend fun durationSeconds(
        variantUrl: String,
        masterText: String,
        masterUrl: String,
        headers: Map<String, String>,
    ): Double? {
        val text = if (variantUrl == masterUrl) {
            masterText
        } else {
            cachedText(variantUrl, headers) ?: return null
        }
        val parsed = runCatching { HlsPlaylistParser.parse(text, variantUrl) }.getOrNull() ?: return null
        val media = (parsed as? HlsParseResult.Media)?.playlist ?: return null
        val seconds = media.segments.sumOf { it.durationSeconds }
        return seconds.takeIf { it > 0.0 }
    }

    private fun chooseVariant(variants: List<HlsVariant>, variantId: String?): HlsVariant? {
        if (variants.isEmpty()) return null
        if (!variantId.isNullOrBlank()) {
            variants.firstOrNull { it.url == variantId }?.let { return it }
        }
        return variants.maxBy { it.bandwidth }
    }

    private suspend fun dash(url: String, headers: Map<String, String>, variantId: String?): MediaFacts? {
        val text = cachedText(url, headers) ?: return null
        val manifest = DashMpdParser.parse(text, url)
        val track = chooseTrack(manifest.tracks, variantId) ?: return MediaFacts()
        val bytes = StreamEstimate.bytesFromMillis(track.bandwidth, manifest.durationMillis)
        return MediaFacts(
            bytes = bytes,
            approximate = bytes != null,
            width = track.width,
            height = track.height,
            durationMs = manifest.durationMillis.takeIf { it > 0L },
        )
    }

    private fun chooseTrack(tracks: List<DashTrack>, variantId: String?): DashTrack? {
        val videos = tracks.filter { it.contentType == "video" && !it.protected }
        if (videos.isEmpty()) return null
        if (!variantId.isNullOrBlank()) {
            videos.firstOrNull { it.id == variantId }?.let { return it }
        }
        return videos.maxBy { it.bandwidth }
    }

    private suspend fun cachedSample(url: String, headers: Map<String, String>): ProbeSample {
        if (YouTubeBlocklist.isBlocked(url)) error("youtube")
        val host = YouTubeBlocklist.hostOf(url) ?: error("host")
        return gate(host).withPermit { sample(url, headers) }
    }

    private suspend fun cachedText(url: String, headers: Map<String, String>): String? {
        if (YouTubeBlocklist.isBlocked(url)) return null
        val key = cacheKey(url)
        synchronized(texts) { texts[key]?.let { return it } }
        val host = YouTubeBlocklist.hostOf(url) ?: return null
        val text = gate(host).withPermit {
            synchronized(texts) { texts[key]?.let { return it } }
            loadText(url, headers)
        }
        if (text.length > 6_000_000) return null
        remember(texts, key, text)
        return text
    }

    private fun gate(host: String): Semaphore {
        return gates.computeIfAbsent(host) { Semaphore(maxPerHost.coerceAtLeast(1)) }
    }

    private fun <T> remember(cache: LinkedHashMap<String, T>, key: String, value: T) {
        synchronized(cache) {
            cache[key] = value
            while (cache.size > capacity) {
                val eldest = cache.entries.firstOrNull() ?: break
                cache.remove(eldest.key)
            }
        }
    }

    private fun factKey(url: String, kind: SizeKind, variantId: String?): String {
        return kind.name + "|" + variantId.orEmpty() + "|" + cacheKey(url)
    }

    private fun cacheKey(url: String): String {
        val hash = url.indexOf('#')
        return if (hash >= 0) url.substring(0, hash) else url
    }
}
