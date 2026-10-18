package app.saveclip.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.saveclip.LocalContainer
import app.saveclip.browser.BrowserTab
import app.saveclip.core.hint.FirstRunHint
import app.saveclip.data.AppSettings
import app.saveclip.data.ShortcutEntity
import app.saveclip.storage.LibraryItem
import app.saveclip.ui.theme.monoStyle

@Composable
fun TabStrip(
    tabs: List<BrowserTab>,
    activeId: String,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNew: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tabs.forEach { tab ->
            val title = tab.title.ifBlank { "새 탭" }.take(18)
            TextButton(onClick = { onSelect(tab.id) }) {
                Text(if (tab.id == activeId) "● $title" else title, maxLines = 1)
            }
            TextButton(onClick = { onClose(tab.id) }) { Text("×") }
        }
        TextButton(onClick = onNew) { Text("새 탭") }
    }
}

@Composable
fun BrowserMenu(
    expanded: Boolean,
    desktop: Boolean,
    onDismiss: () -> Unit,
    onBookmark: () -> Unit,
    onShowBookmarks: () -> Unit,
    onShowHistory: () -> Unit,
    onDesktop: (Boolean) -> Unit,
    onException: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("이 페이지 북마크") }, onClick = { onBookmark(); onDismiss() })
        DropdownMenuItem(text = { Text("북마크") }, onClick = { onShowBookmarks(); onDismiss() })
        DropdownMenuItem(text = { Text("방문 기록") }, onClick = { onShowHistory(); onDismiss() })
        DropdownMenuItem(
            text = { Text(if (desktop) "모바일 사이트로" else "데스크톱 사이트로") },
            onClick = { onDesktop(!desktop); onDismiss() },
        )
        DropdownMenuItem(text = { Text("이 사이트 광고 차단 예외") }, onClick = { onException(); onDismiss() })
    }
}

@Composable
fun HomeGrid(
    onOpen: (String) -> Unit,
    onAdd: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onBookmarks: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onPlay: (String) -> Unit = {},
) {
    val container = LocalContainer.current
    val shortcuts by container.browser.shortcuts().collectAsState(initial = emptyList())
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        IndexHeader(
            number = "01",
            title = "바로가기",
            action = if (shortcuts.isNotEmpty()) if (editing) "완료" else "편집" else null,
            onAction = if (shortcuts.isNotEmpty()) ({ editing = !editing }) else null,
        )
        val cells = shortcuts.map { it to it.label.firstOrNull()?.uppercaseChar()?.toString().orEmpty().ifBlank { "?" } }
        val tail = listOf<Pair<ShortcutEntity?, String>>(null to "북마크", null to "추가")
        (cells.map { it.first to it.second } + tail).chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth().height(76.dp)) {
                row.forEach { (shortcut, letter) ->
                    val label = shortcut?.label ?: letter
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                            .clickable {
                                when {
                                    shortcut != null -> onOpen(shortcut.url)
                                    letter == "북마크" -> onBookmarks()
                                    else -> adding = true
                                }
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        if (shortcut != null) {
                            MonoText(letter, style = monoStyle(20, 24), color = MaterialTheme.colorScheme.onSurface)
                        }
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (editing && shortcut != null) {
                            TextButton(onClick = { onRemove(shortcut.id) }) { Text("삭제") }
                        }
                    }
                }
                repeat(4 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
        RecentDownloads(onOpenDownloads, onPlay)
    }
    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("바로가기") },
            text = {
                Column {
                    OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("이름") }, singleLine = true)
                    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("주소") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onAdd(label, url)
                    adding = false
                    label = ""
                    url = ""
                }) { Text("추가") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun RecentDownloads(onOpenDownloads: () -> Unit, onPlay: (String) -> Unit) {
    val container = LocalContainer.current
    var items by remember { mutableStateOf<List<LibraryItem>>(emptyList()) }
    LaunchedEffect(Unit) {
        items = runCatching { container.library.list(includePrivate = false).take(3) }.getOrDefault(emptyList())
    }
    if (items.isEmpty()) return
    IndexHeader("02", "최근 받은 영상", "전체 보기", onOpenDownloads)
    items.forEach { item ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable { onPlay(item.uri.toString()) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                if (item.sizeBytes > 0) MonoText(formatBytes(item.sizeBytes))
            }
            TextButton(onClick = { onPlay(item.uri.toString()) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("재생") }
        }
        Hairline()
    }
}

@Composable
fun FirstRunDialog(settings: AppSettings, onDone: () -> Unit) {
    if (settings.hintSeen) return
    var step by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("안내") },
        text = { Text(FirstRunHint.step(step).orEmpty()) },
        confirmButton = {
            TextButton(onClick = {
                if (FirstRunHint.isLast(step)) onDone() else step += 1
            }) { Text(if (FirstRunHint.isLast(step)) "알겠어요" else "다음") }
        },
    )
}
