package es.us.ussync.ui

import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

private const val FOLDER_PATH =
    "M4.2 19.4 V7.2 Q4.2 5.6 5.8 5.6 H9.1 Q10 5.6 10.6 6.2 L11.9 7.5 H18.2 Q19.8 7.5 19.8 9.1 V19.4 Q19.8 21 18.2 21 H5.8 Q4.2 21 4.2 19.4 Z"
private const val HOME_PATH =
    "M5 20 Q3.4 20 3.4 18.4 V10.6 Q3.4 9.9 3.9 9.4 L11.1 3 Q12 2.2 12.9 3 L20.1 9.4 Q20.6 9.9 20.6 10.6 V18.4 Q20.6 20 19 20 H15.4 V16.1 Q15.4 14.9 14.2 14.9 H9.8 Q8.6 14.9 8.6 16.1 V20 Z"
private const val BOOK_PATH =
    "M12 4.4 C10.7 3.4 8.6 3.3 6.6 3.8 L4.8 4.5 Q3.6 4.8 3.6 6 V17.6 Q3.6 18.8 4.8 19.1 L6.6 19.8 C8.6 20.4 10.7 20.4 12 19.3 M12 4.4 C13.3 3.4 15.4 3.3 17.4 3.8 L19.2 4.5 Q20.4 4.8 20.4 6 V17.6 Q20.4 18.8 19.2 19.1 L17.4 19.8 C15.4 20.4 13.3 20.4 12 19.3 M12 4.4 V19.3"

/** Draws an outline path from 24x24 viewBox data, centered and scaled to the canvas. */
@androidx.compose.runtime.Composable
private fun StrokedIcon(pathData: String, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val stroke = 1.9.dp.toPx()
        val path = PathParser().parsePathString(pathData).toPath()
        val scale = size.minDimension / 24f
        withTransform({
            translate((size.width - 24f * scale) / 2f, (size.height - 24f * scale) / 2f)
            scale(scale, scale, pivot = Offset.Zero)
        }) {
            drawPath(path, color, style = Stroke(width = stroke / scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Small outline icons used throughout the app, kept dependency-free and visually consistent. */
@androidx.compose.runtime.Composable
fun FolderIcon(color: Color, modifier: Modifier = Modifier) = StrokedIcon(FOLDER_PATH, color, modifier)

@androidx.compose.runtime.Composable
fun HomeIcon(color: Color, modifier: Modifier = Modifier) = StrokedIcon(HOME_PATH, color, modifier)

@androidx.compose.runtime.Composable
fun BookIcon(color: Color, modifier: Modifier = Modifier) = StrokedIcon(BOOK_PATH, color, modifier)

/** Rounded eight-tooth gear with a center ring. */
@androidx.compose.runtime.Composable
fun GearIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 1.9.dp.toPx()
        val center = Offset(size.width / 2f, size.height / 2f)
        val outer = size.minDimension / 2f - stroke
        val inner = outer * 0.74f
        val teeth = 8
        val steps = 240
        val path = Path()
        for (i in 0..steps) {
            val angle = (i.toFloat() / steps) * (2f * Math.PI.toFloat())
            val wave = 0.5f + 0.5f * cos(teeth * angle)
            val shaped = wave * wave * (3f - 2f * wave)
            val radius = inner + (outer - inner) * shaped
            val x = center.x + cos(angle) * radius
            val y = center.y + sin(angle) * radius
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(color, radius = inner * 0.42f, center = center, style = Stroke(width = stroke))
    }
}

private const val REFRESH_PATH =
    "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-8 8s3.58 8 8 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"

@androidx.compose.runtime.Composable
fun SyncIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val path = PathParser().parsePathString(REFRESH_PATH).toPath()
        val scale = size.minDimension / 24f
        withTransform({
            translate((size.width - 24f * scale) / 2f, (size.height - 24f * scale) / 2f)
            scale(scale, scale, pivot = Offset.Zero)
        }) {
            drawPath(path, color)
        }
    }
}

@androidx.compose.runtime.Composable
fun DotIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) { drawCircle(color, radius = size.minDimension * 0.3f) }
}

@androidx.compose.runtime.Composable
fun SearchIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 1.8.dp.toPx()
        drawCircle(color, radius = size.width * .25f, center = Offset(size.width * .43f, size.height * .43f), style = Stroke(stroke))
        drawLine(color, Offset(size.width * .61f, size.height * .61f), Offset(size.width * .84f, size.height * .84f), stroke, cap = StrokeCap.Round)
    }
}

@androidx.compose.runtime.Composable
fun DownloadIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 1.8.dp.toPx()
        val center = size.width / 2
        drawLine(color, Offset(center, size.height * .16f), Offset(center, size.height * .65f), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(center, size.height * .65f), Offset(size.width * .3f, size.height * .46f), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(center, size.height * .65f), Offset(size.width * .7f, size.height * .46f), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * .22f, size.height * .82f), Offset(size.width * .78f, size.height * .82f), stroke, cap = StrokeCap.Round)
    }
}
