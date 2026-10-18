package app.saveclip.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.saveclip.ui.theme.LocalMotionEnabled
import app.saveclip.ui.theme.ScMotion
import app.saveclip.ui.theme.monoStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object DownloadPulse {
    private val ticks = MutableStateFlow(0)
    val tick = ticks.asStateFlow()
    fun bump() {
        ticks.value = ticks.value + 1
    }
}

@Composable
fun MonoText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = monoStyle(12, 16),
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(text, modifier = modifier, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
fun RollingCount(
    value: Int,
    style: TextStyle,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    val motion = LocalMotionEnabled.current
    val label = value.coerceIn(0, 99).toString()
    if (!motion) {
        Text(label, modifier = modifier, style = style, color = color)
        return
    }
    AnimatedContent(
        targetState = label,
        modifier = modifier,
        transitionSpec = {
            (slideInVertically(ScMotion.digit()) { it } + fadeIn(ScMotion.digit()))
                .togetherWith(slideOutVertically(ScMotion.digit()) { -it } + fadeOut(ScMotion.digit()))
        },
        label = "rolling-count",
    ) { digit ->
        Text(digit, style = style, color = color)
    }
}

@Composable
fun IndexHeader(
    number: String,
    title: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoText(number, Modifier.width(28.dp), monoStyle(12, 16))
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(
                action,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onAction)
                    .padding(vertical = 14.dp),
            )
        }
    }
}

@Composable
fun Hairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
fun InkRule() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.onSurface),
    )
}

@Composable
fun FailureLine(message: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .padding(end = 8.dp)
                .width(3.dp)
                .height(18.dp)
                .background(MaterialTheme.colorScheme.error),
        )
        MarkAlert(MaterialTheme.colorScheme.error, Modifier.size(16.dp))
        Text(
            message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 4.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun InkSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedTrackColor = MaterialTheme.colorScheme.inverseSurface,
            checkedThumbColor = MaterialTheme.colorScheme.inverseOnSurface,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            uncheckedThumbColor = MaterialTheme.colorScheme.surface,
            uncheckedBorderColor = MaterialTheme.colorScheme.outline,
        ),
    )
}

@Composable
fun rememberBadgeScale(): Float {
    val tick by DownloadPulse.tick.collectAsState()
    val motion = LocalMotionEnabled.current
    val scale = remember { Animatable(1f) }
    LaunchedEffect(tick) {
        if (tick == 0) return@LaunchedEffect
        if (!motion) {
            scale.snapTo(1f)
            return@LaunchedEffect
        }
        scale.snapTo(0.6f)
        scale.animateTo(1f, ScMotion.badgePop())
    }
    return scale.value
}

@Composable
fun Segmented(
    options: List<Pair<String, Boolean>>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .height(40.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
    ) {
        options.forEachIndexed { index, (label, selected) ->
            Box(
                Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(RoundedCorner(6))
                    .background(if (selected) MaterialTheme.colorScheme.inverseSurface else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (selected) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun RoundedCorner(dp: Int) = androidx.compose.foundation.shape.RoundedCornerShape(dp.dp)

@Composable
fun SelectionRule(selected: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent),
        )
        Column(Modifier.weight(1f)) { content() }
    }
}
