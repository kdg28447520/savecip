package app.saveclip.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.saveclip.LocalContainer
import app.saveclip.data.DownloadEntity
import app.saveclip.data.DownloadStatuses
import app.saveclip.download.DownloadRepository
import app.saveclip.ui.theme.monoStyle
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

class QueueViewModel(private val repository: DownloadRepository) : ViewModel() {
    val tasks = repository.observe()
        .map { list -> list.filter { it.status != DownloadStatuses.COMPLETED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun pause(id: String) = viewModelScope.launch { repository.pause(id) }
    fun resume(id: String) = viewModelScope.launch { repository.resume(id) }
    fun cancel(id: String) = viewModelScope.launch { repository.cancel(id) }
    fun pauseAll(ids: List<String>) = viewModelScope.launch { ids.forEach { repository.pause(it) } }
}

@Composable
fun DownloadsScreen(
    onPlay: (String) -> Unit,
    onOpenPage: (String) -> Unit,
    onBrowse: () -> Unit,
    queue: QueueViewModel = queueModel(),
) {
    val tasks by queue.tasks.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Text(
            "다운로드",
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        SummaryStrip(running = tasks.count { it.status == DownloadStatuses.RUNNING || it.status == DownloadStatuses.QUEUED || it.status == DownloadStatuses.PAUSED })
        if (tasks.isNotEmpty()) {
            QueueGroups(
                tasks = tasks,
                onPause = queue::pause,
                onResume = queue::resume,
                onCancel = queue::cancel,
                onPauseAll = queue::pauseAll,
                onOpenPage = onOpenPage,
                modifier = Modifier.heightIn(max = 360.dp),
            )
        }
        IndexHeader("03", "완료")
        Box(Modifier.weight(1f)) {
            LibraryScreen(
                onPlay = onPlay,
                onOpenPage = onOpenPage,
                section = LibrarySection.FILES,
                onBrowse = if (tasks.isEmpty()) onBrowse else null,
                quietEmpty = tasks.isNotEmpty(),
            )
        }
    }
}

@Composable
private fun QueueGroups(
    tasks: List<DownloadEntity>,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onPauseAll: (List<String>) -> Unit,
    onOpenPage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = tasks.filter { it.status == DownloadStatuses.RUNNING }
    val waiting = tasks.filter { it.status == DownloadStatuses.QUEUED || it.status == DownloadStatuses.PAUSED }
    val failed = tasks.filter { it.status == DownloadStatuses.FAILED }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (running.isNotEmpty()) {
            item(key = "running-head") {
                IndexHeader("01", "받는 중", "모두 일시정지") { onPauseAll(running.map { it.id }) }
            }
            items(running, key = { it.id }) { task ->
                QueueCard(task, { onPause(task.id) }, { onResume(task.id) }, { onCancel(task.id) }, onOpenPage)
            }
        }
        if (waiting.isNotEmpty()) {
            item(key = "waiting-head") { IndexHeader("01", "대기") }
            items(waiting, key = { it.id }) { task ->
                QueueCard(task, { onPause(task.id) }, { onResume(task.id) }, { onCancel(task.id) }, onOpenPage)
            }
        }
        if (failed.isNotEmpty()) {
            item(key = "failed-head") { IndexHeader("02", "실패") }
            items(failed, key = { it.id }) { task ->
                QueueCard(task, { onPause(task.id) }, { onResume(task.id) }, { onCancel(task.id) }, onOpenPage)
            }
        }
    }
}

@Composable
private fun SummaryStrip(running: Int) {
    val freeGb = remember {
        val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        stat.availableBytes / (1024.0 * 1024.0 * 1024.0)
    }
    InkRule()
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Column(Modifier.weight(1f)) {
            MonoText(running.toString(), style = monoStyle(22, 28), color = MaterialTheme.colorScheme.onSurface)
            Text("받는 중", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1.3f)) {
            MonoText(String.format(java.util.Locale.US, "%.1f GB", freeGb), style = monoStyle(22, 28), color = MaterialTheme.colorScheme.onSurface)
            Text("남은 공간", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Hairline()
}

@Composable
private fun SectionHead(title: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun QueueCard(
    task: DownloadEntity,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onOpenPage: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SelectionRule(selected = false) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(task.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${statusLabel(task.status)} · ${task.qualityLabel}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (task.status == DownloadStatuses.RUNNING) {
                val fraction = when {
                    task.bytesTotal > 0L -> task.bytesDone.toFloat() / task.bytesTotal.toFloat()
                    task.segmentTotal > 0 -> task.segmentDone.toFloat() / task.segmentTotal.toFloat()
                    else -> null
                }
                if (fraction == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                } else {
                    LinearProgressIndicator(
                        progress = { fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
                val detail = buildString {
                    if (task.segmentTotal > 0) append("${task.segmentDone}/${task.segmentTotal} 조각")
                    if (task.bytesDone > 0L) {
                        if (isNotEmpty()) append(" · ")
                        append(formatBytes(task.bytesDone))
                        if (task.bytesTotal > 0L) append(" / ${formatBytes(task.bytesTotal)}")
                    }
                }
                if (detail.isNotBlank()) {
                    Text(detail, modifier = Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (task.status == DownloadStatuses.FAILED) {
                FailureLine(failureCopy(task.errorMessage), Modifier.padding(top = 8.dp))
                val done = progressDetail(task)
                if (done.isNotBlank()) {
                    Text(done, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            } else {
                task.errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }
            }
            Row {
                when (task.status) {
                    DownloadStatuses.RUNNING, DownloadStatuses.QUEUED -> TextButton(onClick = onPause) { Text("일시정지") }
                    DownloadStatuses.PAUSED -> TextButton(onClick = onResume) { Text("이어받기") }
                    DownloadStatuses.FAILED -> {
                        val expired = isExpired(task.errorMessage)
                        if (expired && task.pageUrl.startsWith("http")) {
                            TextButton(onClick = { onOpenPage(task.pageUrl) }) { Text("페이지 다시 열기") }
                        } else {
                            TextButton(onClick = onResume) { Text("다시 받기") }
                        }
                    }
                }
                TextButton(onClick = onCancel) { Text("취소") }
            }
        }
        }
    }
}

private fun failureCopy(message: String?): String {
    val text = message.orEmpty()
    return when {
        text.contains("공간") || text.contains("space", ignoreCase = true) -> "저장 공간이 부족해요"
        isExpired(text) -> "링크가 만료됐어요"
        text.isBlank() -> "받지 못했어요"
        else -> text
    }
}

private fun isExpired(message: String?): Boolean {
    val text = message.orEmpty()
    return text.contains("만료") || text.contains("404") || text.contains("410") || text.contains("expired", ignoreCase = true)
}

private fun progressDetail(task: DownloadEntity): String {
    if (task.bytesTotal <= 0L || task.bytesDone <= 0L) return ""
    val percent = (100 * task.bytesDone / task.bytesTotal).coerceIn(0, 100)
    return "$percent% · ${formatBytes(task.bytesDone)} / ${formatBytes(task.bytesTotal)}"
}

private fun statusLabel(status: String): String = when (status) {
    DownloadStatuses.QUEUED -> "대기 중"
    DownloadStatuses.RUNNING -> "받는 중"
    DownloadStatuses.PAUSED -> "일시정지"
    DownloadStatuses.FAILED -> "실패"
    else -> status
}

internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

@Composable
private fun queueModel(): QueueViewModel {
    val container = LocalContainer.current
    return viewModel(factory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return QueueViewModel(container.repository) as T
        }
    })
}
