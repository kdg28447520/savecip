package app.saveclip.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
}

@Composable
fun QueueScreen(model: QueueViewModel = queueModel()) {
    val tasks by model.tasks.collectAsState()
    if (tasks.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
            Text("대기열이 비어 있어요", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "탐색에서 영상을 고르면 여기에서 진행, 일시정지, 이어받기를 볼 수 있습니다.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(tasks, key = { it.id }) { task ->
            QueueCard(
                task = task,
                onPause = { model.pause(task.id) },
                onResume = { model.resume(task.id) },
                onCancel = { model.cancel(task.id) },
            )
        }
    }
}

@Composable
private fun QueueCard(
    task: DownloadEntity,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
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
            task.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
            Row {
                when (task.status) {
                    DownloadStatuses.RUNNING, DownloadStatuses.QUEUED -> TextButton(onClick = onPause) { Text("일시정지") }
                    DownloadStatuses.PAUSED, DownloadStatuses.FAILED -> TextButton(onClick = onResume) { Text("이어받기") }
                }
                TextButton(onClick = onCancel) { Text("취소") }
            }
        }
    }
}

private fun statusLabel(status: String): String = when (status) {
    DownloadStatuses.QUEUED -> "대기"
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
