package app.saveclip.core.detect

import app.saveclip.core.http.SaveClipHttp
import app.saveclip.core.policy.ExecutableBlockedException
import app.saveclip.core.policy.YouTubeBlockedException
import app.saveclip.core.policy.YouTubeBlocklist
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

data class ProbeFetch(
    val contentType: String?,
    val prefix: ByteArray,
)

/**
 * Classifies extensionless media addresses with one HEAD or a 1KB range peek.
 * Results, including "not media", stay in an LRU cache. Each host has its own
 * in-flight limit so a page full of candidates cannot open unbounded connections.
 */
fun defaultExtensionlessProbe(http: SaveClipHttp = SaveClipHttp()): ExtensionlessProbe {
    return ExtensionlessProbe(
        fetch = { url, headers ->
            val snapshot = http.inspect(url, headers)
            ProbeFetch(snapshot.contentType, snapshot.prefix)
        },
    )
}

class ExtensionlessProbe(
    private val fetch: suspend (url: String, headers: Map<String, String>) -> ProbeFetch,
    private val maxPerHost: Int = 2,
    cacheCapacity: Int = 128,
) {
    private val capacity = cacheCapacity.coerceAtLeast(8)
    private val cache = LinkedHashMap<String, MediaKind?>(capacity, 0.75f, true)
    private val gates = ConcurrentHashMap<String, Semaphore>()
    private val inflight = ConcurrentHashMap.newKeySet<String>()

    suspend fun classify(url: String, headers: Map<String, String> = emptyMap()): MediaKind? {
        val key = cacheKey(url)
        synchronized(cache) {
            if (cache.containsKey(key)) return cache[key]
        }
        if (YouTubeBlocklist.isBlocked(url)) return null
        val accept = headers.entries.firstOrNull { it.key.equals("Accept", ignoreCase = true) }?.value
        if (!MediaClassifier.worthProbing(url, accept)) return null
        if (!inflight.add(key)) return null
        val host = YouTubeBlocklist.hostOf(url) ?: return null.also { inflight.remove(key) }
        val gate = gates.computeIfAbsent(host) { Semaphore(maxPerHost.coerceAtLeast(1)) }
        return try {
            gate.withPermit {
                synchronized(cache) {
                    if (cache.containsKey(key)) return cache[key]
                }
                val sample = fetch(url, headers)
                val kind = MediaClassifier.classifyContent(sample.contentType, sample.prefix)
                remember(key, kind)
                kind
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: YouTubeBlockedException) {
            null
        } catch (_: ExecutableBlockedException) {
            remember(key, null)
            null
        } catch (_: Exception) {
            null
        } finally {
            inflight.remove(key)
        }
    }

    private fun remember(key: String, kind: MediaKind?) {
        synchronized(cache) {
            cache[key] = kind
            while (cache.size > capacity) {
                val eldest = cache.entries.firstOrNull() ?: break
                cache.remove(eldest.key)
            }
        }
    }

    private fun cacheKey(url: String): String {
        val hash = url.indexOf('#')
        return if (hash >= 0) url.substring(0, hash) else url
    }
}
