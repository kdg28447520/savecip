package app.saveclip.core.download

import app.saveclip.core.dash.DashTrack
import app.saveclip.core.hls.ByteRange
import app.saveclip.core.hls.HlsMediaPlaylist
import app.saveclip.core.hls.HlsParseResult
import app.saveclip.core.hls.HlsPlaylistParser
import app.saveclip.core.drm.DrmDetector
import app.saveclip.core.http.HttpStatusException
import app.saveclip.core.http.SaveClipHttp
import app.saveclip.core.merge.ContainerKind
import app.saveclip.core.merge.ContainerProbe
import app.saveclip.core.merge.SegmentJoiner
import app.saveclip.core.policy.DownloadGuard
import app.saveclip.core.policy.ProtectedMediaException
import app.saveclip.core.policy.YouTubeBlockedException
import app.saveclip.core.quality.PlaylistInspector
import app.saveclip.core.quality.QualityPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

data class TransferProgress(
    val bytesDone: Long,
    val bytesTotal: Long?,
    val segmentsDone: Int,
    val segmentsTotal: Int,
)

data class HlsDownloadResult(
    val primary: File,
    val audio: File?,
    val container: ContainerKind,
)

data class DashDownloadResult(
    val video: File,
    val audio: File?,
    val container: ContainerKind,
)

class DownloadMergePipeline(
    private val http: SaveClipHttp = SaveClipHttp(),
) {
    suspend fun downloadDirect(
        url: String,
        dest: File,
        headers: Map<String, String> = emptyMap(),
        connections: Int = 4,
        minParallelBytes: Long = ParallelRangeDownloader.MIN_BYTES,
        progress: (TransferProgress) -> Unit = {},
    ): File {
        DownloadGuard.checkUrl(url)
        ParallelRangeDownloader(http).download(
            url = url,
            dest = dest,
            headers = headers,
            connections = connections,
            minBytes = minParallelBytes,
        ) { done, total ->
            progress(TransferProgress(done, total, if (total != null && done >= total) 1 else 0, 1))
        }
        return dest
    }

    suspend fun downloadHls(
        playlistUrl: String,
        workDir: File,
        headers: Map<String, String> = emptyMap(),
        parallelism: Int = 4,
        maxMediaSegments: Int? = null,
        variantUrl: String? = null,
        progress: (TransferProgress) -> Unit = {},
    ): HlsDownloadResult {
        DownloadGuard.checkUrl(playlistUrl)
        val playlistText = http.getText(playlistUrl, headers)
        if (DrmDetector.playlistIsProtected(playlistText)) throw ProtectedMediaException()
        val first = HlsPlaylistParser.parse(playlistText, playlistUrl)
        val mediaUrl: String
        val audioUrl: String?
        when (first) {
            is HlsParseResult.Master -> {
                val variant = when {
                    !variantUrl.isNullOrBlank() -> first.variants.firstOrNull { it.url == variantUrl }
                        ?: error("고른 화질을 찾지 못했습니다")
                    else -> QualityPicker.defaultHls(first.variants)
                }
                mediaUrl = variant.url
                audioUrl = QualityPicker.audioRendition(variant, first.renditions)?.url
            }
            is HlsParseResult.Media -> {
                mediaUrl = playlistUrl
                audioUrl = null
                if (first.playlist.encrypted) throw ProtectedMediaException()
            }
        }
        val primary = downloadMediaPlaylist(
            mediaUrl,
            File(workDir, "hls-primary"),
            File(workDir, "primary-joined.bin"),
            headers,
            parallelism,
            maxMediaSegments,
            tolerateTrailing404 = maxMediaSegments == null,
            progress = progress,
        )
        val audio = audioUrl?.let {
            downloadMediaPlaylist(
                it,
                File(workDir, "hls-audio"),
                File(workDir, "audio-joined.bin"),
                headers,
                parallelism,
                maxMediaSegments,
                tolerateTrailing404 = maxMediaSegments == null,
                progress = progress,
            ).file
        }
        return HlsDownloadResult(primary.file, audio, primary.container)
    }

    suspend fun downloadDash(
        manifestUrl: String,
        workDir: File,
        headers: Map<String, String> = emptyMap(),
        parallelism: Int = 4,
        maxMediaSegments: Int? = null,
        videoRepresentationId: String? = null,
        audioRepresentationId: String? = null,
        progress: (TransferProgress) -> Unit = {},
    ): DashDownloadResult {
        val manifest = PlaylistInspector.inspectDash(manifestUrl, headers, http)
        if (manifest.containsContentProtection || manifest.tracks.any { it.protected }) {
            throw ProtectedMediaException()
        }
        val videos = manifest.tracks.filter { it.contentType == "video" }
        val video = when {
            !videoRepresentationId.isNullOrBlank() -> videos.firstOrNull { it.id == videoRepresentationId }
                ?: error("고른 화질을 찾지 못했습니다")
            else -> QualityPicker.defaultVideo(videos)
        }
        val audio = QualityPicker.defaultAudio(manifest.tracks, audioRepresentationId)
        val videoFile = downloadTrack(
            video,
            File(workDir, "dash-video"),
            File(workDir, "video.mp4"),
            headers,
            parallelism,
            maxMediaSegments,
            progress,
        )
        val audioFile = audio?.let {
            downloadTrack(
                it,
                File(workDir, "dash-audio"),
                File(workDir, "audio.m4a"),
                headers,
                parallelism,
                maxMediaSegments,
                progress,
            )
        }
        val container = ContainerProbe.detect(videoFile)
        return DashDownloadResult(videoFile, audioFile, container)
    }

    private suspend fun downloadMediaPlaylist(
        url: String,
        segmentDir: File,
        output: File,
        headers: Map<String, String>,
        parallelism: Int,
        maxMediaSegments: Int?,
        tolerateTrailing404: Boolean,
        progress: (TransferProgress) -> Unit,
    ): JoinedFile {
        DownloadGuard.checkUrl(url)
        val parsed = HlsPlaylistParser.parse(http.getText(url, headers), url)
        val playlist = when (parsed) {
            is HlsParseResult.Media -> parsed.playlist
            is HlsParseResult.Master -> error("미디어 재생 목록이 아닙니다")
        }
        if (playlist.encrypted) throw ProtectedMediaException()
        rejectBlocked(playlist.segments.map { it.url } + listOfNotNull(playlist.initializationUrl))
        val limited = if (maxMediaSegments != null) playlist.segments.take(maxMediaSegments) else playlist.segments
        val jobs = mutableListOf<SegmentJob>()
        playlist.initializationUrl?.let { init ->
            jobs += SegmentJob(0, init, File(segmentDir, "init.bin"), playlist.initializationRange, isInit = true)
        }
        limited.forEachIndexed { index, segment ->
            jobs += SegmentJob(index + 1, segment.url, File(segmentDir, "seg-%05d.bin".format(index)), segment.byteRange)
        }
        downloadJobs(jobs, headers, parallelism, tolerateTrailing404 && maxMediaSegments == null, progress)
        val parts = orderedExisting(jobs)
        SegmentJoiner.join(parts, output)
        return JoinedFile(output, ContainerProbe.detect(output))
    }

    private suspend fun downloadTrack(
        track: DashTrack,
        segmentDir: File,
        output: File,
        headers: Map<String, String>,
        parallelism: Int,
        maxMediaSegments: Int?,
        progress: (TransferProgress) -> Unit,
    ): File {
        if (track.protected) throw ProtectedMediaException()
        val media = if (maxMediaSegments != null) track.mediaUrls.take(maxMediaSegments) else track.mediaUrls
        rejectBlocked(media + listOfNotNull(track.initializationUrl))
        val jobs = mutableListOf<SegmentJob>()
        track.initializationUrl?.let {
            jobs += SegmentJob(0, it, File(segmentDir, "init.bin"), null, isInit = true)
        }
        media.forEachIndexed { index, url ->
            jobs += SegmentJob(index + 1, url, File(segmentDir, "seg-%05d.bin".format(index)), null)
        }
        if (jobs.isEmpty()) error("받을 조각이 없습니다")
        downloadJobs(jobs, headers, parallelism, tolerateTrailing404 = maxMediaSegments == null, progress)
        SegmentJoiner.join(orderedExisting(jobs), output)
        return output
    }

    private fun rejectBlocked(urls: List<String>) {
        for (url in urls) {
            try {
                DownloadGuard.checkUrl(url)
            } catch (blocked: YouTubeBlockedException) {
                throw blocked
            }
        }
    }

    private suspend fun downloadJobs(
        jobs: List<SegmentJob>,
        headers: Map<String, String>,
        parallelism: Int,
        tolerateTrailing404: Boolean,
        progress: (TransferProgress) -> Unit,
    ) {
        if (jobs.isEmpty()) return
        val gate = Semaphore(parallelism.coerceIn(1, 8))
        val perJob = java.util.concurrent.ConcurrentHashMap<Int, Long>()
        val done = AtomicInteger(0)
        fun snapshot() {
            val sum = perJob.values.sum()
            emit(progress, sum, null, done.get(), jobs.size)
        }
        coroutineScope {
            jobs.map { job ->
                async(Dispatchers.IO) {
                    if (job.dest.exists() && job.dest.length() > 0) {
                        perJob[job.index] = job.dest.length()
                        done.incrementAndGet()
                        snapshot()
                        return@async
                    }
                    gate.withPermit {
                        coroutineContext.ensureActive()
                        try {
                            http.download(job.url, job.dest, headers, job.range) { current, _ ->
                                perJob[job.index] = current
                                snapshot()
                            }
                        } catch (status: HttpStatusException) {
                            val last = jobs.lastOrNull { !it.isInit }?.index
                            if (tolerateTrailing404 && status.code == 404 && job.index == last) return@async
                            throw status
                        }
                        perJob[job.index] = job.dest.length()
                        done.incrementAndGet()
                        snapshot()
                    }
                }
            }.forEach { it.await() }
        }
        snapshot()
    }

    private fun orderedExisting(jobs: List<SegmentJob>): List<File> {
        val missingMiddle = jobs.filter { !it.dest.exists() || it.dest.length() == 0L }
        val lastIndex = jobs.lastOrNull { !it.isInit }?.index
        if (missingMiddle.any { it.index != lastIndex }) error("조각을 받지 못했습니다")
        return jobs.map { it.dest }.filter { it.exists() && it.length() > 0L }
    }

    private fun emit(
        progress: (TransferProgress) -> Unit,
        bytes: Long,
        total: Long?,
        done: Int,
        totalSegments: Int,
    ) {
        progress(TransferProgress(bytes, total, done, totalSegments))
    }
}

private data class JoinedFile(val file: File, val container: ContainerKind)

private data class SegmentJob(
    val index: Int,
    val url: String,
    val dest: File,
    val range: ByteRange?,
    val isInit: Boolean = false,
)
