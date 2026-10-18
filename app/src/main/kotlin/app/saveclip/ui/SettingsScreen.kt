package app.saveclip.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.saveclip.AppContainer
import app.saveclip.LocalContainer
import app.saveclip.core.UserMessages
import app.saveclip.data.AppSettings
import kotlinx.coroutines.flow.SharingStarted
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
}

@Composable
fun SettingsScreen(model: SettingsViewModel = settingsModel()) {
    val settings by model.settings.collectAsState()
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
                    "HLS와 DASH 조각을 나눠 받는 개수입니다. 1에서 6까지입니다.",
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
