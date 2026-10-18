package app.saveclip.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import app.saveclip.core.UserMessages
import java.io.File
import java.nio.ByteBuffer

object AndroidRemuxer {
    fun remux(inputs: List<File>, output: File) {
        require(inputs.isNotEmpty()) { UserMessages.REMUX_FAILED }
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()
        val extractors = mutableListOf<MediaExtractor>()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            data class Track(
                val extractorIndex: Int,
                val sourceIndex: Int,
                val destinationIndex: Int,
                var lastTimeUs: Long,
            )

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val tracks = mutableListOf<Track>()
            inputs.forEachIndexed { extractorIndex, file ->
                val extractor = MediaExtractor()
                extractors += extractor
                extractor.setDataSource(file.absolutePath)
                for (trackIndex in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(trackIndex)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                    val destination = muxer.addTrack(format)
                    extractor.selectTrack(trackIndex)
                    tracks += Track(extractorIndex, trackIndex, destination, -1L)
                }
            }
            if (tracks.isEmpty()) error(UserMessages.REMUX_FAILED)
            muxer.start()
            started = true
            val buffers = Array(extractors.size) { ByteBuffer.allocate(1024 * 1024) }
            val info = MediaCodec.BufferInfo()
            extractors.forEachIndexed { extractorIndex, extractor ->
                while (true) {
                    val sourceIndex = extractor.sampleTrackIndex
                    if (sourceIndex < 0) break
                    val track = tracks.find { it.extractorIndex == extractorIndex && it.sourceIndex == sourceIndex }
                    if (track == null) {
                        extractor.advance()
                        continue
                    }
                    val size = readSample(extractor, buffers, extractorIndex)
                    if (size < 0) break
                    var time = extractor.sampleTime
                    if (time < 0L) time = track.lastTimeUs + 1L
                    if (time <= track.lastTimeUs) time = track.lastTimeUs + 1L
                    track.lastTimeUs = time
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = time
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(track.destinationIndex, buffers[extractorIndex], info)
                    extractor.advance()
                }
            }
            muxer.stop()
            started = false
        } catch (error: Exception) {
            output.delete()
            if (error.message == UserMessages.REMUX_FAILED) throw error
            throw IllegalStateException(UserMessages.REMUX_FAILED, error)
        } finally {
            extractors.forEach { runCatching { it.release() } }
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun readSample(extractor: MediaExtractor, buffers: Array<ByteBuffer>, index: Int): Int {
        return try {
            extractor.readSampleData(buffers[index], 0)
        } catch (_: IllegalArgumentException) {
            buffers[index] = ByteBuffer.allocate(buffers[index].capacity() * 2)
            extractor.readSampleData(buffers[index], 0)
        }
    }
}
