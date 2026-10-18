package app.saveclip.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.saveclip.AppContainer
import app.saveclip.core.BrowserProfile
import app.saveclip.core.UserMessages
import app.saveclip.core.detect.MediaClassifier
import app.saveclip.core.detect.MediaKind
import app.saveclip.core.extract.ExtractResult
import app.saveclip.core.extract.MediaRequestGate
import app.saveclip.core.extract.RequestDecision
import app.saveclip.core.net.UrlResolver
import app.saveclip.core.policy.ExecutableBlockedException
import app.saveclip.core.policy.ProtectedMediaException
import app.saveclip.core.policy.YouTubeBlockedException
import app.saveclip.core.policy.YouTubeBlocklist
import app.saveclip.core.quality.PlaylistInspector
import app.saveclip.core.quality.QualityChoice
import app.saveclip.download.NewDownload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class DetectedMedia(
    val url: String,
    val mime: String,
    val kind: MediaKind,
    val pageUrl: String,
    val pageTitle: String,
    val label: String? = null,
)

object SnifferScript {
    const val SOURCE = """
        (function() {
          if (window.__saveclipInstalled) return;
          window.__saveclipInstalled = true;
          function absolute(value) {
            if (!value) return "";
            try { return new URL(value, document.baseURI).href; } catch (error) { return ""; }
          }
          function collect() {
            var nodes = document.querySelectorAll("video, audio, source");
            var items = [];
            for (var i = 0; i < nodes.length; i++) {
              var el = nodes[i];
              var raw = el.currentSrc || el.src || el.getAttribute("src");
              var url = absolute(raw);
              if (!url || url.indexOf("blob:") === 0 || url.indexOf("data:") === 0) continue;
              items.push({ url: url, mime: el.type || el.getAttribute("type") || "" });
            }
            return items;
          }
          function send() {
            try {
              SaveClipBridge.report(JSON.stringify({
                title: document.title || "",
                page: location.href,
                items: collect()
              }));
            } catch (error) {}
          }
          function markProtected() {
            window.__saveclipProtected = true;
            try { SaveClipBridge.reportProtected(); } catch (error) {}
          }
          try {
            var requestKey = navigator.requestMediaKeySystemAccess;
            if (requestKey) {
              navigator.requestMediaKeySystemAccess = function() {
                markProtected();
                return requestKey.apply(navigator, arguments);
              };
            }
          } catch (error) {}
          try {
            var proto = window.HTMLMediaElement && HTMLMediaElement.prototype;
            if (proto && proto.setMediaKeys) {
              var setKeys = proto.setMediaKeys;
              proto.setMediaKeys = function(keys) {
                if (keys) markProtected();
                return setKeys.apply(this, arguments);
              };
            }
          } catch (error) {}
          send();
          try {
            var observer = new MutationObserver(function() { send(); });
            observer.observe(document.documentElement, { subtree: true, childList: true, attributes: true, attributeFilter: ["src"] });
          } catch (error) {}
          setInterval(send, 2000);
        })();
    """
}

class BrowserViewModel(
    app: android.app.Application,
    private val container: AppContainer,
) : AndroidViewModel(app) {
    private val main = Handler(Looper.getMainLooper())
    private val protectedPage = AtomicBoolean(false)
    private val youtubeNotified = AtomicBoolean(false)
    private val pageSnapshot = AtomicReference("")
    private var analyzeJob: Job? = null

    private val itemsFlow = MutableStateFlow<List<DetectedMedia>>(emptyList())
    val items = itemsFlow.asStateFlow()
    private val progressFlow = MutableStateFlow(0)
    val progress = progressFlow.asStateFlow()
    private val titleFlow = MutableStateFlow("")
    val title = titleFlow.asStateFlow()
    private val blockedFlow = MutableStateFlow(false)
    val blocked = blockedFlow.asStateFlow()
    private val hasPageFlow = MutableStateFlow(false)
    val hasPage = hasPageFlow.asStateFlow()
    private val addressFlow = MutableStateFlow("")
    val address = addressFlow.asStateFlow()
    private val canGoBackFlow = MutableStateFlow(false)
    val canGoBack = canGoBackFlow.asStateFlow()
    private val choicesFlow = MutableStateFlow<Map<String, List<QualityChoice>>>(emptyMap())
    val choices = choicesFlow.asStateFlow()
    private val selectedFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    val selected = selectedFlow.asStateFlow()
    private val loadingChoiceFlow = MutableStateFlow<String?>(null)
    val loadingChoice = loadingChoiceFlow.asStateFlow()
    private val analyzingFlow = MutableStateFlow(false)
    val analyzing = analyzingFlow.asStateFlow()

    var webView: WebView? = null
        private set

    fun onAddressChange(value: String) {
        addressFlow.value = value
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun obtainWebView(): WebView {
        webView?.let { return it }
        val created = WebView(getApplication()).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.userAgentString = BrowserProfile.USER_AGENT
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.allowFileAccess = false
            isVerticalScrollBarEnabled = false
        }
        runCatching {
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(created, true)
        }
        created.addJavascriptInterface(MediaJsBridge(::onBridgeReport, ::onProtected), "SaveClipBridge")
        created.webViewClient = SaveClipWebViewClient(this)
        created.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressFlow.value = newProgress
                if (newProgress > 60) {
                    try {
                        view?.evaluateJavascript(SnifferScript.SOURCE, null)
                    } catch (_: Throwable) {
                        // Ignore script injection failures.
                    }
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                titleFlow.value = title.orEmpty()
            }
        }
        created.setDownloadListener { url, _, _, mime, _ ->
            val page = created.url ?: addressFlow.value
            onMedia(url, mime, page, titleFlow.value)
        }
        webView = created
        return created
    }

    fun pageSnapshot(): String = pageSnapshot.get()

    fun submit(raw: String) {
        val url = UrlResolver.normalizeUserInput(raw)
        if (url == null) {
            container.messages.post(UserMessages.BAD_URL)
            return
        }
        if (YouTubeBlocklist.isBlocked(url)) {
            blockedFlow.value = true
            container.messages.post(UserMessages.YOUTUBE)
            return
        }
        addressFlow.value = url
        hasPageFlow.value = true
        blockedFlow.value = false
        pageSnapshot.set(url)
        try {
            obtainWebView().loadUrl(url)
        } catch (_: Throwable) {
            container.messages.post(UserMessages.PAGE_FAILED)
        }
        analyzeInput(url)
    }

    fun analyzeInput(raw: String) {
        val normalized = UrlResolver.normalizeUserInput(raw)
        if (normalized != null) addressFlow.value = normalized
        analyzeJob?.cancel()
        analyzeJob = viewModelScope.launch {
            analyzingFlow.value = true
            try {
                when (val result = container.extractor.extract(raw)) {
                    is ExtractResult.Media -> {
                        result.items.forEach { item ->
                            onMedia(item.url, item.mime, item.pageUrl, item.title, item.label)
                        }
                        container.messages.post(UserMessages.FOUND)
                    }
                    is ExtractResult.Rejected -> {
                        if (result.message == UserMessages.YOUTUBE) blockedFlow.value = true
                        container.messages.post(result.message)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                container.messages.post(UserMessages.ANALYZE_FAILED)
            } finally {
                analyzingFlow.value = false
            }
        }
    }

    fun goBack() {
        webView?.goBack()
    }

    fun onPageStarted(url: String) {
        if (!url.startsWith("http")) return
        protectedPage.set(false)
        youtubeNotified.set(false)
        blockedFlow.value = false
        itemsFlow.value = emptyList()
        choicesFlow.value = emptyMap()
        selectedFlow.value = emptyMap()
        addressFlow.value = url
        pageSnapshot.set(url)
        syncBack()
    }

    fun onPageFinished() {
        try {
            webView?.evaluateJavascript(SnifferScript.SOURCE, null)
        } catch (_: Throwable) {
            // The page can be gone before the script runs.
        }
        syncBack()
    }

    fun syncBack() {
        canGoBackFlow.value = webView?.canGoBack() == true
    }

    fun onYouTube(mainFrame: Boolean) {
        if (mainFrame) blockedFlow.value = true
        if (youtubeNotified.compareAndSet(false, true)) {
            container.messages.post(UserMessages.YOUTUBE)
        }
    }

    fun onProtected() {
        if (protectedPage.compareAndSet(false, true)) {
            itemsFlow.value = emptyList()
            container.messages.post(UserMessages.PROTECTED)
        }
    }

    fun onMedia(url: String, mime: String?, pageUrl: String, pageTitle: String, label: String? = null) {
        try {
            if (protectedPage.get()) return
            if (YouTubeBlocklist.isBlocked(url) || YouTubeBlocklist.isBlocked(pageUrl)) {
                onYouTube(false)
                return
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) return
            val kind = MediaClassifier.classify(url, mime) ?: return
            if (app.saveclip.core.policy.ExecutableGuard.isBlockedUrl(url)) return
            val title = pageTitle.ifBlank { titleFlow.value }
            val media = DetectedMedia(url, mime.orEmpty(), kind, pageUrl, title, label)
            main.post {
                itemsFlow.update { current ->
                    val index = current.indexOfFirst { it.url == url }
                    if (index >= 0) {
                        if (current[index].label == null && media.label != null) {
                            current.toMutableList().also { it[index] = media }
                        } else {
                            current
                        }
                    } else {
                        (current + media).takeLast(40)
                    }
                }
            }
        } catch (_: Throwable) {
            // One bad address must not close the app.
        }
    }

    fun ensureChoices(item: DetectedMedia) {
        if (item.kind != MediaKind.HLS && item.kind != MediaKind.DASH) return
        if (choicesFlow.value.containsKey(item.url) || loadingChoiceFlow.value == item.url) return
        viewModelScope.launch {
            loadingChoiceFlow.value = item.url
            try {
                val headers = headerMap(item.pageUrl, item.url)
                val list = when (item.kind) {
                    MediaKind.HLS -> PlaylistInspector.hlsChoices(item.url, headers)
                    MediaKind.DASH -> PlaylistInspector.dashChoices(item.url, headers)
                    else -> emptyList()
                }
                choicesFlow.update { it + (item.url to list) }
                if (list.isNotEmpty()) {
                    selectedFlow.update { current ->
                        if (current.containsKey(item.url)) current else current + (item.url to list.first().id)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: ProtectedMediaException) {
                container.messages.post(UserMessages.PROTECTED)
            } catch (_: YouTubeBlockedException) {
                container.messages.post(UserMessages.YOUTUBE)
            } catch (_: ExecutableBlockedException) {
                container.messages.post(UserMessages.EXECUTABLE)
            } catch (error: Exception) {
                container.messages.post(error.message ?: "목록을 읽지 못했어요")
            } finally {
                loadingChoiceFlow.value = null
            }
        }
    }

    fun select(mediaUrl: String, choiceId: String) {
        selectedFlow.update { it + (mediaUrl to choiceId) }
    }

    fun enqueue(item: DetectedMedia) {
        if (protectedPage.get()) {
            container.messages.post(UserMessages.PROTECTED)
            return
        }
        viewModelScope.launch {
            try {
                val choice = choicesFlow.value[item.url]?.firstOrNull { it.id == selectedFlow.value[item.url] }
                container.repository.enqueue(
                    NewDownload(
                        mediaUrl = item.url,
                        pageUrl = item.pageUrl,
                        title = titleFlow.value.ifBlank { item.pageTitle }.ifBlank { "영상" },
                        kind = item.kind,
                        qualityLabel = choice?.label ?: item.label ?: kindLabel(item.kind),
                        variantUrl = if (item.kind == MediaKind.HLS) choice?.id else null,
                        videoRepresentationId = if (item.kind == MediaKind.DASH) choice?.id else null,
                        audioRepresentationId = null,
                        headers = headerMap(item.pageUrl, item.url),
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: YouTubeBlockedException) {
                container.messages.post(UserMessages.YOUTUBE)
            } catch (_: ProtectedMediaException) {
                container.messages.post(UserMessages.PROTECTED)
            } catch (_: ExecutableBlockedException) {
                container.messages.post(UserMessages.EXECUTABLE)
            } catch (_: Throwable) {
                container.messages.post(UserMessages.ENQUEUE_FAILED)
            }
        }
    }

    private fun onBridgeReport(json: String) {
        try {
            val obj = runCatching { JSONObject(json) }.getOrNull() ?: return
            val page = obj.optString("page")
            val title = obj.optString("title")
            if (title.isNotBlank()) main.post { titleFlow.value = title }
            val items = obj.optJSONArray("items") ?: return
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                onMedia(item.optString("url"), item.optString("mime"), page, title)
            }
        } catch (_: Throwable) {
            // Bridge payloads are untrusted page data.
        }
    }

    private fun headerMap(pageUrl: String, mediaUrl: String): Map<String, String> {
        val headers = linkedMapOf(
            "User-Agent" to BrowserProfile.USER_AGENT,
            "Accept" to "*/*",
            "Accept-Language" to "ko-KR,ko;q=0.9,en;q=0.5",
        )
        if (pageUrl.startsWith("http")) headers["Referer"] = pageUrl
        val cookie = runCatching { CookieManager.getInstance().getCookie(mediaUrl) }.getOrNull()
        if (!cookie.isNullOrBlank()) headers["Cookie"] = cookie
        return headers
    }

    override fun onCleared() {
        webView?.destroy()
        webView = null
    }

    private fun kindLabel(kind: MediaKind): String = when (kind) {
        MediaKind.HLS -> "HLS"
        MediaKind.DASH -> "DASH"
        MediaKind.WEBM -> "WebM"
        MediaKind.PROGRESSIVE_MP4 -> "MP4"
    }
}

private class MediaJsBridge(
    private val onReport: (String) -> Unit,
    private val onProtected: () -> Unit,
) {
    @JavascriptInterface
    fun report(json: String) = onReport(json)

    @JavascriptInterface
    fun reportProtected() = onProtected()
}

private class SaveClipWebViewClient(
    private val model: BrowserViewModel,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        return try {
            val url = request.url?.toString().orEmpty()
            val scheme = request.url?.scheme?.lowercase().orEmpty()
            if (scheme != "http" && scheme != "https") return true
            if (YouTubeBlocklist.isBlocked(url)) {
                model.onYouTube(request.isForMainFrame)
                return true
            }
            false
        } catch (_: Throwable) {
            true
        }
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        return try {
            val url = request.url?.toString().orEmpty()
            when (val decision = MediaRequestGate.inspect(url, model.pageSnapshot(), request.isForMainFrame)) {
                is RequestDecision.Block -> {
                    if (decision.youtube) model.onYouTube(request.isForMainFrame)
                    blocked()
                }
                is RequestDecision.Observe -> {
                    model.onMedia(decision.url, null, decision.pageUrl, model.title.value)
                    null
                }
                RequestDecision.Allow -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        model.onPageStarted(url)
    }

    override fun onPageFinished(view: WebView, url: String) {
        model.onPageFinished()
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        model.syncBack()
    }

    private fun blocked(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            403,
            "Blocked",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )
    }
}

fun kindLabel(kind: MediaKind): String = when (kind) {
    MediaKind.HLS -> "HLS"
    MediaKind.DASH -> "DASH"
    MediaKind.WEBM -> "WebM"
    MediaKind.PROGRESSIVE_MP4 -> "MP4"
}

fun fileLabel(url: String): String {
    val path = Uri.parse(url).lastPathSegment ?: url
    return path.take(64)
}
