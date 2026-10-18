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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
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
import app.saveclip.ui.theme.LocalMotionEnabled
import app.saveclip.ui.theme.ScMotion
import app.saveclip.ui.theme.monoStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    onOpenSettings: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onPlay: (String) -> Unit = {},
    model: BrowserViewModel = browserModel(),
) {
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
    var showTabs by remember { mutableStateOf(false) }
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

    BackHandler(enabled = sheet) { sheet = false }
    BackHandler(enabled = !sheet && canGoBack && !blocked) { model.goBack() }

    val built = remember(items, images, facts, choices, pageTitle) {
        DetectedSheet.build(
            clips = clipInputs(items, pageTitle),
            photos = photoInputs(images, pageTitle),
            facts = facts,
            variants = variantMap(choices),
        )
    }
    val found = built.videoCount + built.photoCount
    val motion = LocalMotionEnabled.current
    val scale by animateFloatAsState(if (sheet && motion) 0.928f else 1f, ScMotion.drawerTween(motion), label = "page-scale")
    val shift by animateDpAsState(if (sheet && motion) 14.dp else 0.dp, ScMotion.drawerTween(motion), label = "page-shift")
    val corner by animateDpAsState(if (sheet && motion) 8.dp else 0.dp, ScMotion.drawerTween(motion), label = "page-corner")
    val scrim by animateFloatAsState(if (sheet) 0.5f else 0f, ScMotion.drawerTween(motion), label = "page-scrim")

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.onSurface)) {
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationY = shift.toPx()
                    shape = RoundedCornerShape(corner)
                    clip = sheet && motion
                }
                .background(MaterialTheme.colorScheme.background),
        ) {
            if (!hasPage) {
                HomeChrome(
                    address = address,
                    analyzing = analyzing,
                    clipboard = clipboardSuggestion,
                    onAddress = model::onAddressChange,
                    onGo = { model.submit(address) },
                    onAnalyze = { model.analyzeInput(address) },
                    onPaste = {
                        val pasted = clipboardText(context)?.trim().orEmpty()
                        if (pasted.isNotBlank()) model.onAddressChange(pasted)
                    },
                    onOpenClip = model::openClipboardSuggestion,
                    onDismissClip = model::dismissClipboardSuggestion,
                    onSettings = onOpenSettings,
                )
            } else {
                BrowserChrome(
                    address = address,
                    tabCount = tabs.size,
                    menu = menu,
                    desktop = settings.desktopSite,
                    onClose = model::showHome,
                    onTabs = { showTabs = !showTabs },
                    onMenu = { menu = true },
                    onDismissMenu = { menu = false },
                    onBookmark = model::addBookmark,
                    onShowBookmarks = { bookmarks = true },
                    onShowHistory = { history = true },
                    onDesktop = { enabled -> container.scope.launch { container.settings.setDesktopSite(enabled) } },
                    onException = model::toggleSiteException,
                )
                if (showTabs) {
                    TabStrip(tabs, activeTab, model::selectTab, model::closeTab, model::newTab)
                }
            }
            if (hasPage && progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (!hasPage) {
                    HomeGrid(
                        onOpen = model::submit,
                        onAdd = { label, url ->
                            container.scope.launch {
                                val added = container.browser.addShortcut(label, url)
                                container.messages.post(if (added) "바로가기를 넣었어요" else "이 주소는 바로가기에 넣을 수 없어요")
                            }
                        },
                        onRemove = { id -> container.scope.launch { container.browser.deleteShortcut(id) } },
                        onBookmarks = { bookmarks = true },
                        onOpenDownloads = onOpenDownloads,
                        onPlay = onPlay,
                    )
                } else {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { viewContext ->
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
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(28.dp),
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
            if (hasPage) {
                Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = model::goBack, modifier = Modifier.height(48.dp)) { Text("뒤로") }
                    TextButton(onClick = model::goForward, modifier = Modifier.height(48.dp)) { Text("앞으로") }
                    TextButton(onClick = model::reload, modifier = Modifier.height(48.dp)) { Text("새로고침") }
                    TextButton(onClick = model::showHome, modifier = Modifier.height(48.dp)) { Text("홈") }
                    TextButton(onClick = model::addBookmark, modifier = Modifier.height(48.dp)) { Text("북마크") }
                }
            }
        }
        if (scrim > 0f) {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = scrim)))
        }
        if (found > 0 && !blocked && !sheet) {
            DetectDock(
                count = found,
                videos = built.videoCount,
                photos = built.photoCount,
                onOpen = { sheet = true },
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = if (hasPage) 68.dp else 12.dp),
            )
        }
        AnimatedVisibility(
            visible = sheet,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = if (motion) slideInVertically(ScMotion.sheetMorph()) { it } + fadeIn(ScMotion.spec(true, ScMotion.sheetMorph())) else fadeIn(ScMotion.spec(false, ScMotion.sheetMorph())),
            exit = if (motion) slideOutVertically(androidx.compose.animation.core.tween(ScMotion.Base, easing = ScMotion.Exit)) { it } + fadeOut() else fadeOut(ScMotion.spec(false, ScMotion.sheetMorph())),
        ) {
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
                    DownloadPulse.bump()
                    sheet = false
                },
                onDownloadPhotos = { urls ->
                    urls.forEach { url -> images.firstOrNull { it.url == url }?.let(::enqueueImage) }
                    DownloadPulse.bump()
                    sheet = false
                },
                onOpenSettings = {
                    sheet = false
                    onOpenSettings()
                },
            )
        }
    }

    LaunchedEffect(sheet) {
        if (!sheet) return@LaunchedEffect
        model.refreshSizes()
        items.filter { it.kind == MediaKind.HLS || it.kind == MediaKind.DASH }.forEach(model::ensureChoices)
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
private fun HomeChrome(
    address: String,
    analyzing: Boolean,
    clipboard: String?,
    onAddress: (String) -> Unit,
    onGo: () -> Unit,
    onAnalyze: () -> Unit,
    onPaste: () -> Unit,
    onOpenClip: () -> Unit,
    onDismissClip: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.inverseSurface),
                contentAlignment = Alignment.Center,
            ) {
                MarkDown(MaterialTheme.colorScheme.inverseOnSurface, Modifier.size(14.dp))
            }
            Text("SaveClip", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp).weight(1f))
            IconButton(onClick = onSettings) { MarkSliders(MaterialTheme.colorScheme.onSurface) }
        }
        Text(
            "링크를 붙여넣고\n화질만 고르세요",
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        Text("영상 주소", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = address,
            onValueChange = onAddress,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            singleLine = true,
            placeholder = { MonoText("https://", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            trailingIcon = { TextButton(onClick = onPaste) { Text("붙여넣기") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onGo() }),
            shape = RoundedCornerShape(8.dp),
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onAnalyze, enabled = !analyzing, modifier = Modifier.height(48.dp)) {
                if (analyzing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("분석")
            }
            OutlinedButton(onClick = onGo, enabled = !analyzing, shape = RoundedCornerShape(8.dp)) { Text("이동") }
        }
        if (clipboard != null) {
            Row(
                Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("방금 복사한 링크", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MonoText(clipboardHost(clipboard), color = MaterialTheme.colorScheme.onSurface)
                }
                Button(onClick = onOpenClip, shape = RoundedCornerShape(8.dp), modifier = Modifier.height(40.dp)) { Text("열기") }
                TextButton(onClick = onDismissClip) { Text("닫기") }
            }
        }
    }
}

@Composable
private fun BrowserChrome(
    address: String,
    tabCount: Int,
    menu: Boolean,
    desktop: Boolean,
    onClose: () -> Unit,
    onTabs: () -> Unit,
    onMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onBookmark: () -> Unit,
    onShowBookmarks: () -> Unit,
    onShowHistory: () -> Unit,
    onDesktop: (Boolean) -> Unit,
    onException: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onClose, modifier = Modifier.height(48.dp)) { Text("닫기") }
        Row(
            Modifier
                .weight(1f)
                .height(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonoText(clipboardHost(address), color = MaterialTheme.colorScheme.onSurface)
        }
        TextButton(onClick = onTabs, modifier = Modifier.height(48.dp)) {
            MonoText(tabCount.toString(), style = monoStyle(12, 16, androidx.compose.ui.text.font.FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
        }
        Box {
            TextButton(onClick = onMenu, modifier = Modifier.height(48.dp)) { Text("메뉴") }
            BrowserMenu(menu, desktop, onDismissMenu, onBookmark, onShowBookmarks, onShowHistory, onDesktop, onException)
        }
    }
}

@Composable
private fun DetectDock(count: Int, videos: Int, photos: Int, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val detail = if (photos > 0) "영상 ${videos}개 · 사진 ${photos}장" else "영상 ${videos}개"
    Row(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .shadow(12.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.inverseSurface)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .size(48.dp)
                .border(1.dp, MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.25f), RoundedCornerShape(8.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            RollingCount(count, monoStyle(22, 28, androidx.compose.ui.text.font.FontWeight.SemiBold), MaterialTheme.colorScheme.inverseOnSurface)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(detail, color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.titleMedium)
            Text("재생하면 더 찾아요", color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onOpen, modifier = Modifier.height(48.dp), shape = RoundedCornerShape(8.dp)) { Text("받기") }
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
