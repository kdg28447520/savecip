package app.saveclip.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.content.Intent
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.saveclip.AppContainer
import app.saveclip.LocalContainer
import app.saveclip.core.UserMessages
import app.saveclip.data.AppSettings
import app.saveclip.crash.CrashStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val settings = container.settings.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppSettings(),
    )

    fun setWifi(value: Boolean) {
        viewModelScope.launch {
            container.settings.setWifiOnly(value)
            container.repository.reenqueueQueued()
        }
    }

    fun setParallel(value: Int) {
        viewModelScope.launch { container.settings.setParallelism(value) }
    }

    fun setSuggestCopied(value: Boolean) {
        viewModelScope.launch { container.settings.setSuggestCopiedLink(value) }
    }

    private val crashLogFlow = MutableStateFlow(CrashStore.read(container.app))
    val crashLog = crashLogFlow.asStateFlow()

    fun clearCrashLog() {
        CrashStore.clear(container.app)
        crashLogFlow.value = null
    }
}

@Composable
fun SettingsScreen(model: SettingsViewModel = settingsModel()) {
    val settings by model.settings.collectAsState()
    val crashLog by model.crashLog.collectAsState()
    val context = LocalContext.current
    val container = LocalContainer.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("설정", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Card {
            Column(Modifier.padding(16.dp)) {
                Text("Wi-Fi에서만 받기", style = MaterialTheme.typography.titleMedium)
                Text(
                    "요금이 없는 네트워크에서만 큐를 진행합니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(checked = settings.wifiOnly, onCheckedChange = model::setWifi)
            }
        }
        Spacer(Modifier.height(12.dp))
        Card {
            Column(Modifier.padding(16.dp)) {
                Text("동시에 받을 조각 수  ${settings.parallelism}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "HLS·DASH 조각 수입니다. 큰 단일 파일은 3에서 6개 연결로 나누고, 서버가 Range를 받지 않으면 연결 하나로 받습니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = settings.parallelism.toFloat(),
                    onValueChange = { model.setParallel(it.toInt().coerceIn(1, 6)) },
                    valueRange = 1f..6f,
                    steps = 4,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Card {
            Column(Modifier.padding(16.dp)) {
                Text("복사한 링크 제안", style = MaterialTheme.typography.titleMedium)
                Text(
                    "앱이 앞에 올 때 클립보드의 http 주소를 한 번 읽고 열지 묻습니다. 기본값은 꺼짐입니다. 켜 두면 안드로이드가 붙여넣기 알림을 띄울 수 있어요.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(checked = settings.suggestCopiedLink, onCheckedChange = model::setSuggestCopied)
            }
        }
        Spacer(Modifier.height(12.dp))
        Card {
            Column(Modifier.padding(16.dp)) {
                Text("받지 않는 것", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("유튜브 주소는 브라우저와 다운로더 모두에서 막습니다. ${UserMessages.YOUTUBE}")
                Spacer(Modifier.height(8.dp))
                Text("Widevine, DASH ContentProtection, HLS EXT-X-KEY 영상은 시도하지 않습니다. ${UserMessages.PROTECTED}")
                Spacer(Modifier.height(8.dp))
                Text("실행 파일은 저장하지 않습니다. ${UserMessages.EXECUTABLE}")
                Spacer(Modifier.height(8.dp))
                Text(
                    "저장 위치는 영화/SaveClip 입니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!crashLog.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("마지막 오류 기록", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "앱이 예기치 않게 닫히면 기록이 남습니다. 공유하면 원인을 함께 볼 수 있어요.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        crashLog.orEmpty().take(800),
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "세이브클립 오류 기록")
                            putExtra(Intent.EXTRA_TEXT, crashLog)
                        }
                        runCatching {
                            context.startActivity(Intent.createChooser(send, "오류 기록 보내기"))
                        }.onFailure {
                            container.messages.post("오류 기록을 보내지 못했어요")
                        }
                    }) { Text("기록 보내기") }
                    TextButton(onClick = model::clearCrashLog) { Text("기록 지우기") }
                }
            }
        }
    }
}

@Composable
private fun settingsModel(): SettingsViewModel {
    val container = LocalContainer.current
    return viewModel(factory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(container) as T
        }
    })
}
