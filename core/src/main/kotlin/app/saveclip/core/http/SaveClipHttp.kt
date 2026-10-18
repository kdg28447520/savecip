package app.saveclip.core.http

import app.saveclip.core.BrowserProfile
import app.saveclip.core.detect.MediaClassifier
import app.saveclip.core.hls.ByteRange
import app.saveclip.core.policy.DownloadGuard
import app.saveclip.core.policy.ExecutableGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

class HttpStatusException(val code: Int, val url: String) : IOException("HTTP $code")

class RangeRejectedException(url: String) : IOException("range rejected $url")

data class ResourceSnapshot(
    val finalUrl: String,
    val status: Int,
    val contentType: String?,
    val totalBytes: Long?,
    val rangesAccepted: Boolean,
    val prefix: ByteArray,
)

data class TextPeek(
    val finalUrl: String,
    val contentType: String,
    val text: String,
)

class SaveClipHttp(base: OkHttpClient = OkHttpClient()) {
    private val client: OkHttpClient = base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun finalUrl(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        execute(url, headers, null).use { response ->
            response.request.url.toString()
        }
    }

    suspend fun peekText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxChars: Int = 512_000,
    ): TextPeek = withContext(Dispatchers.IO) {
        execute(url, headers, null).use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code, url)
            val type = response.header("Content-Type").orEmpty()
            val resolved = response.request.url.toString()
            if (!isTextual(type)) return@withContext TextPeek(resolved, type, "")
            val body = response.body ?: return@withContext TextPeek(resolved, type, "")
            val text = body.charStream().use { reader ->
                val out = StringBuilder()
                val buffer = CharArray(8192)
                while (out.length < maxChars) {
                    val count = reader.read(buffer, 0, minOf(buffer.size, maxChars - out.length))
                    if (count < 0) break
                    out.append(buffer, 0, count)
                }
                out.toString()
            }
            TextPeek(resolved, type, text)
        }
    }

    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        execute(url, headers, null).use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code, url)
            val text = response.body?.string().orEmpty()
            if (text.length > 6_000_000) error("응답이 너무 큽니다")
            text
        }
    }

    /**
     * HEAD first. When the type, length, or range support is still unknown,
     * reads at most the first kilobyte with `Range: bytes=0-1023` and closes
     * the body so a full file is not pulled just to classify it.
     */
    suspend fun inspect(url: String, headers: Map<String, String> = emptyMap()): ResourceSnapshot =
        withContext(Dispatchers.IO) {
            var finalUrl = url
            var contentType: String? = null
            var total: Long? = null
            var headerSaysRanges = false
            val head = runCatching { execute(url, headers, null, method = "HEAD") }.getOrNull()
            if (head != null) {
                head.use { response ->
                    finalUrl = response.request.url.toString()
                    if (response.isSuccessful) {
                        contentType = response.header("Content-Type")
                        total = response.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 }
                        headerSaysRanges = response.header("Accept-Ranges")
                            ?.contains("bytes", ignoreCase = true) == true
                    }
                }
            }
            val typeKnown = MediaClassifier.classifyContent(contentType, ByteArray(0)) != null
            if (typeKnown && total != null && headerSaysRanges) {
                return@withContext ResourceSnapshot(finalUrl, 200, contentType, total, true, ByteArray(0))
            }
            execute(finalUrl, headers, "bytes=0-1023").use { response ->
                if (response.code >= 400) throw HttpStatusException(response.code, finalUrl)
                val prefix = readPrefix(response, 1024)
                val ranged = response.code == 206
                val length = contentRangeTotal(response.header("Content-Range"))
                    ?: if (response.code == 200) {
                        response.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 }
                            ?: response.body?.contentLength()?.takeIf { it >= 0 }
                    } else {
                        total
                    }
                ResourceSnapshot(
                    finalUrl = response.request.url.toString(),
                    status = response.code,
                    contentType = response.header("Content-Type") ?: contentType,
                    totalBytes = length ?: total,
                    rangesAccepted = ranged,
                    prefix = prefix,
                )
            }
        }

    suspend fun download(
        url: String,
        dest: File,
        headers: Map<String, String> = emptyMap(),
        range: ByteRange? = null,
        strictRange: Boolean = false,
        onBytes: (done: Long, total: Long?) -> Unit = { _, _ -> },
    ): Long = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val expected = range?.length
        if (dest.exists() && dest.length() > 0 && (expected == null || dest.length() == expected)) {
            onBytes(dest.length(), expected ?: dest.length())
            return@withContext dest.length()
        }
        val partial = File(dest.parentFile, dest.name + ".part")
        var existing = if (partial.exists()) partial.length() else 0L
        if (expected != null && existing > expected) {
            partial.delete()
            existing = 0L
        }
        val callSlot = arrayOfNulls<Call>(1)
        val job = coroutineContext[Job]
        val handle = job?.invokeOnCompletion { cause ->
            if (cause != null) callSlot[0]?.cancel()
        }
        try {
            val response = execute(url, headers, rangeHeader(range, existing)) { callSlot[0] = it }
            response.use { resp ->
                if (resp.code == 416 && existing > 0) {
                    movePartial(partial, dest)
                    onBytes(dest.length(), dest.length())
                    return@withContext dest.length()
                }
                if (range != null && strictRange && resp.code != 206) {
                    throw RangeRejectedException(url)
                }
                if (!resp.isSuccessful) throw HttpStatusException(resp.code, url)
                val append = resp.code == 206 && existing > 0
                if (!append && existing > 0) {
                    partial.delete()
                    existing = 0L
                }
                val total = totalBytes(resp, if (append) existing else 0L, range)
                val body = resp.body ?: error("empty body")
                body.byteStream().use { input ->
                    FileOutputStream(partial, append).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = if (append) existing else 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            onBytes(done, total)
                        }
                    }
                }
                movePartial(partial, dest)
                onBytes(dest.length(), total ?: dest.length())
                dest.length()
            }
        } catch (io: IOException) {
            if (!coroutineContext.isActive) throw CancellationException("cancelled").also { it.initCause(io) }
            throw io
        } finally {
            handle?.dispose()
        }
    }

    private fun execute(
        url: String,
        headers: Map<String, String>,
        rangeHeader: String?,
        method: String = "GET",
        onCall: (Call) -> Unit = {},
    ): Response {
        var currentUrl = url
        var hops = 0
        while (true) {
            DownloadGuard.checkUrl(currentUrl)
            val builder = Request.Builder().url(currentUrl)
            if (method.equals("HEAD", ignoreCase = true)) builder.head()
            applyHeaders(builder, headers)
            if (rangeHeader != null) builder.header("Range", rangeHeader)
            val request = builder.build()
            val call = client.newCall(request)
            onCall(call)
            val response = call.execute()
            if (isRedirect(response.code) && hops < 8) {
                val location = response.header("Location")
                response.close()
                if (location.isNullOrBlank()) throw IOException("redirect")
                val next = request.url.resolve(location) ?: throw IOException("bad redirect")
                currentUrl = next.toString()
                hops++
                continue
            }
            val type = response.header("Content-Type")
            if (ExecutableGuard.isBlockedMime(type)) {
                response.close()
                throw app.saveclip.core.policy.ExecutableBlockedException()
            }
            return response
        }
    }

    private fun applyHeaders(builder: Request.Builder, headers: Map<String, String>) {
        var hasUserAgent = false
        var hasAccept = false
        for ((key, value) in headers) {
            if (value.isBlank() || key.equals("Range", ignoreCase = true)) continue
            builder.header(key, value)
            if (key.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
            if (key.equals("Accept", ignoreCase = true)) hasAccept = true
        }
        if (!hasUserAgent) builder.header("User-Agent", BrowserProfile.USER_AGENT)
        if (!hasAccept) builder.header("Accept", "*/*")
    }

    private fun rangeHeader(range: ByteRange?, existing: Long): String? {
        if (range == null && existing <= 0L) return null
        val start = (range?.start ?: 0L) + existing
        val end = range?.endInclusive
        return if (end != null) "bytes=$start-$end" else "bytes=$start-"
    }

    private fun totalBytes(response: Response, already: Long, range: ByteRange?): Long? {
        if (range != null) return range.length
        val contentRange = response.header("Content-Range")
        val slash = contentRange?.substringAfter("/", "")?.toLongOrNull()
        if (slash != null && slash > 0) return slash
        val length = response.body?.contentLength() ?: -1
        if (length >= 0 && response.code == 200) return length
        if (length >= 0 && response.code == 206) return already + length
        return null
    }

    private fun movePartial(partial: File, dest: File) {
        if (dest.exists()) dest.delete()
        if (!partial.renameTo(dest)) {
            partial.copyTo(dest, overwrite = true)
            partial.delete()
        }
    }

    private fun readPrefix(response: Response, max: Int): ByteArray {
        val body = response.body ?: return ByteArray(0)
        body.byteStream().use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(256)
            var remaining = max
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (read < 0) break
                out.write(buffer, 0, read)
                remaining -= read
            }
            return out.toByteArray()
        }
    }

    private fun contentRangeTotal(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val total = header.substringAfter("/", "").trim()
        if (total.isEmpty() || total == "*") return null
        return total.toLongOrNull()?.takeIf { it >= 0 }
    }

    private fun isRedirect(code: Int): Boolean = code == 301 || code == 302 || code == 303 || code == 307 || code == 308

    private fun isTextual(contentType: String): Boolean {
        if (contentType.isBlank()) return true
        val type = contentType.lowercase()
        return type.contains("json") ||
            type.contains("text") ||
            type.contains("xml") ||
            type.contains("html") ||
            type.contains("javascript")
    }
}
