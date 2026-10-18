package app.saveclip.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
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
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature
import androidx.lifecycle.viewModelScope
import app.saveclip.AppContainer
import app.saveclip.core.BrowserProfile
import app.saveclip.core.UserMessages
import app.saveclip.core.browser.AdBlockDecision
import app.saveclip.core.browser.ImageReport
import app.saveclip.core.browser.TabLimits
import app.saveclip.core.detect.DetectedNames
import app.saveclip.core.detect.MediaClassifier
import app.saveclip.core.detect.MediaFacts
import app.saveclip.core.detect.MediaKind
import app.saveclip.core.detect.SizeKind
import app.saveclip.core.download.Delivery
import app.saveclip.core.net.SharedLinks
import app.saveclip.core.policy.ExtraBlocks
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class DetectedMedia(
    val url: String,
    val mime: String,
    val kind: MediaKind,
    val pageUrl: String,
    val pageTitle: String,
    val label: String? = null,
    val posterUrl: String? = null,
)

data class DetectedImage(
    val url: String,
    val pageUrl: String,
    val pageTitle: String,
)

data class BrowserTab(
    val id: String,
    val title: String,
    val url: String,
    val state: Bundle? = null,
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
              var poster = "";
              if (el.tagName === "VIDEO") poster = absolute(el.getAttribute("poster") || "");
              else if (el.parentElement && el.parentElement.tagName === "VIDEO") poster = absolute(el.parentElement.getAttribute("poster") || "");
              items.push({ url: url, mime: el.type || el.getAttribute("type") || "", poster: poster });
            }
            return items;
          }
          function largestFromSrcset(srcset) {
            if (!srcset) return "";
            var parts = srcset.split(",");
            var bestUrl = "";
            var best = -1;
            for (var i = 0; i < parts.length; i++) {
              var bits = parts[i].trim().split(/\s+/);
              if (!bits[0]) continue;
              var score = 1;
              if (bits.length > 1) {
                var desc = bits[bits.length - 1];
                if (desc.slice(-1) === "w") score = parseInt(desc, 10) || 1;
                else if (desc.slice(-1) === "x") score = Math.round((parseFloat(desc) || 1) * 1000);
              }
              if (score > best) { best = score; bestUrl = bits[0]; }
            }
            return bestUrl;
          }
          function collectImages() {
            var imgs = document.querySelectorAll("img");
            var out = [];
            for (var i = 0; i < imgs.length; i++) {
              var el = imgs[i];
              var srcset = el.getAttribute("srcset") || "";
              var raw = largestFromSrcset(srcset) || el.currentSrc || el.src || el.getAttribute("src");
              var url = absolute(raw);
              if (!url || url.indexOf("blob:") === 0 || url.indexOf("data:") === 0) continue;
              out.push({
                url: url,
                width: el.naturalWidth || el.width || 0,
                height: el.naturalHeight || el.height || 0,
                srcset: srcset
              });
            }
            return out;
          }
          function send() {
            try {
              var og = "";
              try {
                var meta = document.querySelector('meta[property="og:image"]');
                if (meta) og = absolute(meta.getAttribute("content") || "");
              } catch (error) {}
              SaveClipBridge.report(JSON.stringify({
                title: document.title || "",
                page: location.href,
                ogImage: og,
                items: collect(),
                images: collectImages()
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
    private val clipboardSuggestionFlow = MutableStateFlow<String?>(null)
    val clipboardSuggestion = clipboardSuggestionFlow.asStateFlow()
    private var hiddenClipboardUrl: String? = null
    private val interceptActive = AtomicBoolean(true)
    private val workerInstalled = AtomicBoolean(false)
    private val tabsFlow = MutableStateFlow(listOf(BrowserTab(UUID.randomUUID().toString(), "새 탭", "")))
    val tabs = tabsFlow.asStateFlow()
    private val activeIdFlow = MutableStateFlow(tabsFlow.value.first().id)
    val activeTabId = activeIdFlow.asStateFlow()
    private val imagesFlow = MutableStateFlow<List<DetectedImage>>(emptyList())
    val images = imagesFlow.asStateFlow()
    private val factsFlow = MutableStateFlow<Map<String, MediaFacts>>(emptyMap())
    val facts = factsFlow.asStateFlow()
    private val protectedFlow = MutableStateFlow<Set<String>>(emptySet())
    val protectedUrls = protectedFlow.asStateFlow()
    private val ogImageFlow = MutableStateFlow("")
    val ogImage = ogImageFlow.asStateFlow()
    private val sizedVariant = ConcurrentHashMap<String, String?>()
    private val factJobs = ConcurrentHashMap<String, Job>()
    @Volatile private var desktopSite = false
    @Volatile private var blockAds = false
    @Volatile private var exceptions = emptySet<String>()

    var webView: WebView? = null
        private set

    init {
        viewModelScope.launch {
            container.database.exceptionDao().observe().collect { rows ->
                exceptions = rows.map { it.host }.toSet()
            }
        }
        viewModelScope.launch {
            val initial = runCatching { container.settings.current() }.getOrNull()
            desktopSite = initial?.desktopSite == true
            blockAds = initial?.blockAds == true
            applyAgent(reload = false)
            container.settings.settings.collect { settings ->
                blockAds = settings.blockAds
                if (settings.desktopSite != desktopSite) {
                    desktopSite = settings.desktopSite
                    applyAgent(reload = hasPageFlow.value)
                }
            }
        }
    }

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
            settings.userAgentString = BrowserProfile.userAgent(desktopSite)
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
        installServiceWorker()
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
                touchActive(title = title.orEmpty())
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
        if (ExtraBlocks.isBlocked(url)) {
            container.messages.post(UserMessages.RIGHTS)
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
        imagesFlow.value = emptyList()
        choicesFlow.value = emptyMap()
        selectedFlow.value = emptyMap()
        clearFacts()
        addressFlow.value = url
        pageSnapshot.set(url)
        touchActive(url = url)
        syncBack()
    }

    fun onPageFinished() {
        try {
            webView?.evaluateJavascript(SnifferScript.SOURCE, null)
        } catch (_: Throwable) {
            // The page can be gone before the script runs.
        }
        val url = webView?.url.orEmpty()
        val pageTitle = titleFlow.value
        if (url.startsWith("http")) {
            touchActive(url = url, title = pageTitle)
            viewModelScope.launch {
                runCatching { container.browser.visit(url, pageTitle.ifBlank { url }) }
            }
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
            clearFacts()
            container.messages.post(UserMessages.PROTECTED)
        }
    }

    fun onMedia(url: String, mime: String?, pageUrl: String, pageTitle: String, label: String? = null, posterUrl: String? = null) {
        try {
            if (protectedPage.get()) return
            if (YouTubeBlocklist.isBlocked(url) || YouTubeBlocklist.isBlocked(pageUrl)) {
                onYouTube(false)
                return
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) return
            val kind = MediaClassifier.classify(url, mime)
            if (kind == null) {
                if (MediaClassifier.worthProbing(url, mime)) {
                    scheduleProbe(url, pageUrl, headerMap(pageUrl, url))
                }
                return
            }
            if (app.saveclip.core.policy.ExecutableGuard.isBlockedUrl(url)) return
            val title = pageTitle.ifBlank { titleFlow.value }
            val poster = posterUrl?.takeIf { it.startsWith("http") } ?: ogImageFlow.value.takeIf { it.startsWith("http") }
            val media = DetectedMedia(url, mime.orEmpty(), kind, pageUrl, title, label, poster)
            main.post {
                var added = false
                itemsFlow.update { current ->
                    val index = current.indexOfFirst { it.url == url }
                    if (index >= 0) {
                        val existing = current[index]
                        val posterUpdate = existing.posterUrl == null && media.posterUrl != null
                        val labelUpdate = existing.label == null && media.label != null
                        if (posterUpdate || labelUpdate) {
                            current.toMutableList().also {
                                it[index] = existing.copy(
                                    label = existing.label ?: media.label,
                                    posterUrl = existing.posterUrl ?: media.posterUrl,
                                    pageTitle = existing.pageTitle.ifBlank { media.pageTitle },
                                )
                            }
                        } else {
                            current
                        }
                    } else {
                        added = true
                        (current + media).takeLast(40)
                    }
                }
                if (added) scheduleSize(url, pageUrl, sizeKind(kind), selectedFlow.value[url])
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
                if (list.isNotEmpty() && !selectedFlow.value.containsKey(item.url)) {
                    selectedFlow.update { it + (item.url to list.first().id) }
                    scheduleSize(item.url, item.pageUrl, sizeKind(item.kind), list.first().id)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: ProtectedMediaException) {
                protectedFlow.update { it + item.url }
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
        val item = itemsFlow.value.firstOrNull { it.url == mediaUrl } ?: return
        scheduleSize(item.url, item.pageUrl, sizeKind(item.kind), choiceId)
    }

    fun refreshSizes() {
        itemsFlow.value.forEach { item ->
            scheduleSize(item.url, item.pageUrl, sizeKind(item.kind), selectedFlow.value[item.url])
        }
        imagesFlow.value.forEach { image ->
            scheduleSize(image.url, image.pageUrl, SizeKind.IMAGE, null)
        }
    }

    fun enqueue(item: DetectedMedia, delivery: String = Delivery.VIDEO) {
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
                        title = displayName(item),
                        kind = item.kind,
                        qualityLabel = choice?.label ?: item.label ?: kindLabel(item.kind),
                        variantUrl = if (item.kind == MediaKind.HLS) choice?.id else null,
                        videoRepresentationId = if (item.kind == MediaKind.DASH) choice?.id else null,
                        audioRepresentationId = null,
                        headers = headerMap(item.pageUrl, item.url),
                        delivery = delivery,
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

    fun onIntercept(
        url: String,
        pageUrl: String,
        mainFrame: Boolean,
        headers: Map<String, String>,
    ): WebResourceResponse? {
        if (!interceptActive.get()) return null
        return try {
            val accept = headers.entries.firstOrNull { it.key.equals("Accept", ignoreCase = true) }?.value
            when (val decision = MediaRequestGate.inspect(url, pageUrl, mainFrame, accept)) {
                is RequestDecision.Block -> {
                    if (decision.youtube) onYouTube(mainFrame)
                    blockedResponse()
                }
                else -> {
                    if (ExtraBlocks.isBlocked(url)) {
                        if (mainFrame) onRights()
                        return blockedResponse()
                    }
                    if (AdBlockDecision.shouldBlock(blockAds, mainFrame, url, pageUrl, container.blocklist, exceptions)) {
                        return emptyResponse()
                    }
                    when (decision) {
                        is RequestDecision.Observe -> {
                            onMedia(decision.url, null, decision.pageUrl, title.value)
                            null
                        }
                        is RequestDecision.Probe -> {
                            scheduleProbe(decision.url, decision.pageUrl, headers)
                            null
                        }
                        else -> null
                    }
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun onRights() {
        container.messages.post(UserMessages.RIGHTS)
    }

    fun considerClipboard(text: String?) {
        viewModelScope.launch {
            val enabled = runCatching { container.settings.current().suggestCopiedLink }.getOrDefault(false)
            if (!enabled || text.isNullOrBlank()) {
                clipboardSuggestionFlow.value = null
                return@launch
            }
            val url = SharedLinks.firstHttpUrl(text)
            if (url == null || YouTubeBlocklist.isBlocked(url) || url == hiddenClipboardUrl || url == addressFlow.value) {
                clipboardSuggestionFlow.value = null
                return@launch
            }
            clipboardSuggestionFlow.value = url
        }
    }

    fun openClipboardSuggestion() {
        val url = clipboardSuggestionFlow.value ?: return
        hiddenClipboardUrl = url
        clipboardSuggestionFlow.value = null
        submit(url)
    }

    fun dismissClipboardSuggestion() {
        hiddenClipboardUrl = clipboardSuggestionFlow.value
        clipboardSuggestionFlow.value = null
    }

    fun headersFor(request: WebResourceRequest, pageUrl: String, mediaUrl: String): Map<String, String> {
        val headers = headerMap(pageUrl, mediaUrl).toMutableMap()
        val incoming = runCatching { request.requestHeaders }.getOrNull().orEmpty()
        for ((key, value) in incoming) {
            if (value.isBlank()) continue
            if (key.equals("Range", true) || key.equals("Host", true) || key.equals("Connection", true)) continue
            headers[key] = value
        }
        return headers
    }

    private fun scheduleProbe(url: String, pageUrl: String, headers: Map<String, String>) {
        if (!interceptActive.get() || protectedPage.get()) return
        if (YouTubeBlocklist.isBlocked(url) || YouTubeBlocklist.isBlocked(pageUrl)) {
            onYouTube(false)
            return
        }
        viewModelScope.launch {
            try {
                val kind = container.probe.classify(url, headers) ?: return@launch
                onMedia(url, MediaClassifier.mimeOf(kind), pageUrl, titleFlow.value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // A failed peek must not surface as a crash.
            }
        }
    }

    private fun installServiceWorker() {
        if (!workerInstalled.compareAndSet(false, true)) return
        runCatching {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)) return
            ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : ServiceWorkerClientCompat() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url?.toString().orEmpty()
                    val page = pageSnapshot()
                    return onIntercept(url, page, false, headersFor(request, page, url))
                }
            })
        }
    }

    private fun onBridgeReport(json: String) {
        try {
            val obj = runCatching { JSONObject(json) }.getOrNull() ?: return
            val page = obj.optString("page")
            val title = obj.optString("title")
            val og = obj.optString("ogImage")
            if (og.startsWith("http")) ogImageFlow.value = og
            if (title.isNotBlank()) main.post { titleFlow.value = title }
            val items = obj.optJSONArray("items")
            if (items != null) {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val poster = item.optString("poster").ifBlank { og }
                    onMedia(item.optString("url"), item.optString("mime"), page, title, posterUrl = poster)
                }
            }
            val found = ImageReport.urls(json, page.ifBlank { pageSnapshot.get() })
            if (found.isNotEmpty()) {
                val pageTitle = title.ifBlank { titleFlow.value }
                val pageUrl = page.ifBlank { pageSnapshot.get() }
                main.post {
                    val fresh = mutableListOf<DetectedImage>()
                    imagesFlow.update { current ->
                        val merged = current.toMutableList()
                        found.forEach { url ->
                            if (merged.none { it.url == url }) {
                                val image = DetectedImage(url, pageUrl, pageTitle)
                                merged += image
                                fresh += image
                            }
                        }
                        merged.takeLast(20)
                    }
                    fresh.forEach { image -> scheduleSize(image.url, image.pageUrl, SizeKind.IMAGE, null) }
                }
            }
        } catch (_: Throwable) {
            // Bridge payloads are untrusted page data.
        }
    }

    private fun headerMap(pageUrl: String, mediaUrl: String): Map<String, String> {
        val headers = linkedMapOf(
            "User-Agent" to BrowserProfile.userAgent(desktopSite),
            "Accept" to "*/*",
            "Accept-Language" to "ko-KR,ko;q=0.9,en;q=0.5",
        )
        if (pageUrl.startsWith("http")) headers["Referer"] = pageUrl
        val cookie = runCatching { CookieManager.getInstance().getCookie(mediaUrl) }.getOrNull()
        if (!cookie.isNullOrBlank()) headers["Cookie"] = cookie
        return headers
    }

    fun newTab() {
        if (!TabLimits.canAdd(tabsFlow.value.size)) {
            container.messages.post("탭은 8개까지 열 수 있어요")
            return
        }
        saveActive()
        val tab = BrowserTab(UUID.randomUUID().toString(), "새 탭", "")
        tabsFlow.update { it + tab }
        activeIdFlow.value = tab.id
        show(tab)
    }

    fun selectTab(id: String) {
        if (id == activeIdFlow.value) return
        saveActive()
        val next = tabsFlow.value.firstOrNull { it.id == id } ?: return
        activeIdFlow.value = id
        show(next)
    }

    fun closeTab(id: String) {
        val current = tabsFlow.value
        if (current.size <= 1) {
            val fresh = BrowserTab(UUID.randomUUID().toString(), "새 탭", "")
            tabsFlow.value = listOf(fresh)
            activeIdFlow.value = fresh.id
            show(fresh)
            return
        }
        if (id == activeIdFlow.value) saveActive()
        val index = current.indexOfFirst { it.id == id }.coerceAtLeast(0)
        val remaining = current.filter { it.id != id }
        tabsFlow.value = remaining
        if (activeIdFlow.value == id) {
            val neighbor = remaining[index.coerceAtMost(remaining.lastIndex)]
            activeIdFlow.value = neighbor.id
            show(neighbor)
        }
    }

    fun addBookmark() {
        val url = pageSnapshot.get().ifBlank { addressFlow.value }
        viewModelScope.launch {
            val added = runCatching { container.browser.addBookmark(titleFlow.value, url) }.getOrDefault(false)
            container.messages.post(if (added) "북마크에 넣었어요" else "이 페이지는 북마크할 수 없어요")
        }
    }

    fun toggleSiteException() {
        val host = YouTubeBlocklist.hostOf(pageSnapshot.get().ifBlank { addressFlow.value }) ?: return
        viewModelScope.launch {
            when (runCatching { container.browser.toggleException(host) }.getOrNull()) {
                true -> container.messages.post("이 사이트는 광고 차단 예외예요")
                false -> container.messages.post("이 사이트 예외를 지웠어요")
                null -> container.messages.post("이 주소는 예외로 둘 수 없어요")
            }
        }
    }

    fun enqueueImage(image: DetectedImage) {
        viewModelScope.launch {
            try {
                if (YouTubeBlocklist.isBlocked(image.url) || YouTubeBlocklist.isBlocked(image.pageUrl)) {
                    container.messages.post(UserMessages.YOUTUBE)
                    return@launch
                }
                if (ExtraBlocks.isBlocked(image.url) || ExtraBlocks.isBlocked(image.pageUrl)) {
                    container.messages.post(UserMessages.RIGHTS)
                    return@launch
                }
                container.repository.enqueue(
                    NewDownload(
                        mediaUrl = image.url,
                        pageUrl = image.pageUrl,
                        title = imageName(image),
                        kind = MediaKind.PROGRESSIVE_MP4,
                        qualityLabel = "이미지",
                        variantUrl = null,
                        videoRepresentationId = null,
                        audioRepresentationId = null,
                        headers = headerMap(image.pageUrl, image.url),
                        delivery = Delivery.IMAGE,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                container.messages.post(UserMessages.ENQUEUE_FAILED)
            }
        }
    }

    private fun saveActive() {
        val web = webView ?: return
        val bundle = Bundle()
        runCatching { web.saveState(bundle) }
        val id = activeIdFlow.value
        val currentUrl = web.url?.takeIf { it.startsWith("http") } ?: addressFlow.value
        tabsFlow.update { list ->
            list.map { tab ->
                if (tab.id == id) {
                    tab.copy(
                        title = titleFlow.value.ifBlank { tab.title },
                        url = currentUrl,
                        state = bundle,
                    )
                } else {
                    tab
                }
            }
        }
    }

    private fun show(tab: BrowserTab) {
        val web = runCatching { obtainWebView() }.getOrNull() ?: return
        itemsFlow.value = emptyList()
        imagesFlow.value = emptyList()
        choicesFlow.value = emptyMap()
        selectedFlow.value = emptyMap()
        clearFacts()
        blockedFlow.value = false
        protectedPage.set(false)
        val restored = tab.state?.let { runCatching { web.restoreState(it) }.getOrNull() }
        if (restored != null) {
            val url = tab.url
            addressFlow.value = url
            titleFlow.value = tab.title
            hasPageFlow.value = url.startsWith("http")
            pageSnapshot.set(url)
        } else if (tab.url.startsWith("http")) {
            addressFlow.value = tab.url
            titleFlow.value = tab.title
            hasPageFlow.value = true
            pageSnapshot.set(tab.url)
            runCatching { web.loadUrl(tab.url) }
        } else {
            addressFlow.value = ""
            titleFlow.value = ""
            hasPageFlow.value = false
            pageSnapshot.set("")
            progressFlow.value = 0
            runCatching { web.loadUrl("about:blank") }
        }
        syncBack()
    }

    private fun touchActive(url: String? = null, title: String? = null) {
        val id = activeIdFlow.value
        tabsFlow.update { list ->
            list.map { tab ->
                if (tab.id != id) {
                    tab
                } else {
                    tab.copy(
                        url = url ?: tab.url,
                        title = title?.ifBlank { tab.title } ?: tab.title,
                    )
                }
            }
        }
    }

    private fun applyAgent(reload: Boolean) {
        val web = webView ?: return
        web.settings.userAgentString = BrowserProfile.userAgent(desktopSite)
        if (reload && hasPageFlow.value) runCatching { web.reload() }
    }

    override fun onCleared() {
        interceptActive.set(false)
        clearFacts()
        webView?.destroy()
        webView = null
    }

    private fun scheduleSize(url: String, pageUrl: String, kind: SizeKind, variantId: String?) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        if (YouTubeBlocklist.isBlocked(url) || YouTubeBlocklist.isBlocked(pageUrl)) return
        val variantKey = variantId.orEmpty()
        val sameVariant = sizedVariant[url] == variantKey
        if (factsFlow.value[url] != null && sameVariant) return
        if (factJobs[url]?.isActive == true && sameVariant) return
        if (sizedVariant.containsKey(url) && !sameVariant) factsFlow.update { it - url }
        sizedVariant[url] = variantKey
        factJobs[url]?.cancel()
        factJobs[url] = viewModelScope.launch {
            val looked = try {
                container.sizes.lookup(url, kind, headerMap(pageUrl, url), variantId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (sizedVariant[url] != variantKey) return@launch
            factsFlow.update { it + (url to (looked ?: MediaFacts())) }
        }
    }

    private fun clearFacts() {
        factsFlow.value = emptyMap()
        protectedFlow.value = emptySet()
        ogImageFlow.value = ""
        sizedVariant.clear()
        factJobs.values.forEach { it.cancel() }
        factJobs.clear()
    }

    private fun sizeKind(kind: MediaKind): SizeKind = when (kind) {
        MediaKind.HLS -> SizeKind.HLS
        MediaKind.DASH -> SizeKind.DASH
        MediaKind.WEBM, MediaKind.PROGRESSIVE_MP4 -> SizeKind.PROGRESSIVE
    }

    private fun displayName(item: DetectedMedia): String {
        val index = (itemsFlow.value.indexOfFirst { it.url == item.url } + 1).coerceAtLeast(1)
        return DetectedNames.of(
            item.pageTitle.ifBlank { titleFlow.value },
            index,
            item.url,
            "영상",
            DetectedNames.typeOf(item.kind),
        ).primary
    }

    private fun imageName(image: DetectedImage): String {
        val index = (imagesFlow.value.indexOfFirst { it.url == image.url } + 1).coerceAtLeast(1)
        return DetectedNames.of(image.pageTitle, index, image.url, "사진", DetectedNames.IMAGE).primary
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
            if (ExtraBlocks.isBlocked(url)) {
                model.onRights()
                return true
            }
            false
        } catch (_: Throwable) {
            true
        }
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url?.toString().orEmpty()
        val page = model.pageSnapshot()
        return model.onIntercept(url, page, request.isForMainFrame, model.headersFor(request, page, url))
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

}

private fun emptyResponse(): WebResourceResponse {
    return WebResourceResponse(
        "text/plain",
        "utf-8",
        200,
        "OK",
        mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream(ByteArray(0)),
    )
}

private fun blockedResponse(): WebResourceResponse {
    return WebResourceResponse(
        "text/plain",
        "utf-8",
        403,
        "Blocked",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0)),
    )
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
