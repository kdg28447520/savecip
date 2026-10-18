package app.saveclip.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.util.Rational
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.saveclip.LocalContainer
import app.saveclip.ui.theme.PlayerPaper
import app.saveclip.ui.theme.PlayerTheme
import app.saveclip.ui.theme.monoStyle
import app.saveclip.core.player.GestureZone
import app.saveclip.core.player.OrientationMode
import app.saveclip.core.player.PlayerGestures
import app.saveclip.data.PlaybackEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private data class TextTrack(val label: String, val group: androidx.media3.common.TrackGroup, val index: Int)

@Composable
fun PlayerScreen(uri: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val container = LocalContainer.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
            prepare()
            playWhenReady = true
        }
    }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableFloatStateOf(1f) }
    var orientation by remember { mutableStateOf(OrientationMode.FREE) }
    var tracks by remember { mutableStateOf<List<TextTrack>>(emptyList()) }
    var subtitleMenu by remember { mutableStateOf(false) }
    var subtitleLabel by remember { mutableStateOf("자막 끔") }
    var dragLabel by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableLongStateOf(0L) }

    LaunchedEffect(uri) {
        saved = runCatching { container.database.playbackDao().get(uri)?.positionMs ?: 0L }.getOrDefault(0L)
    }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            delay(400)
        }
    }
    DisposableEffect(player, saved) {
        var resumed = false
        fun maybeResume() {
            if (resumed || player.playbackState != Player.STATE_READY) return
            resumed = true
            val target = PlayerGestures.resumePosition(saved, player.duration)
            if (target > 0L) player.seekTo(target)
        }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                maybeResume()
            }

            override fun onTracksChanged(playerTracks: Tracks) {
                tracks = textTracks(playerTracks)
            }
        }
        player.addListener(listener)
        maybeResume()
        onDispose {
            val stored = PlayerGestures.positionToStore(player.currentPosition, player.duration)
            player.removeListener(listener)
            player.release()
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            val attrs = activity.window.attributes
            attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            activity.window.attributes = attrs
            container.scope.launch {
                runCatching {
                    container.database.playbackDao().upsert(
                        PlaybackEntity(uri, stored, System.currentTimeMillis()),
                    )
                }
            }
        }
    }

    PlayerTheme {
    Box(Modifier.fillMaxSize().background(PlayerPaper)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
        )
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(player) {
                    var zone = GestureZone.SEEK
                    var origin = 0f
                    var dx = 0f
                    var dy = 0f
                    detectDragGestures(
                        onDragStart = { offset ->
                            zone = PlayerGestures.zone(offset.x, size.width.toFloat())
                            dx = 0f
                            dy = 0f
                            origin = when (zone) {
                                GestureZone.BRIGHTNESS -> brightnessOf(activity)
                                GestureZone.VOLUME -> volumeOf(audio)
                                GestureZone.SEEK -> player.currentPosition.toFloat()
                            }
                        },
                        onDragEnd = { dragLabel = null },
                        onDragCancel = { dragLabel = null },
                        onDrag = { change, amount ->
                            change.consume()
                            dx += amount.x
                            dy += amount.y
                            when (zone) {
                                GestureZone.BRIGHTNESS -> {
                                    val value = PlayerGestures.dragVertical(origin, dy, size.height.toFloat())
                                    setBrightness(activity, value)
                                    dragLabel = "밝기 ${(value * 100).roundToInt()}"
                                }
                                GestureZone.VOLUME -> {
                                    val value = PlayerGestures.dragVertical(origin, dy, size.height.toFloat())
                                    setVolume(audio, value)
                                    dragLabel = "소리 ${(value * 100).roundToInt()}"
                                }
                                GestureZone.SEEK -> {
                                    val target = PlayerGestures.seekTo(
                                        origin.toLong(),
                                        dx,
                                        size.width.toFloat(),
                                        player.duration.coerceAtLeast(0L),
                                    )
                                    player.seekTo(target)
                                    dragLabel = formatClock(target)
                                }
                            }
                        },
                    )
                },
        )
        dragLabel?.let { label ->
            Text(
                label,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).background(Color(0x88000000)).padding(12.dp),
            )
        }
        Row(
            Modifier.align(Alignment.TopStart).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = onBack) { Text("닫기", color = Color.White) }
            TextButton(onClick = {
                speed = PlayerGestures.nextSpeed(speed)
                player.setPlaybackSpeed(speed)
            }) { Text("${speed}x", color = Color.White) }
            TextButton(onClick = {
                orientation = PlayerGestures.nextOrientation(orientation)
                activity.requestedOrientation = when (orientation) {
                    OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    OrientationMode.FREE -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }) {
                Text(
                    when (orientation) {
                        OrientationMode.LANDSCAPE -> "가로"
                        OrientationMode.PORTRAIT -> "세로"
                        OrientationMode.FREE -> "회전"
                    },
                    color = Color.White,
                )
            }
            TextButton(onClick = { enterPip(activity) }) { Text("PiP", color = Color.White) }
            Box {
                TextButton(onClick = { subtitleMenu = true }) { Text(subtitleLabel, color = Color.White) }
                DropdownMenu(expanded = subtitleMenu, onDismissRequest = { subtitleMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("자막 끔") },
                        onClick = {
                            player.trackSelectionParameters = player.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                .build()
                            subtitleLabel = "자막 끔"
                            subtitleMenu = false
                        },
                    )
                    tracks.forEach { track ->
                        DropdownMenuItem(
                            text = { Text(track.label) },
                            onClick = {
                                player.trackSelectionParameters = player.trackSelectionParameters
                                    .buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                    .setOverrideForType(TrackSelectionOverride(track.group, listOf(track.index)))
                                    .build()
                                subtitleLabel = track.label
                                subtitleMenu = false
                            },
                        )
                    }
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    if (player.isPlaying) player.pause() else player.play()
                }) { Text(if (player.isPlaying) "일시정지" else "재생", color = Color.White) }
                MonoText(
                    "${formatClock(position)} / ${formatClock(duration)}",
                    color = Color.White,
                    style = monoStyle(12, 16),
                )
            }
            if (duration > 0L) {
                Slider(
                    value = position.toFloat().coerceIn(0f, duration.toFloat()),
                    onValueChange = { player.seekTo(it.toLong()) },
                    valueRange = 0f..duration.toFloat(),
                )
            }
        }
    }
    }
}

private fun textTracks(tracks: Tracks): List<TextTrack> {
    val found = mutableListOf<TextTrack>()
    for (group in tracks.groups) {
        if (group.type != C.TRACK_TYPE_TEXT) continue
        for (index in 0 until group.length) {
            val format = group.getTrackFormat(index)
            val label = format.label ?: format.language ?: "자막 ${found.size + 1}"
            found += TextTrack(label, group.mediaTrackGroup, index)
        }
    }
    return found
}

private fun brightnessOf(activity: Activity): Float {
    val current = activity.window.attributes.screenBrightness
    return if (current < 0f) 0.5f else current.coerceIn(0f, 1f)
}

private fun setBrightness(activity: Activity, value: Float) {
    val attrs = activity.window.attributes
    attrs.screenBrightness = value.coerceIn(0.01f, 1f)
    activity.window.attributes = attrs
}

private fun volumeOf(audio: AudioManager): Float {
    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max.toFloat()
}

private fun setVolume(audio: AudioManager, fraction: Float) {
    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val level = (fraction.coerceIn(0f, 1f) * max).roundToInt().coerceIn(0, max)
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
}

private fun enterPip(activity: Activity) {
    if (Build.VERSION.SDK_INT < 26 || activity !is ComponentActivity) return
    val params = android.app.PictureInPictureParams.Builder()
        .setAspectRatio(Rational(16, 9))
        .build()
    runCatching { activity.enterPictureInPictureMode(params) }
}

private fun formatClock(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val minutes = total / 60L
    val seconds = total % 60L
    return "%d:%02d".format(minutes, seconds)
}
