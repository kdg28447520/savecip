package app.saveclip.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.saveclip.core.detect.BuiltSheet
import app.saveclip.core.detect.ClipInput
import app.saveclip.core.detect.DetectedSheet
import app.saveclip.core.detect.MediaFacts
import app.saveclip.core.detect.Notices
import app.saveclip.core.detect.PhotoInput
import app.saveclip.core.detect.QualityChip
import app.saveclip.core.detect.SheetVariant
import app.saveclip.core.detect.VideoCard
import app.saveclip.core.policy.YouTubeBlocklist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectedMediaSheet(
    clips: List<ClipInput>,
    photos: List<PhotoInput>,
    facts: Map<String, MediaFacts>,
    variants: Map<String, List<SheetVariant>>,
    protectedUrls: Set<String>,
    onDismiss: () -> Unit,
    onDownload: (sourceUrl: String, variantId: String?, audio: Boolean) -> Unit,
    onDownloadPhotos: (List<String>) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val built = remember(clips, photos, facts, variants) {
        DetectedSheet.build(clips, photos, facts, variants)
    }
    var videoTab by remember { mutableStateOf(true) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var chipId by remember { mutableStateOf<String?>(null) }
    var audio by remember { mutableStateOf(false) }
    var showShort by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<VideoCard?>(null) }
    LaunchedEffect(built.selectedId, built.selectedChipId) {
        if (selectedId == null || built.videos.none { it.id == selectedId } && built.short.none { it.id == selectedId }) {
            selectedId = built.selectedId
            chipId = built.selectedChipId
        }
    }
    val selected = (built.videos + built.short).firstOrNull { it.id == selectedId }
    val chip = selected?.chips?.firstOrNull { it.id == chipId } ?: selected?.chips?.firstOrNull()
    val blocked = chip != null && chip.sourceUrl in protectedUrls
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(bottom = 16.dp)) {
            SheetTabs(
                videoCount = built.videoCount,
                photoCount = built.photoCount,
                videoTab = videoTab || built.photoCount == 0,
                onVideo = { videoTab = true },
                onPhoto = { videoTab = false },
            )
            if (videoTab || built.photoCount == 0) {
                if (built.videoCount == 0) {
                    Text(
                        "아직 찾은 영상이 없어요. 페이지에서 영상을 재생하면 여기에 나타나요.",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(built.videos, key = { it.id }) { card ->
                            VideoChoiceRow(
                                card = card,
                                selected = card.id == selected?.id,
                                onSelect = {
                                    selectedId = card.id
                                    chipId = card.chips.firstOrNull { it.original }?.id ?: card.chips.firstOrNull()?.id
                                },
                                onDetail = { detail = card },
                            )
                        }
                        if (built.short.isNotEmpty()) {
                            item(key = "short-toggle") {
                                TextButton(
                                    onClick = { showShort = !showShort },
                                    modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp),
                                ) {
                                    Text(DetectedSheet.shortLabel(built.short.size, showShort))
                                }
                            }
                            if (showShort) {
                                items(built.short, key = { it.id }) { card ->
                                    VideoChoiceRow(
                                        card = card,
                                        selected = card.id == selected?.id,
                                        onSelect = {
                                            selectedId = card.id
                                            chipId = card.chips.firstOrNull { it.original }?.id ?: card.chips.firstOrNull()?.id
                                        },
                                        onDetail = { detail = card },
                                    )
                                }
                            }
                        }
                    }
                    if (selected != null && chip != null) {
                        FormatToggle(audio = audio, onAudio = { audio = it })
                        QualityRow(chips = selected.chips, selectedId = chip.id, onSelect = { chipId = it })
                        if (blocked) {
                            Text(
                                Notices.DRM,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Button(
                            onClick = { onDownload(chip.sourceUrl, chip.variantId, audio) },
                            enabled = !blocked,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(52.dp),
                            shape = CircleShape,
                        ) {
                            Text(DetectedSheet.cta(audio, chip), style = MaterialTheme.typography.titleMedium)
                        }
                        TextButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp),
                        ) {
                            Text("내장 메모리 › Movies › SaveClip · 변경")
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.heightIn(min = 120.dp, max = 360.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(built.photos, key = { it.url }) { photo ->
                        Column {
                            RemoteThumb(photo.url, Modifier.fillMaxWidth().height(96.dp))
                            Text(photo.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Button(
                    onClick = { onDownloadPhotos(built.photos.map { it.url }) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(52.dp),
                    shape = CircleShape,
                ) {
                    Text(DetectedSheet.photoCta(built.photoCount), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
    detail?.let { card ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text("자세히") },
            text = {
                Text(card.detailUrls.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
            },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("닫기") } },
        )
    }
}

@Composable
private fun SheetTabs(
    videoCount: Int,
    photoCount: Int,
    videoTab: Boolean,
    onVideo: () -> Unit,
    onPhoto: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onVideo, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (videoTab) "영상 $videoCount" else "영상 $videoCount", color = if (videoTab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (photoCount > 0) {
            TextButton(onClick = onPhoto, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("사진 $photoCount", color = if (!videoTab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoChoiceRow(card: VideoCard, selected: Boolean, onSelect: () -> Unit, onDetail: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(12.dp),
            )
            .combinedClickable(onClick = onSelect, onLongClick = onDetail)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(96.dp, 54.dp)) {
            RemoteThumb(card.posterUrl, Modifier.fillMaxSize())
            card.durationLabel?.let { label ->
                Text(
                    label,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.inverseSurface)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(card.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(card.meta, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        RadioButton(selected = selected, onClick = onSelect, modifier = Modifier.size(48.dp))
    }
}

@Composable
private fun FormatToggle(audio: Boolean, onAudio: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(40.dp)
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
    ) {
        Segment("영상 MP4", selected = !audio, onClick = { onAudio(false) }, modifier = Modifier.weight(1f))
        Segment("음성만 M4A", selected = audio, onClick = { onAudio(true) }, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (selected) "✓ $label" else label,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun QualityRow(chips: List<QualityChip>, selectedId: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chips.forEach { chip ->
            val selected = chip.id == selectedId
            Column(
                Modifier
                    .width(104.dp)
                    .height(64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                    .clickable { onSelect(chip.id) }
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (chip.original) {
                    Text(
                        "원본",
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.inverseSurface)
                            .padding(horizontal = 4.dp),
                    )
                }
                Text(chip.headline, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
                val sizeLabel = chip.sizeLabel
                if (!sizeLabel.isNullOrBlank()) {
                    Text(sizeLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (chip.pending) {
                    Text("용량 확인 중", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun RemoteThumb(url: String?, modifier: Modifier) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = null
        if (url.isNullOrBlank() || YouTubeBlocklist.isBlocked(url)) return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val connection = URL(url).openConnection()
                connection.connectTimeout = 4_000
                connection.readTimeout = 4_000
                connection.getInputStream().use { input ->
                    BitmapFactory.decodeStream(input)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text("영상", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        }
    }
}
