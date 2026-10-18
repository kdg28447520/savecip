package app.saveclip.download

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.webkit.CookieManager
import app.saveclip.AppContainer
import app.saveclip.R
import app.saveclip.core.BrowserProfile
import app.saveclip.core.UserMessages
import app.saveclip.core.audio.AudioSave
import app.saveclip.core.detect.MediaKind
import app.saveclip.core.download.Delivery
import app.saveclip.core.policy.RightsBlockedException
import app.saveclip.core.download.DownloadMergePipeline
import app.saveclip.core.download.ParallelRangeDownloader
import app.saveclip.core.download.TransferProgress
import app.saveclip.core.merge.ContainerKind
import app.saveclip.core.merge.ContainerProbe
import app.saveclip.core.policy.ExecutableBlockedException
import app.saveclip.core.policy.ProtectedMediaException
import app.saveclip.core.policy.YouTubeBlockedException
import app.saveclip.data.DownloadDao
import app.saveclip.data.DownloadEntity
import app.saveclip.data.DownloadStatuses
import app.saveclip.storage.AudioStore
import app.saveclip.storage.FileNames
import app.saveclip.storage.ImageStore
import app.saveclip.storage.MediaStoreSaver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

sealed class SessionOutcome {
    data object Success : SessionOutcome()
    data object Skipped : SessionOutcome()
    data object Retry : SessionOutcome()
    data object Failure : SessionOutcome()
}

class DownloadSession(
    private val context: Context,
    private val container: AppContainer,
    private val notifier: DownloadNotifier,
    private val publish: suspend (android.app.Notification) -> Unit,
) {
    suspend fun run(taskId: String?, attempt: Int): SessionOutcome {
        if (taskId.isNullOrBlank()) return SessionOutcome.Failure
        val dao = container.database.downloadDao()
        val task = dao.get(taskId) ?: return SessionOutcome.Failure
        if (task.status == DownloadStatuses.PAUSED || task.status == DownloadStatuses.COMPLETED) {
            return SessionOutcome.Skipped
        }
        if (dao.markRunning(taskId) == 0) return SessionOutcome.Skipped
        return try {
            publish(
                notifier.progressNotification(
                    task.title,
                    taskId,
                    null,
                    context.getString(R.string.notif_preparing),
                ),
            )
            var savedBytes = task.bytesDone
            val uri = perform(task, notifier) { savedBytes = it }
            if (dao.markCompleted(taskId, uri.toString(), savedBytes) == 0) {
                val current = dao.get(taskId)
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
            File(context.filesDir, "work/$taskId").deleteRecursively()
            UserInitiatedTransfers.forget(context, taskId)
            SessionOutcome.Success
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (protected: ProtectedMediaException) {
            fail(dao, notifier, taskId, task.title, UserMessages.PROTECTED)
        } catch (youtube: YouTubeBlockedException) {
            fail(dao, notifier, taskId, task.title, UserMessages.YOUTUBE)
        } catch (executable: ExecutableBlockedException) {
            fail(dao, notifier, taskId, task.title, UserMessages.EXECUTABLE)
        } catch (rights: RightsBlockedException) {
            fail(dao, notifier, taskId, task.title, UserMessages.RIGHTS)
        } catch (error: Exception) {
            val message = (error.message ?: "받지 못했어요").take(180)
            dao.markFailed(taskId, message)
            if (error is IOException && attempt < 2) {
                notifier.failed(task.title, "다시 시도합니다")
                SessionOutcome.Retry
            } else {
                notifier.failed(task.title, message)
                SessionOutcome.Failure
            }
        }
    }

    private suspend fun perform(
        task: DownloadEntity,
        notifier: DownloadNotifier,
        onBytes: (Long) -> Unit,
    ): Uri {
        val dao = container.database.downloadDao()
        val work = File(context.filesDir, "work/${task.id}").apply { mkdirs() }
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
                        context.getString(R.string.notif_running)
                    }
                    publish(notifier.progressNotification(task.title, task.id, fraction, detail))
                    delay(200)
                }
            }
            try {
                outputUri = if (Delivery.normalize(task.delivery) == Delivery.IMAGE) {
                    saveImage(pipeline, task, work, headers, channel)
                } else {
                    when (MediaKind.valueOf(task.kind)) {
                        MediaKind.WEBM, MediaKind.PROGRESSIVE_MP4 -> saveDirect(pipeline, task, work, headers, parallelism, channel)
                        MediaKind.HLS -> saveHls(pipeline, task, work, headers, parallelism, channel)
                        MediaKind.DASH -> saveDash(pipeline, task, work, headers, parallelism, channel)
                    }
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
        parallelism: Int,
        channel: Channel<TransferProgress>,
    ): Uri {
        val dest = File(work, "media.bin")
        val connections = ParallelRangeDownloader.fileConnections(parallelism)
        pipeline.downloadDirect(task.mediaUrl, dest, headers, connections) { channel.trySend(it) }
        val webm = task.kind == MediaKind.WEBM.name
        val output = if (!webm && ContainerProbe.detect(dest) == ContainerKind.FRAGMENTED_MP4) {
            val remuxed = File(work, "remux.mp4")
            try {
                app.saveclip.download.AndroidRemuxer.remux(listOf(dest), remuxed)
                remuxed
            } catch (_: Exception) {
                dest
            }
        } else {
            dest
        }
        val mime = if (webm) "video/webm" else "video/mp4"
        val extension = if (webm) "webm" else "mp4"
        return storeProduced(task, output, work, mime, extension)
    }

    private suspend fun saveImage(
        pipeline: DownloadMergePipeline,
        task: DownloadEntity,
        work: File,
        headers: Map<String, String>,
        channel: Channel<TransferProgress>,
    ): Uri {
        val dest = File(work, "image.bin")
        pipeline.downloadDirect(task.mediaUrl, dest, headers, connections = 1) { channel.trySend(it) }
        val mime = ImageStore.mimeFor(task.mediaUrl)
        return ImageStore.save(
            context,
            dest,
            FileNames.displayName(task.title, ImageStore.extensionFor(mime)),
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
        return storeProduced(task, file, work, "video/mp4", "mp4")
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
        return storeProduced(task, remuxed, work, "video/mp4", "mp4")
    }

    private fun storeProduced(task: DownloadEntity, file: File, work: File, mime: String, extension: String): Uri {
        if (Delivery.normalize(task.delivery) == Delivery.AUDIO) {
            val audio = File(work, "audio.m4a")
            AndroidRemuxer.extractAudio(file, audio)
            return AudioStore.save(context, audio, FileNames.displayName(task.title, AudioSave.EXTENSION))
        }
        return MediaStoreSaver.save(context, file, FileNames.displayName(task.title, extension), mime)
    }

    private suspend fun requestHeaders(task: DownloadEntity): Map<String, String> {
        val headers = HeaderCodec.decode(task.headersJson).toMutableMap()
        val cookie = runCatching { CookieManager.getInstance().getCookie(task.mediaUrl) }.getOrNull()
        if (!cookie.isNullOrBlank()) headers["Cookie"] = cookie
        val desktop = runCatching { container.settings.current().desktopSite }.getOrDefault(false)
        headers.putIfAbsent("User-Agent", BrowserProfile.userAgent(desktop))
        if (task.pageUrl.startsWith("http")) headers.putIfAbsent("Referer", task.pageUrl)
        return headers
    }

    private suspend fun fail(
        dao: DownloadDao,
        notifier: DownloadNotifier,
        id: String,
        title: String,
        message: String,
    ): SessionOutcome {
        dao.markFailed(id, message)
        notifier.failed(title, message)
        return SessionOutcome.Failure
    }
}
