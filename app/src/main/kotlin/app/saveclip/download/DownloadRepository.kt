package app.saveclip.download

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.saveclip.SaveClipApp
import app.saveclip.UserMessageBus
import app.saveclip.core.detect.MediaKind
import app.saveclip.data.DownloadDao
import app.saveclip.data.DownloadEntity
import app.saveclip.data.DownloadStatuses
import app.saveclip.data.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.TimeUnit

data class NewDownload(
    val mediaUrl: String,
    val pageUrl: String,
    val title: String,
    val kind: MediaKind,
    val qualityLabel: String,
    val variantUrl: String?,
    val videoRepresentationId: String?,
    val audioRepresentationId: String?,
    val headers: Map<String, String>,
)

class DownloadRepository(
    private val app: Application,
    private val dao: DownloadDao,
    private val settings: SettingsStore,
    private val messages: UserMessageBus,
) {
    private val work by lazy { WorkManager.getInstance(app) }

    fun observe(): Flow<List<DownloadEntity>> = dao.observeAll()

    suspend fun enqueue(spec: NewDownload) {
        val entity = DownloadEntity(
            id = UUID.randomUUID().toString(),
            mediaUrl = spec.mediaUrl,
            pageUrl = spec.pageUrl,
            title = spec.title,
            kind = spec.kind.name,
            qualityLabel = spec.qualityLabel,
            variantUrl = spec.variantUrl,
            videoRepresentationId = spec.videoRepresentationId,
            audioRepresentationId = spec.audioRepresentationId,
            status = DownloadStatuses.QUEUED,
            bytesDone = 0L,
            bytesTotal = 0L,
            segmentDone = 0,
            segmentTotal = 0,
            errorMessage = null,
            outputUri = null,
            headersJson = HeaderCodec.encode(spec.headers),
            createdAt = System.currentTimeMillis(),
        )
        dao.upsert(entity)
        enqueueWork(entity)
        messages.post("받는 목록에 넣었어요")
    }

    suspend fun pause(id: String) {
        dao.markPaused(id)
        work.cancelUniqueWork(workName(id))
    }

    suspend fun resume(id: String) {
        val task = dao.get(id) ?: return
        if (task.status != DownloadStatuses.PAUSED && task.status != DownloadStatuses.FAILED) return
        dao.markQueued(id)
        enqueueWork(task.copy(status = DownloadStatuses.QUEUED, errorMessage = null))
    }

    suspend fun cancel(id: String) {
        work.cancelUniqueWork(workName(id))
        dao.delete(id)
        java.io.File(app.filesDir, "work/$id").deleteRecursively()
    }

    suspend fun reenqueueQueued() {
        dao.byStatus(DownloadStatuses.QUEUED).forEach { enqueueWork(it) }
    }

    private suspend fun enqueueWork(entity: DownloadEntity) {
        val current = settings.current()
        val network = if (current.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_ID to entity.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("saveclip")
            .build()
        work.enqueueUniqueWork(workName(entity.id), ExistingWorkPolicy.REPLACE, request)
    }

    private fun workName(id: String) = "saveclip-$id"
}

class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val pending = goAsync()
        val app = context.applicationContext as SaveClipApp
        app.container.scope.launch {
            try {
                if (intent.action == ACTION_PAUSE) app.container.repository.pause(id)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PAUSE = "app.saveclip.action.PAUSE"
        const val EXTRA_ID = "id"
    }
}
