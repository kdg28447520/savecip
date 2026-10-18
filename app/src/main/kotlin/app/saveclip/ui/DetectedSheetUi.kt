package app.saveclip.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.saveclip.core.detect.ClipInput
import app.saveclip.core.detect.DetectedSheet
import app.saveclip.core.detect.MediaFacts
import app.saveclip.core.detect.Notices
import app.saveclip.core.detect.PhotoInput
import app.saveclip.core.detect.QualityChip
import app.saveclip.core.detect.SheetVariant
import app.saveclip.core.detect.VideoCard
import app.saveclip.core.policy.YouTubeBlocklist
import app.saveclip.ui.theme.monoStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

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
    val showVideo = videoTab || built.photoCount == 0
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 640.dp)
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Box(
            Modifier
                .padding(top = 8.dp)
                .size(36.dp, 4.dp)
                .align(Alignment.CenterHorizontally)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outline),
        )
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("찾은 영상", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (built.photoCount > 0) {
                Segmented(
                    options = listOf(
                        "영상 ${built.videoCount}" to showVideo,
                        "사진 ${built.photoCount}" to !showVideo,
                    ),
                    onSelect = { videoTab = it == 0 },
                    modifier = Modifier.width(168.dp),
                )
            }
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("닫기") }
        }
        if (showVideo) {
            if (built.videoCount == 0) {
                Text(
                    "아직 찾은 영상이 없어요. 페이지에서 영상을 재생하면 여기에 나타나요.",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 220.dp)) {
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
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
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
                    Text("형식", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    Segmented(
                        options = listOf("영상 MP4" to !audio, "음성만 M4A" to audio),
                        onSelect = { audio = it == 1 },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("화질", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text("용량", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    InkRule()
                    selected.chips.forEach { option ->
                        QualityLine(option, option.id == chip.id) { chipId = option.id }
                    }
                    if (blocked) {
                        FailureLine(Notices.DRM, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                    Button(
                        onClick = { onDownload(chip.sourceUrl, chip.variantId, audio) },
                        enabled = !blocked,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).height(52.dp),
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.buttonColors(),
                    ) {
                        Text(DetectedSheet.cta(audio, chip), style = MaterialTheme.typography.labelLarge)
                    }
                    TextButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp),
                    ) {
                        MonoText("Movies/SaveClip", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("  변경", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.heightIn(min = 120.dp, max = 360.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(built.photos, key = { it.url }) { photo ->
                    Column {
                        RemoteThumb(photo.url, Modifier.fillMaxWidth().height(96.dp))
                        Text(photo.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            Button(
                onClick = { onDownloadPhotos(built.photos.map { it.url }) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).height(52.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(DetectedSheet.photoCta(built.photoCount), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    detail?.let { card ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text("자세히") },
            text = { Text(card.detailUrls.joinToString("\n"), style = monoStyle(12, 16)) },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("닫기") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoChoiceRow(card: VideoCard, selected: Boolean, onSelect: () -> Unit, onDetail: () -> Unit) {
    SelectionRule(selected = selected) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .combinedClickable(onClick = onSelect, onLongClick = onDetail)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = onSelect,
                colors = RadioButtonDefaults.colors(
                    selectedColor = MaterialTheme.colorScheme.onSurface,
                    unselectedColor = MaterialTheme.colorScheme.outline,
                ),
            )
            Box(Modifier.size(80.dp, 45.dp)) {
                RemoteThumb(card.posterUrl, Modifier.fillMaxSize())
                card.durationLabel?.let { label ->
                    MonoText(
                        label,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(2.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.inverseSurface)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(card.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                MonoText(card.meta)
            }
        }
        Hairline()
    }
}

@Composable
private fun QualityLine(chip: QualityChip, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.onSurface,
                unselectedColor = MaterialTheme.colorScheme.outline,
            ),
        )
        Text(chip.headline, style = monoStyle(15, 20, androidx.compose.ui.text.font.FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
        if (chip.original) {
            Text(
                "원본",
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.inverseSurface)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        val sizeLabel = chip.sizeLabel
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            when {
                !sizeLabel.isNullOrBlank() -> MonoText(sizeLabel, color = MaterialTheme.colorScheme.onSurface)
                chip.pending -> Text("용량 확인 중", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Hairline()
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
    Box(
        modifier.clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text("영상", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        }
    }
}
