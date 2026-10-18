package app.saveclip.download

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.webkit.CookieManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.saveclip.AppContainer
import app.saveclip.R
import app.saveclip.core.BrowserProfile
import app.saveclip.core.UserMessages
import app.saveclip.core.detect.MediaKind
import app.saveclip.core.download.DownloadMergePipeline
import app.saveclip.core.download.TransferProgress
import app.saveclip.core.merge.ContainerKind
import app.saveclip.core.merge.ContainerProbe
import app.saveclip.core.policy.ExecutableBlockedException
import app.saveclip.core.policy.ProtectedMediaException
import app.saveclip.core.policy.YouTubeBlockedException
import app.saveclip.data.DownloadDao
import app.saveclip.data.DownloadEntity
import app.saveclip.data.DownloadStatuses
import app.saveclip.storage.FileNames
import app.saveclip.storage.MediaStoreSaver
import androidx.work.ListenableWorker.Result
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

class DownloadWorker(
    context: Context,
    params: WorkerParameters,
    private val container: AppContainer,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val dao = container.database.downloadDao()
        val task = dao.get(id) ?: return Result.failure()
        if (task.status == DownloadStatuses.PAUSED || task.status == DownloadStatuses.COMPLETED) {
            return Result.success()
        }
        if (dao.markRunning(id) == 0) return Result.success()
        val notifier = DownloadNotifier(applicationContext)
        return try {
            setForeground(
                notifier.progressInfo(
                    task.title,
                    id,
                    null,
                    applicationContext.getString(R.string.notif_preparing),
                ),
            )
            var savedBytes = task.bytesDone
            val uri = perform(task, notifier) { savedBytes = it }
            if (dao.markCompleted(id, uri.toString(), savedBytes) == 0) {
                val current = dao.get(id)
                if (current?.status == DownloadStatuses.RUNNING) {
                    dao.upsert(
                        current.copy(
                            status = DownloadStatuses.COMPLETED,
                            outputUri = uri.toString(),
                            bytesDone = savedBytes,
                            errorMessage = null,
                        ),
                    )
                }
            }
            notifier.done(task.title)
            File(applicationContext.filesDir, "work/$id").deleteRecursively()
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (protected: ProtectedMediaException) {
            fail(dao, notifier, id, task.title, UserMessages.PROTECTED)
        } catch (youtube: YouTubeBlockedException) {
            fail(dao, notifier, id, task.title, UserMessages.YOUTUBE)
        } catch (executable: ExecutableBlockedException) {
            fail(dao, notifier, id, task.title, UserMessages.EXECUTABLE)
        } catch (error: Exception) {
            val message = (error.message ?: "받지 못했어요").take(180)
            dao.markFailed(id, message)
            if (error is IOException && runAttemptCount < 2) {
                notifier.failed(task.title, "다시 시도합니다")
                Result.retry()
            } else {
                notifier.failed(task.title, message)
                Result.failure()
            }
        }
    }

    private suspend fun perform(
        task: DownloadEntity,
        notifier: DownloadNotifier,
        onBytes: (Long) -> Unit,
    ): Uri {
        val dao = container.database.downloadDao()
        val work = File(applicationContext.filesDir, "work/${task.id}").apply { mkdirs() }
        val pipeline = DownloadMergePipeline()
        val headers = requestHeaders(task)
        val parallelism = container.settings.current().parallelism.coerceIn(1, 6)
        val channel = Channel<TransferProgress>(Channel.CONFLATED)
        var outputUri: Uri? = null
        coroutineScope {
            val updater = launch {
                var lastPaint = 0L
                for (progress in channel) {
                    onBytes(progress.bytesDone)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPaint < 400L && progress.segmentsDone < progress.segmentsTotal) {
                        delay(200)
                        continue
                    }
                    lastPaint = now
                    dao.updateProgress(
                        task.id,
                        progress.bytesDone,
                        progress.bytesTotal ?: 0L,
                        progress.segmentsDone,
                        progress.segmentsTotal,
                    )
                    val knownTotal = progress.bytesTotal
                    val fraction = when {
                        knownTotal != null && knownTotal > 0L ->
                            progress.bytesDone.toFloat() / knownTotal.toFloat()
                        progress.segmentsTotal > 0 ->
                            progress.segmentsDone.toFloat() / progress.segmentsTotal.toFloat()
                        else -> null
                    }
                    val detail = if (progress.segmentsTotal > 0) {
                        "${progress.segmentsDone}/${progress.segmentsTotal} 조각"
                    } else {
                        applicationContext.getString(R.string.notif_running)
                    }
                    setForeground(notifier.progressInfo(task.title, task.id, fraction, detail))
                    delay(200)
                }
            }
            try {
                outputUri = when (MediaKind.valueOf(task.kind)) {
                    MediaKind.WEBM, MediaKind.PROGRESSIVE_MP4 -> saveDirect(pipeline, task, work, headers, channel)
                    MediaKind.HLS -> saveHls(pipeline, task, work, headers, parallelism, channel)
                    MediaKind.DASH -> saveDash(pipeline, task, work, headers, parallelism, channel)
                }
            } finally {
                channel.close()
                updater.join()
            }
        }
        return outputUri ?: error("받지 못했어요")
    }

    private suspend fun saveDirect(
        pipeline: DownloadMergePipeline,
        task: DownloadEntity,
        work: File,
        headers: Map<String, String>,
        channel: Channel<TransferProgress>,
    ): Uri {
        val dest = File(work, "media.bin")
        pipeline.downloadDirect(task.mediaUrl, dest, headers) { channel.trySend(it) }
        val webm = task.kind == MediaKind.WEBM.name
        val output = if (!webm && ContainerProbe.detect(dest) == ContainerKind.FRAGMENTED_MP4) {
            val remuxed = File(work, "remux.mp4")
            try {
                AndroidRemuxer.remux(listOf(dest), remuxed)
                remuxed
            } catch (_: Exception) {
                dest
            }
        } else {
            dest
        }
        val mime = if (webm) "video/webm" else "video/mp4"
        val extension = if (webm) "webm" else "mp4"
        return MediaStoreSaver.save(
            applicationContext,
            output,
            FileNames.displayName(task.title, extension),
            mime,
        )
    }

    private suspend fun saveHls(
        pipeline: DownloadMergePipeline,
        task: DownloadEntity,
        work: File,
        headers: Map<String, String>,
        parallelism: Int,
        channel: Channel<TransferProgress>,
    ): Uri {
        val result = pipeline.downloadHls(
            playlistUrl = task.mediaUrl,
            workDir = work,
            headers = headers,
            parallelism = parallelism,
            maxMediaSegments = null,
            variantUrl = task.variantUrl,
        ) { channel.trySend(it) }
        val remuxed = File(work, "out.mp4")
        val file = try {
            AndroidRemuxer.remux(listOfNotNull(result.primary, result.audio), remuxed)
            remuxed
        } catch (error: Exception) {
            if (result.container == ContainerKind.FRAGMENTED_MP4 || result.container == ContainerKind.PROGRESSIVE_MP4) {
                result.primary
            } else {
                throw error
            }
        }
        return MediaStoreSaver.save(
            applicationContext,
            file,
            FileNames.displayName(task.title, "mp4"),
            "video/mp4",
        )
    }

    private suspend fun saveDash(
        pipeline: DownloadMergePipeline,
        task: DownloadEntity,
        work: File,
        headers: Map<String, String>,
        parallelism: Int,
        channel: Channel<TransferProgress>,
    ): Uri {
        val result = pipeline.downloadDash(
            manifestUrl = task.mediaUrl,
            workDir = work,
            headers = headers,
            parallelism = parallelism,
            maxMediaSegments = null,
            videoRepresentationId = task.videoRepresentationId,
            audioRepresentationId = task.audioRepresentationId,
        ) { channel.trySend(it) }
        val remuxed = File(work, "out.mp4")
        AndroidRemuxer.remux(listOfNotNull(result.video, result.audio), remuxed)
        return MediaStoreSaver.save(
            applicationContext,
            remuxed,
            FileNames.displayName(task.title, "mp4"),
            "video/mp4",
        )
    }

    private fun requestHeaders(task: DownloadEntity): Map<String, String> {
        val headers = HeaderCodec.decode(task.headersJson).toMutableMap()
        val cookie = runCatching { CookieManager.getInstance().getCookie(task.mediaUrl) }.getOrNull()
        if (!cookie.isNullOrBlank()) headers["Cookie"] = cookie
        headers.putIfAbsent("User-Agent", BrowserProfile.USER_AGENT)
        if (task.pageUrl.startsWith("http")) headers.putIfAbsent("Referer", task.pageUrl)
        return headers
    }

    private suspend fun fail(
        dao: DownloadDao,
        notifier: DownloadNotifier,
        id: String,
        title: String,
        message: String,
    ): Result {
        dao.markFailed(id, message)
        notifier.failed(title, message)
        return Result.failure()
    }

    companion object {
        const val KEY_ID = "taskId"
    }
}
