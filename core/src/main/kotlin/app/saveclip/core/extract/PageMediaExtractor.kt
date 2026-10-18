package app.saveclip.core.extract

import app.saveclip.core.BrowserProfile
import app.saveclip.core.UserMessages
import app.saveclip.core.detect.MediaKind
import app.saveclip.core.http.SaveClipHttp
import app.saveclip.core.http.TextPeek
import app.saveclip.core.net.UrlResolver
import app.saveclip.core.policy.ExecutableBlockedException
import app.saveclip.core.policy.ExecutableGuard
import app.saveclip.core.policy.ProtectedMediaException
import app.saveclip.core.policy.YouTubeBlockedException
import app.saveclip.core.policy.YouTubeBlocklist
import kotlinx.coroutines.CancellationException

data class ExtractedMedia(
    val url: String,
    val mime: String,
    val kind: MediaKind,
    val pageUrl: String,
    val title: String,
    val label: String,
)

sealed class ExtractResult {
    data class Media(val items: List<ExtractedMedia>) : ExtractResult()
    data class Rejected(val message: String) : ExtractResult()
}

interface TextFetcher {
    suspend fun get(url: String, headers: Map<String, String>): String
    suspend fun resolve(url: String, headers: Map<String, String>): String
    suspend fun peek(url: String, headers: Map<String, String>, maxChars: Int): TextPeek
}

fun defaultPageMediaExtractor(): PageMediaExtractor = PageMediaExtractor(SaveClipTextFetcher(SaveClipHttp()))

class SaveClipTextFetcher(private val http: SaveClipHttp) : TextFetcher {
    override suspend fun get(url: String, headers: Map<String, String>): String = http.getText(url, headers)

    override suspend fun resolve(url: String, headers: Map<String, String>): String = http.finalUrl(url, headers)

    override suspend fun peek(url: String, headers: Map<String, String>, maxChars: Int): TextPeek {
        return http.peekText(url, headers, maxChars)
    }
}

class PageMediaExtractor(
    private val fetch: TextFetcher,
) {
    suspend fun extract(raw: String): ExtractResult {
        return try {
            extractUnsafe(raw, hops = 0)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: YouTubeBlockedException) {
            ExtractResult.Rejected(UserMessages.YOUTUBE)
        } catch (_: ProtectedMediaException) {
            ExtractResult.Rejected(UserMessages.PROTECTED)
        } catch (_: ExecutableBlockedException) {
            ExtractResult.Rejected(UserMessages.EXECUTABLE)
        } catch (_: Exception) {
            ExtractResult.Rejected(UserMessages.ANALYZE_FAILED)
        }
    }

    private suspend fun extractUnsafe(raw: String, hops: Int): ExtractResult {
        val url = UrlResolver.normalizeUserInput(raw) ?: return ExtractResult.Rejected(UserMessages.BAD_URL)
        if (YouTubeBlocklist.isBlocked(url)) return ExtractResult.Rejected(UserMessages.YOUTUBE)
        if (ExecutableGuard.isBlockedUrl(url)) return ExtractResult.Rejected(UserMessages.EXECUTABLE)
        if (NaverLinks.isShortLink(url)) {
            if (hops >= 3) return ExtractResult.Rejected(UserMessages.ANALYZE_FAILED)
            val next = fetch.resolve(url, headers(url))
            if (next == url) return ExtractResult.Rejected(UserMessages.NOT_FOUND)
            return extractUnsafe(next, hops + 1)
        }
        HtmlMediaFinder.direct(url)?.let { return ExtractResult.Media(listOf(it)) }
        val naver = NaverLinks.parse(url)
        if (naver != null) return loadNaver(url, naver)
        return loadHtml(url)
    }

    private suspend fun loadNaver(pageUrl: String, ref: NaverClipRef): ExtractResult {
        var sawEmpty = false
        var sawFailure = false
        for (service in NaverLinks.serviceAttempts(ref)) {
            val api = NaverLinks.cardApiUrl(ref.copy(serviceType = service))
            val json = try {
                fetch.get(api, headers(pageUrl))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (blocked: YouTubeBlockedException) {
                throw blocked
            } catch (protected: ProtectedMediaException) {
                throw protected
            } catch (executable: ExecutableBlockedException) {
                throw executable
            } catch (_: Exception) {
                sawFailure = true
                continue
            }
            when (val parsed = ClipCardParser.parse(json, pageUrl)) {
                is CardParse.Found -> return ExtractResult.Media(parsed.items)
                CardParse.Protected -> return ExtractResult.Rejected(UserMessages.PROTECTED)
                CardParse.Empty -> sawEmpty = true
                CardParse.Invalid -> sawFailure = true
            }
        }
        return if (sawEmpty) {
            ExtractResult.Rejected(UserMessages.NOT_FOUND)
        } else if (sawFailure) {
            ExtractResult.Rejected(UserMessages.ANALYZE_FAILED)
        } else {
            ExtractResult.Rejected(UserMessages.NOT_FOUND)
        }
    }

    private suspend fun loadHtml(pageUrl: String): ExtractResult {
        val peek = fetch.peek(pageUrl, headers(pageUrl), maxChars = 512_000)
        if (YouTubeBlocklist.isBlocked(peek.finalUrl)) return ExtractResult.Rejected(UserMessages.YOUTUBE)
        HtmlMediaFinder.direct(peek.finalUrl, peek.contentType)?.let { direct ->
            return ExtractResult.Media(listOf(direct.copy(pageUrl = pageUrl)))
        }
        val items = HtmlMediaFinder.find(peek.finalUrl, peek.text)
        if (items.isNotEmpty()) return ExtractResult.Media(items)
        return ExtractResult.Rejected(UserMessages.NOT_FOUND)
    }

    private fun headers(referer: String): Map<String, String> {
        return linkedMapOf(
            "User-Agent" to BrowserProfile.USER_AGENT,
            "Accept" to "application/json,text/html;q=0.9,*/*;q=0.8",
            "Accept-Language" to "ko-KR,ko;q=0.9,en;q=0.5",
            "Referer" to referer,
        )
    }
}
