package app.saveclip.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.saveclip.AppContainer
import app.saveclip.LocalContainer
import app.saveclip.core.UserMessages
import app.saveclip.core.audio.AudioSave
import app.saveclip.core.detect.Notices
import app.saveclip.core.library.DeleteMode
import app.saveclip.core.library.LibraryOps
import app.saveclip.download.AndroidRemuxer
import app.saveclip.storage.AudioStore
import app.saveclip.storage.FileNames
import app.saveclip.storage.LibraryItem
import app.saveclip.storage.LibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class LibraryViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private val library: LibraryRepository = container.library
    private val itemsFlow = MutableStateFlow<List<LibraryItem>>(emptyList())
    val items = itemsFlow.asStateFlow()
    private val unlockedFlow = MutableStateFlow(false)
    val unlocked = unlockedFlow.asStateFlow()
    private val selectedFlow = MutableStateFlow<Set<String>>(emptySet())
    val selected = selectedFlow.asStateFlow()
    private var privateMode = false

    fun showFiles() {
        privateMode = false
        viewModelScope.launch { itemsFlow.value = library.list(includePrivate = false) }
    }

    fun showPrivate() {
        privateMode = true
        viewModelScope.launch {
            itemsFlow.value = if (unlockedFlow.value) library.listPrivate() else emptyList()
        }
    }

    fun refresh() {
        if (privateMode) showPrivate() else showFiles()
    }

    fun unlock() {
        unlockedFlow.value = true
        refresh()
    }

    fun lock() {
        unlockedFlow.value = false
        selectedFlow.value = emptySet()
        refresh()
    }

    fun toggle(item: LibraryItem) {
        val key = item.uri.toString()
        selectedFlow.value = if (key in selectedFlow.value) selectedFlow.value - key else selectedFlow.value + key
    }

    fun clearSelection() {
        selectedFlow.value = emptySet()
    }

    fun chosen(): List<LibraryItem> {
        val keys = selectedFlow.value
        return itemsFlow.value.filter { it.uri.toString() in keys }
    }

    fun deleteDirect(items: List<LibraryItem>) {
        viewModelScope.launch {
            library.deleteDirect(items)
            selectedFlow.value = emptySet()
            refresh()
        }
    }

    fun forgetAfterSystemDelete(items: List<LibraryItem>) {
        viewModelScope.launch {
            library.forgetRows(items)
            selectedFlow.value = emptySet()
            refresh()
        }
    }

    fun rename(item: LibraryItem, name: String) {
        viewModelScope.launch {
            val ok = library.rename(item, name)
            container.messages.post(if (ok) "이름을 바꿨어요" else "이름을 바꾸지 못했어요")
            refresh()
        }
    }

    fun movePrivate(item: LibraryItem, onCopied: (LibraryItem) -> Unit) {
        viewModelScope.launch {
            val file = library.moveToPrivate(item)
            if (file == null) {
                container.messages.post("비공개 폴더로 옮기지 못했어요")
            } else {
                container.messages.post("비공개 폴더에 넣었어요")
                onCopied(item)
            }
            refresh()
        }
    }

    fun saveAudio(item: LibraryItem) {
        viewModelScope.launch {
            val work = File(container.app.cacheDir, "audio-out").apply { mkdirs() }
            val output = File(work, "clip.m4a")
            try {
                if (item.appPrivate) {
                    val path = item.uri.path ?: error(UserMessages.NO_AUDIO)
                    AndroidRemuxer.extractAudio(File(path), output)
                } else {
                    AndroidRemuxer.extractAudio(container.app, item.uri, output)
                }
                AudioStore.save(container.app, output, FileNames.displayName(item.title, AudioSave.EXTENSION))
                container.messages.post("음성만 저장했어요")
                refresh()
            } catch (_: Exception) {
                container.messages.post(UserMessages.NO_AUDIO)
            } finally {
                output.delete()
            }
        }
    }
}

enum class LibrarySection { FILES, PRIVATE }

@Composable
fun LibraryScreen(
    onPlay: (String) -> Unit,
    onOpenPage: (String) -> Unit,
    section: LibrarySection = LibrarySection.FILES,
    onBrowse: (() -> Unit)? = null,
    quietEmpty: Boolean = false,
    model: LibraryViewModel = libraryModel(),
) {
    val items by model.items.collectAsState()
    val unlocked by model.unlocked.collectAsState()
    val selected by model.selected.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var renameTarget by remember { mutableStateOf<LibraryItem?>(null) }
    var renameText by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<List<LibraryItem>>(emptyList()) }
    val activity = context as? FragmentActivity
    val privateFolder = section == LibrarySection.PRIVATE
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            model.forgetAfterSystemDelete(pendingDelete)
        }
        pendingDelete = emptyList()
    }

    LaunchedEffect(section) {
        if (privateFolder) model.showPrivate() else model.showFiles()
    }
    if (privateFolder) {
        DisposableEffect(activity) {
            val window = activity?.window
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                model.lock()
            }
        }
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) model.lock()
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (privateFolder) {
            Text("비공개 폴더", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            InkRule()
            Text(
                Notices.PRIVATE_FOLDER,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Hairline()
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (privateFolder) {
                TextButton(onClick = {
                    if (unlocked) {
                        model.lock()
                    } else if (activity != null) {
                        promptPrivate(activity, model::unlock) {}
                    }
                }) { Text(if (unlocked) "지금 잠그기" else "잠금 해제") }
            }
            if (selected.isNotEmpty()) {
                TextButton(onClick = {
                    val chosen = model.chosen()
                    val direct = chosen.filter { it.appPrivate || it.uri.scheme != "content" }
                    val stored = chosen.filter { !it.appPrivate && it.uri.scheme == "content" }
                    if (direct.isNotEmpty()) model.deleteDirect(direct)
                    val mode = LibraryOps.deleteMode(
                        sdk = Build.VERSION.SDK_INT,
                        count = stored.size,
                        appPrivate = false,
                    )
                    if (stored.isNotEmpty() && mode == DeleteMode.SYSTEM_REQUEST && Build.VERSION.SDK_INT >= 30) {
                        pendingDelete = stored
                        val request = android.provider.MediaStore.createDeleteRequest(
                            context.contentResolver,
                            stored.map { it.uri },
                        )
                        deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                    } else if (stored.isNotEmpty()) {
                        model.deleteDirect(stored)
                    }
                }) { Text("삭제 ${selected.size}") }
                TextButton(onClick = model::clearSelection) { Text("선택 해제") }
            }
        }
        if (privateFolder && !unlocked) {
            Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
                Text("비공개 폴더", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "기기 잠금으로 엽니다. 이메일 PIN은 쓰지 않아요.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = { if (activity != null) promptPrivate(activity, model::unlock) {} },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("잠금 해제") }
            }
            return
        }
        if (items.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(if (quietEmpty) 16.dp else 28.dp), verticalArrangement = Arrangement.Center) {
                Text(
                    if (privateFolder) "비공개 파일이 없어요" else if (quietEmpty) "완료된 파일이 없어요" else "아직 받은 영상이 없어요",
                    style = if (quietEmpty) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineMedium,
                )
                if (!quietEmpty) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (privateFolder) {
                            "받은 파일을 여기로 옮기면 갤러리에 보이지 않아요."
                        } else {
                            "링크를 붙여넣거나 브라우저에서 영상을 재생하면, 받은 파일이 여기에 모여요."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!privateFolder && onBrowse != null) {
                        Button(onClick = onBrowse, modifier = Modifier.padding(top = 16.dp)) { Text("링크 붙여넣기") }
                    }
                }
            }
            return
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(168.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.uri.toString() + it.appPrivate }) { item ->
                val key = item.uri.toString()
                Card(Modifier.fillMaxWidth().clickable {
                    if (selected.isEmpty()) onPlay(item.uri.toString()) else model.toggle(item)
                }) {
                    Column(Modifier.padding(14.dp)) {
                        Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
                            Text(kindMark(item), color = MaterialTheme.colorScheme.primary)
                        }
                        Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        if (item.appPrivate) {
                            Text("비공개", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (item.sizeBytes > 0) {
                            Text(formatBytes(item.sizeBytes), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row {
                            TextButton(onClick = { model.toggle(item) }) {
                                Text(if (key in selected) "선택됨" else "선택")
                            }
                            TextButton(onClick = {
                                renameTarget = item
                                renameText = item.title
                            }) { Text("이름") }
                        }
                        Row {
                            TextButton(onClick = { shareItem(context, item) }) { Text("공유") }
                            val page = LibraryOps.redownloadUrl(item.pageUrl.orEmpty())
                            if (page != null) {
                                TextButton(onClick = { onOpenPage(page) }) { Text("다시 받기") }
                            }
                        }
                        if (!item.mime.startsWith("image")) {
                            TextButton(onClick = { model.saveAudio(item) }) { Text("음성만") }
                        }
                        if (!item.appPrivate) {
                            TextButton(onClick = {
                                val move = {
                                    model.movePrivate(item) { copied ->
                                    if (Build.VERSION.SDK_INT >= 30 && copied.uri.scheme == "content") {
                                        pendingDelete = listOf(copied)
                                        val request = android.provider.MediaStore.createDeleteRequest(
                                            context.contentResolver,
                                            listOf(copied.uri),
                                        )
                                        deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                                    } else {
                                        model.deleteDirect(listOf(copied))
                                    }
                                }
                                }
                                if (unlocked) {
                                    move()
                                } else if (activity != null) {
                                    promptPrivate(activity, {
                                        model.unlock()
                                        move()
                                    }) {}
                                }
                            }) { Text("비공개로") }
                        }
                    }
                }
            }
        }
    }
    renameTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("이름 바꾸기") },
            text = {
                OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    model.rename(item, renameText)
                    renameTarget = null
                }) { Text("저장") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("취소") } },
        )
    }
}

private fun kindMark(item: LibraryItem): String = when {
    item.appPrivate -> "비공개"
    item.mime.startsWith("image") -> "사진"
    item.mime.startsWith("audio") -> "음성"
    else -> "영상"
}

private fun shareItem(context: android.content.Context, item: LibraryItem) {
    val container = (context.applicationContext as app.saveclip.SaveClipApp).container
    val dir = File(context.cacheDir, "share").apply { mkdirs() }
    val dest = File(dir, LibraryOps.sanitizeDisplayName(item.title, item.title.substringAfterLast('.', "mp4")))
    val copied = runCatching {
        val input = if (item.uri.scheme == "file") {
            item.uri.path?.let { File(it).inputStream() }
        } else {
            context.contentResolver.openInputStream(item.uri)
        } ?: return@runCatching false
        input.use { source -> dest.outputStream().use { source.copyTo(it) } }
        true
    }.getOrDefault(false)
    if (!copied) {
        container.messages.post("공유할 파일을 열지 못했어요")
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", dest)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = item.mime.ifBlank { "*/*" }
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = android.content.ClipData.newRawUri("saveclip", uri)
    }
    runCatching { context.startActivity(Intent.createChooser(send, "공유")) }
        .onFailure { container.messages.post("공유하지 못했어요") }
}

@Suppress("DEPRECATION")
private fun promptPrivate(activity: FragmentActivity, onSuccess: () -> Unit, onError: (String) -> Unit) {
    val executor = ContextCompat.getMainExecutor(activity)
    val prompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onError(errString.toString())
                (activity.application as app.saveclip.SaveClipApp).container.messages.post(errString.toString())
            }
        },
    )
    val builder = BiometricPrompt.PromptInfo.Builder()
        .setTitle("비공개 폴더")
        .setSubtitle("기기 잠금으로 엽니다")
    if (Build.VERSION.SDK_INT >= 30) {
        builder.setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        )
    } else {
        builder.setDeviceCredentialAllowed(true)
    }
    runCatching { prompt.authenticate(builder.build()) }
        .onFailure { (activity.application as app.saveclip.SaveClipApp).container.messages.post("기기 잠금을 열 수 없어요") }
}

@Composable
private fun libraryModel(): LibraryViewModel {
    val container = LocalContainer.current
    return viewModel(factory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return LibraryViewModel(container) as T
        }
    })
}
