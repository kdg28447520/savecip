package app.saveclip.ui

import android.Manifest
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import app.saveclip.LocalContainer
import app.saveclip.browser.BrowserViewModel
import app.saveclip.browser.DetectedMedia
import app.saveclip.browser.fileLabel
import app.saveclip.browser.kindLabel
import app.saveclip.core.detect.MediaKind
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
    var sheet by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<DetectedMedia?>(null) }
    val context = LocalContext.current

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val item = pending
        pending = null
        if (granted && item != null) model.enqueue(item)
    }
    val storage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val item = pending
        pending = null
        if (granted && item != null) model.enqueue(item)
    }

    fun enqueue(item: DetectedMedia) {
        val needsNotification = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        val needsStorage = Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        when {
            needsNotification -> {
                pending = item
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            needsStorage -> {
                pending = item
                storage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            else -> model.enqueue(item)
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
                Spacer(Modifier.height(12.dp))
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
            }
            if (hasPage && progress in 1..99) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (!hasPage) {
                    EmptyBrowser()
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
        if (items.isNotEmpty() && !blocked) {
            Box(Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
                Button(
                    onClick = { sheet = true },
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    MarkDown(androidx.compose.ui.graphics.Color.White, Modifier.size(18.dp))
                    Text("  ${items.size}", modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }

    if (sheet) {
        ModalBottomSheet(onDismissRequest = { sheet = false }) {
            Text(
                "감지된 영상",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                "페이지를 재생하면 주소가 여기에 쌓입니다. 화질을 고른 뒤 받으세요.",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
                items(items, key = { it.url }) { item ->
                    DetectedRow(
                        item = item,
                        choices = choices[item.url].orEmpty(),
                        selectedId = selected[item.url],
                        loading = loadingChoice == item.url,
                        onExpand = { model.ensureChoices(item) },
                        onSelect = { model.select(item.url, it) },
                        onDownload = { enqueue(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyBrowser() {
    Column(
        Modifier
            .fillMaxSize()
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
private fun DetectedRow(
    item: DetectedMedia,
    choices: List<app.saveclip.core.quality.QualityChoice>,
    selectedId: String?,
    loading: Boolean,
    onExpand: () -> Unit,
    onSelect: (String) -> Unit,
    onDownload: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                kindLabel(item.kind),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Text(
                item.label ?: fileLabel(item.url),
                modifier = Modifier.padding(start = 8.dp).weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (item.kind == MediaKind.HLS || item.kind == MediaKind.DASH) {
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
        FilledTonalButton(onClick = onDownload, modifier = Modifier.padding(top = 6.dp)) {
            Text("받기")
        }
    }
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
