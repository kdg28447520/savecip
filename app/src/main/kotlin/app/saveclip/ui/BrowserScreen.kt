package app.saveclip.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import app.saveclip.LocalContainer
import app.saveclip.core.detect.ClipInput
import app.saveclip.core.detect.DetectedSheet
import app.saveclip.core.detect.PhotoInput
import app.saveclip.core.detect.SheetVariant
import app.saveclip.core.download.Delivery
import kotlinx.coroutines.launch
import app.saveclip.browser.BrowserViewModel
import app.saveclip.browser.DetectedImage
import app.saveclip.data.AppSettings
import app.saveclip.browser.DetectedMedia
import app.saveclip.core.detect.MediaKind
import app.saveclip.ui.theme.Persimmon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(onOpenSettings: () -> Unit = {}, model: BrowserViewModel = browserModel()) {
    val address by model.address.collectAsState()
    val progress by model.progress.collectAsState()
    val items by model.items.collectAsState()
    val hasPage by model.hasPage.collectAsState()
    val blocked by model.blocked.collectAsState()
    val canGoBack by model.canGoBack.collectAsState()
    val choices by model.choices.collectAsState()
    val analyzing by model.analyzing.collectAsState()
    val clipboardSuggestion by model.clipboardSuggestion.collectAsState()
    val tabs by model.tabs.collectAsState()
    val activeTab by model.activeTabId.collectAsState()
    val images by model.images.collectAsState()
    val facts by model.facts.collectAsState()
    val pageTitle by model.title.collectAsState()
    val protectedUrls by model.protectedUrls.collectAsState()
    val container = LocalContainer.current
    val settings by container.settings.settings.collectAsState(initial = AppSettings())
    var sheet by remember { mutableStateOf(false) }
    var pendingDelivery by remember { mutableStateOf(Delivery.VIDEO) }
    var menu by remember { mutableStateOf(false) }
    var bookmarks by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<DetectedMedia?>(null) }
    val context = LocalContext.current

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val item = pending
        pending = null
        if (granted && item != null) model.enqueue(item, pendingDelivery)
    }
    val storage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val item = pending
        pending = null
        if (granted && item != null) model.enqueue(item, pendingDelivery)
    }

    fun enqueue(item: DetectedMedia, delivery: String = Delivery.VIDEO) {
        val needsNotification = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        val needsStorage = Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        when {
            needsNotification -> {
                pending = item
                pendingDelivery = delivery
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            needsStorage -> {
                pending = item
                pendingDelivery = delivery
                storage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            else -> model.enqueue(item, delivery)
        }
    }

    fun enqueueImage(image: DetectedImage) {
        model.enqueueImage(image)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, settings.suggestCopiedLink) {
        fun pull() {
            if (!settings.suggestCopiedLink) {
                model.considerClipboard(null)
            } else {
                model.considerClipboard(clipboardText(context))
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) pull()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) pull()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(container) {
        container.incoming.pending.collect { url ->
            if (url.isNullOrBlank()) return@collect
            container.incoming.consume(url)
            model.submit(url)
        }
    }

    BackHandler(enabled = canGoBack && !blocked) { model.goBack() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Persimmon),
                        contentAlignment = Alignment.Center,
                    ) {
                        MarkDown(MaterialTheme.colorScheme.onPrimary, Modifier.size(16.dp))
                    }
                    Column(Modifier.padding(start = 10.dp).weight(1f)) {
                        Text("세이브클립", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "웹에서 연 영상만 이 기기에 저장합니다",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        MarkSliders(MaterialTheme.colorScheme.onSurface)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        TabStrip(
                            tabs = tabs,
                            activeId = activeTab,
                            onSelect = model::selectTab,
                            onClose = model::closeTab,
                            onNew = model::newTab,
                        )
                    }
                    TextButton(onClick = { menu = true }) { Text("메뉴") }
                    BrowserMenu(
                        expanded = menu,
                        desktop = settings.desktopSite,
                        onDismiss = { menu = false },
                        onBookmark = model::addBookmark,
                        onShowBookmarks = { bookmarks = true },
                        onShowHistory = { history = true },
                        onDesktop = { enabled ->
                            container.scope.launch { container.settings.setDesktopSite(enabled) }
                        },
                        onException = model::toggleSiteException,
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = address,
                    onValueChange = model::onAddressChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("주소 붙여넣기 또는 검색") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { model.submit(address) }),
                    shape = RoundedCornerShape(16.dp),
                )
                Row(
                    Modifier.padding(top = 8.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = { model.analyzeInput(address) },
                        enabled = !analyzing,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        if (analyzing) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("분석")
                        }
                    }
                    Button(
                        onClick = { model.submit(address) },
                        enabled = !analyzing,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text("이동") }
                }
                if (clipboardSuggestion != null) {
                    Row(
                        Modifier.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "복사한 링크",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = model::openClipboardSuggestion) {
                            Text("열기")
                        }
                        Text(
                            clipboardHost(clipboardSuggestion.orEmpty()),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = model::dismissClipboardSuggestion) { Text("닫기") }
                    }
                }
            }
            if (hasPage && progress in 1..99) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (!hasPage) {
                    Column(Modifier.fillMaxSize()) {
                        EmptyBrowser()
                        Box(Modifier.weight(1f)) {
                        HomeGrid(
                            onOpen = model::submit,
                            onAdd = { label, url ->
                                container.scope.launch {
                                    val added = container.browser.addShortcut(label, url)
                                    container.messages.post(if (added) "바로가기를 넣었어요" else "이 주소는 바로가기에 넣을 수 없어요")
                                }
                            },
                            onRemove = { id ->
                                container.scope.launch { container.browser.deleteShortcut(id) }
                            },
                        )
                        }
                    }
                } else {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            model.obtainWebView().also { web ->
                                (web.parent as? ViewGroup)?.removeView(web)
                                web.layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                            }
                        },
                    )
                    if (blocked) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background)
                                .padding(28.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text("유튜브는 지원하지 않아요", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "youtube.com, youtu.be, youtube-nocookie.com, googlevideo.com 주소는 열지도 받지도 않습니다.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        val found = remember(items, images, facts, choices, pageTitle) {
            DetectedSheet.build(
                clips = clipInputs(items, pageTitle),
                photos = photoInputs(images, pageTitle),
                facts = facts,
                variants = variantMap(choices),
            ).let { it.videoCount + it.photoCount }
        }
        if (found > 0 && !blocked) {
            Box(Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
                Button(
                    onClick = { sheet = true },
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    MarkDown(MaterialTheme.colorScheme.onPrimary, Modifier.size(18.dp))
                    Text("받기", modifier = Modifier.padding(start = 6.dp))
                }
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.inverseSurface),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        found.coerceAtMost(99).toString(),
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }

    LaunchedEffect(sheet) {
        if (!sheet) return@LaunchedEffect
        model.refreshSizes()
        items.filter { it.kind == MediaKind.HLS || it.kind == MediaKind.DASH }.forEach(model::ensureChoices)
    }
    if (sheet) {
        DetectedMediaSheet(
            clips = clipInputs(items, pageTitle),
            photos = photoInputs(images, pageTitle),
            facts = facts,
            variants = variantMap(choices),
            protectedUrls = protectedUrls,
            onDismiss = { sheet = false },
            onDownload = { sourceUrl, variantId, audio ->
                val item = items.firstOrNull { it.url == sourceUrl } ?: return@DetectedMediaSheet
                if (!variantId.isNullOrBlank()) model.select(sourceUrl, variantId)
                enqueue(item, if (audio) Delivery.AUDIO else Delivery.VIDEO)
                sheet = false
            },
            onDownloadPhotos = { urls ->
                urls.forEach { url -> images.firstOrNull { it.url == url }?.let(::enqueueImage) }
                sheet = false
            },
            onOpenSettings = {
                sheet = false
                onOpenSettings()
            },
        )
    }
    if (bookmarks) {
        BookmarkSheet(onOpen = { model.submit(it); bookmarks = false }, onClose = { bookmarks = false })
    }
    if (history) {
        HistorySheet(onOpen = { model.submit(it); history = false }, onClose = { history = false })
    }
    FirstRunDialog(settings) {
        container.scope.launch { container.settings.setHintSeen(true) }
    }
}

@Composable
private fun EmptyBrowser() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(28.dp)
            .widthIn(max = 560.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("영상 페이지 주소를 붙여 넣으세요", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(10.dp))
        Text(
            "분석은 주소에서 영상을 찾고, 이동은 페이지를 엽니다. 유튜브는 열 수 없고, 보호된 영상은 받을 수 없어요.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun clipInputs(items: List<DetectedMedia>, pageTitle: String): List<ClipInput> {
    return items.map { item ->
        ClipInput(
            url = item.url,
            kind = item.kind,
            pageUrl = item.pageUrl,
            pageTitle = item.pageTitle.ifBlank { pageTitle },
            posterUrl = item.posterUrl,
        )
    }
}

private fun photoInputs(images: List<DetectedImage>, pageTitle: String): List<PhotoInput> {
    return images.map { image ->
        PhotoInput(
            url = image.url,
            pageUrl = image.pageUrl,
            pageTitle = image.pageTitle.ifBlank { pageTitle },
        )
    }
}

private fun variantMap(
    choices: Map<String, List<app.saveclip.core.quality.QualityChoice>>,
): Map<String, List<SheetVariant>> {
    return choices.mapValues { (_, list) ->
        list.map { choice -> SheetVariant(choice.id, choice.height, choice.bandwidth) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarkSheet(onOpen: (String) -> Unit, onClose: () -> Unit) {
    val container = LocalContainer.current
    val rows by container.browser.bookmarks().collectAsState(initial = emptyList())
    ModalBottomSheet(onDismissRequest = onClose) {
        Text("북마크", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
            items(rows, key = { it.id }) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onOpen(row.url) }, modifier = Modifier.weight(1f)) {
                        Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { container.scope.launch { container.browser.deleteBookmark(row.id) } }) { Text("삭제") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(onOpen: (String) -> Unit, onClose: () -> Unit) {
    val container = LocalContainer.current
    val rows by container.browser.history().collectAsState(initial = emptyList())
    ModalBottomSheet(onDismissRequest = onClose) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("방문 기록", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { container.scope.launch { container.browser.clearHistory() } }) { Text("모두 지우기") }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
            items(rows, key = { it.id }) { row ->
                TextButton(onClick = { onOpen(row.url) }, modifier = Modifier.fillMaxWidth()) {
                    Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun clipboardText(context: android.content.Context): String? {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return null
    if (!manager.hasPrimaryClip()) return null
    val clip = manager.primaryClip ?: return null
    if (clip.itemCount <= 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}

private fun clipboardHost(url: String): String {
    val host = android.net.Uri.parse(url).host
    return host?.takeIf { it.isNotBlank() } ?: url.take(48)
}

@Composable
private fun browserModel(): BrowserViewModel {
    val container = LocalContainer.current
    return viewModel(
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return BrowserViewModel(container.app, container) as T
            }
        },
    )
}
