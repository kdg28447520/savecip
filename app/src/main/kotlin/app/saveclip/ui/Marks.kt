package app.saveclip.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun MarkBrowse(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        drawCircle(color, style = Stroke(width = 2.2.dp.toPx()))
        drawLine(color, Offset(size.width * 0.5f, size.height * 0.16f), Offset(size.width * 0.5f, size.height * 0.84f), 1.6.dp.toPx())
        drawLine(color, Offset(size.width * 0.16f, size.height * 0.5f), Offset(size.width * 0.84f, size.height * 0.5f), 1.6.dp.toPx())
    }
}

@Composable
fun MarkDown(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.5f, size.height * 0.16f)
            lineTo(size.width * 0.5f, size.height * 0.62f)
            moveTo(size.width * 0.28f, size.height * 0.44f)
            lineTo(size.width * 0.5f, size.height * 0.68f)
            lineTo(size.width * 0.72f, size.height * 0.44f)
        }
        drawPath(path, color, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round))
        drawLine(
            color,
            Offset(size.width * 0.22f, size.height * 0.82f),
            Offset(size.width * 0.78f, size.height * 0.82f),
            2.2.dp.toPx(),
            StrokeCap.Round,
        )
    }
}

@Composable
fun MarkFilm(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.18f, size.height * 0.16f),
            size = Size(size.width * 0.64f, size.height * 0.68f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
            style = Stroke(width = 2.dp.toPx()),
        )
        val play = Path().apply {
            moveTo(size.width * 0.42f, size.height * 0.36f)
            lineTo(size.width * 0.66f, size.height * 0.5f)
            lineTo(size.width * 0.42f, size.height * 0.64f)
            close()
        }
        drawPath(play, color)
    }
}

@Composable
fun MarkSliders(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        val ys = listOf(0.28f, 0.5f, 0.72f)
        val xs = listOf(0.35f, 0.62f, 0.46f)
        ys.forEachIndexed { index, y ->
            drawLine(
                color,
                Offset(size.width * 0.16f, size.height * y),
                Offset(size.width * 0.84f, size.height * y),
                2.dp.toPx(),
                StrokeCap.Round,
            )
            drawCircle(color, radius = 3.2.dp.toPx(), center = Offset(size.width * xs[index], size.height * y))
        }
    }
}
