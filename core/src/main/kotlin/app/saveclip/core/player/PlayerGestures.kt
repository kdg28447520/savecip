package app.saveclip.core.player

enum class GestureZone { BRIGHTNESS, VOLUME, SEEK }

enum class OrientationMode { FREE, LANDSCAPE, PORTRAIT }

object PlayerGestures {
    val speeds: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    fun zone(x: Float, width: Float): GestureZone {
        if (width <= 0f) return GestureZone.SEEK
        val ratio = x / width
        return when {
            ratio < 0.4f -> GestureZone.BRIGHTNESS
            ratio > 0.6f -> GestureZone.VOLUME
            else -> GestureZone.SEEK
        }
    }

    fun dragVertical(start: Float, dy: Float, height: Float): Float {
        if (height <= 0f) return start.coerceIn(0f, 1f)
        return (start - dy / height).coerceIn(0f, 1f)
    }

    fun seekTo(positionMs: Long, dx: Float, width: Float, durationMs: Long): Long {
        if (width <= 0f || durationMs <= 0L) return positionMs.coerceAtLeast(0L)
        val delta = ((dx / width) * durationMs.toFloat() * 0.25f).toLong()
        return (positionMs + delta).coerceIn(0L, durationMs)
    }

    fun nextSpeed(current: Float): Float {
        val index = speeds.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
        if (index < 0) return 1f
        return speeds[(index + 1) % speeds.size]
    }

    fun positionToStore(positionMs: Long, durationMs: Long): Long {
        if (positionMs < 3_000L) return 0L
        if (durationMs > 0L && positionMs >= durationMs - 3_000L) return 0L
        return positionMs
    }

    fun resumePosition(savedMs: Long, durationMs: Long): Long = positionToStore(savedMs, durationMs)

    fun nextOrientation(current: OrientationMode): OrientationMode = when (current) {
        OrientationMode.FREE -> OrientationMode.LANDSCAPE
        OrientationMode.LANDSCAPE -> OrientationMode.PORTRAIT
        OrientationMode.PORTRAIT -> OrientationMode.FREE
    }
}
