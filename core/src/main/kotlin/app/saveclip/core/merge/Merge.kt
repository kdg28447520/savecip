package app.saveclip.core.merge

import java.io.File
import java.io.FileOutputStream

object SegmentJoiner {
    fun join(parts: List<File>, output: File) {
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()
        FileOutputStream(output).use { out ->
            val buffer = ByteArray(1024 * 1024)
            for (part in parts) {
                part.inputStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                    }
                }
            }
        }
    }
}

enum class ContainerKind {
    MPEG_TS,
    FRAGMENTED_MP4,
    PROGRESSIVE_MP4,
    WEBM,
    UNKNOWN,
}

object ContainerProbe {
    fun detect(file: File): ContainerKind {
        file.inputStream().use { input ->
            val header = ByteArray(16)
            val read = input.read(header)
            if (read <= 0) return ContainerKind.UNKNOWN
            if (header[0] == 0x47.toByte()) return ContainerKind.MPEG_TS
            if (read >= 4 && header[0] == 0x1A.toByte() && header[1] == 0x45.toByte()) return ContainerKind.WEBM
        }
        val boxes = topLevelTypes(file)
        val hasMoof = "moof" in boxes || fileContainsType(file, "moof")
        val hasMoov = "moov" in boxes
        val hasFtyp = "ftyp" in boxes || "styp" in boxes
        if (hasFtyp && hasMoof) return ContainerKind.FRAGMENTED_MP4
        if (hasFtyp && hasMoov) return ContainerKind.PROGRESSIVE_MP4
        if (hasMoof) return ContainerKind.FRAGMENTED_MP4
        return ContainerKind.UNKNOWN
    }

    fun looksLikeTransportStream(file: File, packets: Int = 8): Boolean {
        file.inputStream().use { input ->
            val packet = ByteArray(188)
            var seen = 0
            while (seen < packets) {
                val read = readFully(input, packet)
                if (read == 0) return seen > 0
                if (read < 188) return false
                if (packet[0] != 0x47.toByte()) return false
                seen++
            }
            return true
        }
    }

    fun hasBox(file: File, type: String): Boolean {
        if (type in topLevelTypes(file)) return true
        return fileContainsType(file, type)
    }

    fun topLevelTypes(file: File, limit: Int = 32): List<String> {
        val types = mutableListOf<String>()
        file.inputStream().use { input ->
            val header = ByteArray(8)
            while (types.size < limit) {
                if (readFully(input, header) < 8) break
                val size32 = readU32(header, 0)
                val type = String(header, 4, 4, Charsets.ISO_8859_1)
                types += type
                val skip = when (size32) {
                    0L -> break
                    1L -> {
                        val large = ByteArray(8)
                        if (readFully(input, large) < 8) break
                        readU64(large) - 16
                    }
                    else -> size32 - 8
                }
                if (skip < 0 || !skipFully(input, skip)) break
                if (size32 == 0L) break
            }
        }
        return types
    }

    private fun fileContainsType(file: File, type: String): Boolean {
        val needle = type.toByteArray(Charsets.ISO_8859_1)
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 128)
            var previous = ByteArray(0)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) return false
                val window = previous + buffer.copyOf(read)
                if (indexOf(window, needle) >= 0) return true
                previous = window.takeLast(needle.size - 1).toByteArray()
            }
        }
    }

    private fun indexOf(data: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || data.size < needle.size) return -1
        for (i in 0..data.size - needle.size) {
            var matched = true
            for (j in needle.indices) {
                if (data[i + j] != needle[j]) {
                    matched = false
                    break
                }
            }
            if (matched) return i
        }
        return -1
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) return offset
            offset += read
        }
        return offset
    }

    private fun skipFully(input: java.io.InputStream, bytes: Long): Boolean {
        var left = bytes
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) {
                left -= skipped
                continue
            }
            if (input.read() < 0) return false
            left--
        }
        return true
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long {
        return ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)
    }

    private fun readU64(bytes: ByteArray): Long {
        var value = 0L
        for (byte in bytes) value = (value shl 8) or (byte.toLong() and 0xff)
        return value
    }
}
