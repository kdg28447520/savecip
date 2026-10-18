package app.saveclip

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import app.saveclip.core.detect.defaultExtensionlessProbe
import app.saveclip.core.extract.defaultPageMediaExtractor
import app.saveclip.crash.CrashStore
import app.saveclip.data.AppDatabase
import app.saveclip.data.SettingsStore
import app.saveclip.download.DownloadRepository
import app.saveclip.download.DownloadWorker
import app.saveclip.storage.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class UserMessageBus {
    private val messagesFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages = messagesFlow.asSharedFlow()

    fun post(text: String) {
        messagesFlow.tryEmit(text)
    }
}

class IncomingLinks {
    private val pendingFlow = MutableStateFlow<String?>(null)
    val pending = pendingFlow.asStateFlow()

    fun offer(url: String) {
        pendingFlow.value = url
    }

    fun consume(url: String) {
        pendingFlow.compareAndSet(url, null)
    }
}

class AppContainer(val app: Application) {
    val database = AppDatabase.create(app)
    val settings = SettingsStore(app)
    val messages = UserMessageBus()
    val probe = defaultExtensionlessProbe()
    val incoming = IncomingLinks()
    val extractor = defaultPageMediaExtractor()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val repository = DownloadRepository(app, database.downloadDao(), settings, messages)
    val library = LibraryRepository(app, database.downloadDao())
}

class SaveClipApp : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        CrashStore.install(this)
        container = AppContainer(this)
        instance = this
        container.scope.launch {
            runCatching { container.repository.recoverInterrupted() }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(SaveClipWorkerFactory(container))
            .build()

    companion object {
        lateinit var instance: SaveClipApp
            private set
    }
}

class SaveClipWorkerFactory(
    private val container: AppContainer,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        if (workerClassName == DownloadWorker::class.java.name) {
            return DownloadWorker(appContext, workerParameters, container)
        }
        return null
    }
}
