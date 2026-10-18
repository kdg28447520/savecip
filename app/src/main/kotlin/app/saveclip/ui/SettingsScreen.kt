package app.saveclip.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxWidth
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
import app.saveclip.core.detect.Notices
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

    fun setDesktop(value: Boolean) {
        viewModelScope.launch { container.settings.setDesktopSite(value) }
    }

    fun setBlockAds(value: Boolean) {
        viewModelScope.launch { container.settings.setBlockAds(value) }
    }

    fun removeException(host: String) {
        viewModelScope.launch { container.browser.toggleException(host) }
    }

    private val crashLogFlow = MutableStateFlow(CrashStore.read(container.app))
    val crashLog = crashLogFlow.asStateFlow()

    fun clearCrashLog() {
        CrashStore.clear(container.app)
        crashLogFlow.value = null
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit = {}, model: SettingsViewModel = settingsModel()) {
    val settings by model.settings.collectAsState()
    val crashLog by model.crashLog.collectAsState()
    val context = LocalContext.current
    val container = LocalContainer.current
    val exceptions by container.browser.exceptions().collectAsState(initial = emptyList())
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        TextButton(onClick = onBack) { Text("뒤로") }
        Text("설정", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        IndexHeader("01", "다운로드")
        Hairline()
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Wi-Fi에서만 받기", style = MaterialTheme.typography.titleMedium)
                Text(
                    "모바일 데이터로는 받지 않아요",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                InkSwitch(checked = settings.wifiOnly, onCheckedChange = model::setWifi)
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth()) {
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
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.onSurface,
                        activeTrackColor = MaterialTheme.colorScheme.onSurface,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                )
            }
        }
        IndexHeader("02", "브라우저")
        Hairline()
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("복사한 링크 제안", style = MaterialTheme.typography.titleMedium)
                Text(
                    "앱을 열면 복사한 영상 링크를 보여줘요. 기본값은 꺼짐입니다. 켜 두면 안드로이드가 붙여넣기 알림을 띄울 수 있어요.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                InkSwitch(checked = settings.suggestCopiedLink, onCheckedChange = model::setSuggestCopied)
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("데스크톱 사이트", style = MaterialTheme.typography.titleMedium)
                Text(
                    "브라우저와 받기 요청에 같은 데스크톱 User-Agent를 씁니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                InkSwitch(checked = settings.desktopSite, onCheckedChange = model::setDesktop)
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("광고·추적 차단", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Peter Lowe의 공개 호스트 목록으로 하위 요청만 빈 응답을 돌려줍니다. 기본값은 꺼짐입니다. 유튜브 차단과는 별개이며, 예외를 켜도 유튜브는 열리지 않아요.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                InkSwitch(checked = settings.blockAds, onCheckedChange = model::setBlockAds)
                exceptions.forEach { row ->
                    TextButton(onClick = { model.removeException(row.host) }) { Text("${row.host} 예외 지우기") }
                }
            }
        }
        IndexHeader("03", "비공개 폴더")
        Hairline()
        Text(
            "기기 잠금으로 열어요. 앱을 삭제하면 이 폴더의 파일도 함께 지워져요.",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
        IndexHeader("04", "정보")
        Hairline()
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("저작권", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(Notices.COPYRIGHT)
                Spacer(Modifier.height(8.dp))
                Text(
                    "개인정보 처리방침 주소는 아직 연결하지 않았어요.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth()) {
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
            Column(Modifier.fillMaxWidth()) {
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
