package app.saveclip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
fun HomeGrid(onOpen: (String) -> Unit, onAdd: (String, String) -> Unit, onRemove: (String) -> Unit) {
    val container = LocalContainer.current
    val shortcuts by container.browser.shortcuts().collectAsState(initial = emptyList())
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("바로가기", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (shortcuts.isNotEmpty()) {
                TextButton(onClick = { editing = !editing }) { Text(if (editing) "완료" else "편집") }
            }
        }
        Text(
            "기본 바로가기는 비어 있어요. 추가해도 유튜브 주소는 저장되지 않아요.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val cells: List<ShortcutEntity?> = shortcuts + null
        cells.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { shortcut ->
                    if (shortcut == null) {
                        LetterTile("추가", "+", Modifier.weight(1f)) { adding = true }
                    } else {
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            LetterTile(
                                shortcut.label,
                                shortcut.label.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                                Modifier.fillMaxWidth(),
                            ) { onOpen(shortcut.url) }
                            if (editing) {
                                TextButton(onClick = { onRemove(shortcut.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text("삭제")
                                }
                            }
                        }
                    }
                }
                repeat(4 - row.size) {
                    Box(Modifier.weight(1f))
                }
            }
        }
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
private fun LetterTile(label: String, letter: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(letter, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        Text(
            label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodySmall,
        )
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
