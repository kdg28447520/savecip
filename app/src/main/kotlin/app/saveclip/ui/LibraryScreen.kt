package app.saveclip.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.saveclip.LocalContainer
import app.saveclip.data.DownloadEntity
import app.saveclip.storage.LibraryItem
import app.saveclip.storage.LibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val library: LibraryRepository,
    private val completed: suspend () -> List<DownloadEntity>,
) : ViewModel() {
    private val itemsFlow = MutableStateFlow<List<LibraryItem>>(emptyList())
    val items = itemsFlow.asStateFlow()
    private var rows: List<DownloadEntity> = emptyList()

    fun refresh() {
        viewModelScope.launch {
            rows = completed()
            itemsFlow.value = library.list()
        }
    }

    fun delete(item: LibraryItem) {
        viewModelScope.launch {
            val entity = rows.firstOrNull { it.outputUri == item.uri.toString() }
            library.delete(item, entity)
            refresh()
        }
    }
}

@Composable
fun LibraryScreen(onPlay: (String) -> Unit, model: LibraryViewModel = libraryModel()) {
    val items by model.items.collectAsState()
    var pending by remember { mutableStateOf<LibraryItem?>(null) }
    LaunchedEffect(Unit) { model.refresh() }

    if (items.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
            Text("받은 영상이 없어요", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "저장이 끝나면 영화/SaveClip 과 이 목록에 나타납니다.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(168.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = { it.uri.toString() }) { item ->
            Card(Modifier.fillMaxWidth().clickable { onPlay(item.uri.toString()) }) {
                Column(Modifier.padding(14.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(88.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        MarkFilm(MaterialTheme.colorScheme.primary, Modifier.height(36.dp))
                    }
                    Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    if (item.sizeBytes > 0) {
                        Text(
                            formatBytes(item.sizeBytes),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    TextButton(onClick = { pending = item }) { Text("삭제") }
                }
            }
        }
    }
    pending?.let { item ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("이 영상을 삭제할까요?") },
            text = { Text(item.title) },
            confirmButton = {
                TextButton(onClick = {
                    model.delete(item)
                    pending = null
                }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("취소") } },
        )
    }
}

@Composable
private fun libraryModel(): LibraryViewModel {
    val container = LocalContainer.current
    return viewModel(factory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return LibraryViewModel(container.library, { container.database.downloadDao().completed() }) as T
        }
    })
}
