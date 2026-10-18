package app.saveclip.core.audio

import java.util.Locale

object AudioSave {
    const val MIME = "audio/mp4"
    const val EXTENSION = "m4a"
    const val RELATIVE_PATH = "Music/SaveClip"

    fun audioIndexes(mimes: List<String?>): List<Int> {
        return mimes.mapIndexedNotNull { index, mime ->
            val normalized = mime?.substringBefore(';')?.trim()?.lowercase(Locale.US) ?: return@mapIndexedNotNull null
            if (normalized.startsWith("audio/")) index else null
        }
    }

    fun hasAudio(mimes: List<String?>): Boolean = audioIndexes(mimes).isNotEmpty()
}
