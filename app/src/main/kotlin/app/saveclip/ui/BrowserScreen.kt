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
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.draw.alpha
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
import app.saveclip.core.download.Delivery
import kotlinx.coroutines.launch
import app.saveclip.browser.BrowserViewModel
import app.saveclip.browser.DetectedImage
import app.saveclip.data.AppSettings
import app.saveclip.browser.DetectedMedia
import app.saveclip.core.detect.DetectionList
import app.saveclip.core.detect.ImageRowInput
import app.saveclip.core.detect.ListedMedia
import app.saveclip.core.detect.MediaKind
import app.saveclip.core.detect.VideoRowInput
import app.saveclip.ui.theme.Persimmon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(model: BrowserViewModel = browserModel()) {
    val address by model.address.collectAsState()
    val progress by model.progress.collectAsState()
    val items by model.items.collectAsState()
    val hasPage by model.hasPage.collectAsState()
    val blocked by model.blocked.collectAsState()
    val canGoBack by model.canGoBack.collectAsState()
    val choices by model.choices.collectAsState()
    val selected by model.selected.collectAsState()
    val loadingChoice by model.loadingChoice.collectAsState()
    val analyzing by model.analyzing.collectAsState()
    val clipboardSuggestion by model.clipboardSuggestion.collectAsState()
    val tabs by model.tabs.collectAsState()
    val activeTab by model.activeTabId.collectAsState()
    val images by model.images.collectAsState()
    val facts by model.facts.collectAsState()
    val pageTitle by model.title.collectAsState()
    val container = LocalContainer.current
    val settings by container.settings.settings.collectAsState(initial = AppSettings())
    var sheet by remember { mutableStateOf(false) }
    var showTiny by remember { mutableStateOf(false) }
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
                        MarkDown(androidx.compose.ui.graphics.Color.White, Modifier.size(16.dp))
                    }
                    Column(Modifier.padding(start = 10.dp)) {
                        Text("세이브클립", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "웹에서 연 영상만 이 기기에 저장합니다",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                    placeholder = { Text("페이지 주소") },
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
                        TextButton(onClick = model::openClipboardSuggestion) {
                            Text("복사한 링크 열기")
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
        if ((items.isNotEmpty() || images.isNotEmpty()) && !blocked) {
            Box(Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
                Button(
                    onClick = { sheet = true },
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    MarkDown(androidx.compose.ui.graphics.Color.White, Modifier.size(18.dp))
                    Text("  ${items.size + images.size}", modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }

    LaunchedEffect(sheet, items, images, selected) {
        if (sheet) model.refreshSizes()
    }
    if (sheet) {
        val arranged = DetectionList.arrange(
            videos = items.map { VideoRowInput(it.url, it.kind, it.pageTitle.ifBlank { pageTitle }) },
            images = images.map { ImageRowInput(it.url, it.pageTitle.ifBlank { pageTitle }) },
            facts = facts,
        )
        val videosByUrl = items.associateBy { it.url }
        val imagesByUrl = images.associateBy { it.url }
        ModalBottomSheet(onDismissRequest = { sheet = false }) {
            Text(
                "감지된 영상",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                "큰 파일부터 보여 드려요. 1MB보다 작은 파일은 접어 둡니다.",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
                items(arranged.main, key = { it.key }) { row ->
                    PresentedRow(
                        row = row,
                        item = videosByUrl[row.url],
                        choices = choices[row.url].orEmpty(),
                        selectedId = selected[row.url],
                        loading = loadingChoice == row.url,
                        muted = false,
                        onExpand = { videosByUrl[row.url]?.let(model::ensureChoices) },
                        onSelect = { model.select(row.url, it) },
                        onDownload = { videosByUrl[row.url]?.let { enqueue(it) } },
                        onAudio = { videosByUrl[row.url]?.let { enqueue(it, Delivery.AUDIO) } },
                        onImage = { imagesByUrl[row.url]?.let { enqueueImage(it) } },
                    )
                }
                if (arranged.tinyCount > 0) {
                    item(key = "tiny-files") {
                        TextButton(
                            onClick = { showTiny = !showTiny },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        ) {
                            Text(if (showTiny) "작은 파일 ${arranged.tinyCount}개 접기" else "작은 파일 ${arranged.tinyCount}개")
                        }
                    }
                    if (showTiny) {
                        items(arranged.tiny, key = { it.key }) { row ->
                            PresentedRow(
                                row = row,
                                item = videosByUrl[row.url],
                                choices = choices[row.url].orEmpty(),
                                selectedId = selected[row.url],
                                loading = loadingChoice == row.url,
                                muted = true,
                                onExpand = { videosByUrl[row.url]?.let(model::ensureChoices) },
                                onSelect = { model.select(row.url, it) },
                                onDownload = { videosByUrl[row.url]?.let { enqueue(it) } },
                                onAudio = { videosByUrl[row.url]?.let { enqueue(it, Delivery.AUDIO) } },
                                onImage = { imagesByUrl[row.url]?.let { enqueueImage(it) } },
                            )
                        }
                    }
                }
            }
        }
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

@Composable
private fun PresentedRow(
    row: ListedMedia,
    item: DetectedMedia?,
    choices: List<app.saveclip.core.quality.QualityChoice>,
    selectedId: String?,
    loading: Boolean,
    muted: Boolean,
    onExpand: () -> Unit,
    onSelect: (String) -> Unit,
    onDownload: () -> Unit,
    onAudio: () -> Unit,
    onImage: () -> Unit,
) {
    val typeLabel = row.typeLabel.ifBlank { if (row.video) "MP4" else "사진" }
    val primary = row.primary.ifBlank { if (row.video) "영상" else "사진" }
    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (muted) 0.55f else 1f)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text(
                typeLabel,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Column(Modifier.padding(start = 8.dp).weight(1f)) {
                Text(primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val secondary = row.secondary
                if (!secondary.isNullOrBlank()) {
                    Text(
                        secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val meta = row.meta
                val detail = when {
                    !meta.isNullOrBlank() -> meta
                    row.pending -> "크기 확인 중"
                    else -> null
                }
                if (detail != null) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (row.video && item != null && (item.kind == MediaKind.HLS || item.kind == MediaKind.DASH)) {
            if (choices.isEmpty() && !loading) {
                TextButton(onClick = onExpand) { Text("화질 보기") }
            }
            if (loading) {
                CircularProgressIndicator(Modifier.padding(vertical = 8.dp).size(18.dp), strokeWidth = 2.dp)
            }
            if (choices.isNotEmpty()) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.take(6).forEach { choice ->
                        FilterChip(
                            selected = choice.id == selectedId,
                            onClick = { onSelect(choice.id) },
                            label = { Text(choice.label) },
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (row.video) {
                FilledTonalButton(onClick = onDownload, modifier = Modifier.padding(top = 6.dp)) {
                    Text("받기")
                }
                FilledTonalButton(onClick = onAudio, modifier = Modifier.padding(top = 6.dp)) {
                    Text("음성만")
                }
            } else {
                FilledTonalButton(onClick = onImage, modifier = Modifier.padding(top = 6.dp)) {
                    Text("사진 받기")
                }
            }
        }
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
