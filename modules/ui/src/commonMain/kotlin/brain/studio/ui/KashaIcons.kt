package brain.studio

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Небольшой собственный набор Kasha. Никаких внешних icon packs в runtime. */
enum class Glyph {
    RECORD, PLAY, PAUSE, STOP, SEND, HOME, FOLDER, TASKS, SETTINGS,
    BACK, NEXT, PLUS, DELETE, MAGIC, MORE, PIN, EDIT, UP, DOWN, CHECK,
    ARCHIVE, CLOCK,
}

private data class Motion(
    val rotation: Float = 0f,
    val x: Float = 0f,
    val y: Float = 0f,
    val scale: Float = 1f,
)

/** Короткие функциональные движения на hover/press/focus. */
private fun motion(glyph: Glyph): Motion = when (glyph) {
    Glyph.RECORD -> Motion(scale = 1.10f)
    Glyph.PLAY -> Motion(x = 1.8f, scale = 1.06f)
    Glyph.PAUSE -> Motion(scale = .92f)
    Glyph.STOP -> Motion(rotation = 8f, scale = .94f)
    Glyph.SEND -> Motion(rotation = -7f, x = 2.2f, y = -1.6f, scale = 1.04f)
    Glyph.HOME -> Motion(y = -1.6f, scale = 1.04f)
    Glyph.FOLDER -> Motion(rotation = -4f, y = -1f)
    Glyph.TASKS -> Motion(y = -1.5f, scale = 1.04f)
    Glyph.SETTINGS -> Motion(rotation = 42f)
    Glyph.BACK -> Motion(x = -2.5f)
    Glyph.NEXT -> Motion(x = 2.5f)
    Glyph.PLUS -> Motion(rotation = 90f, scale = 1.08f)
    Glyph.DELETE -> Motion(rotation = -6f, y = 1f)
    Glyph.MAGIC -> Motion(rotation = 9f, y = -1.5f, scale = 1.05f)
    Glyph.MORE -> Motion(scale = 1.14f)
    Glyph.PIN -> Motion(y = -2f, scale = 1.05f)
    Glyph.EDIT -> Motion(rotation = -5f, x = 1.2f, y = -1.2f)
    Glyph.UP -> Motion(y = -2.5f)
    Glyph.DOWN -> Motion(y = 2.5f)
    Glyph.CHECK -> Motion(scale = 1.12f)
    Glyph.ARCHIVE -> Motion(y = 1.8f, scale = .96f)
    Glyph.CLOCK -> Motion(rotation = 18f)
}

@Composable
fun KashaIcon(
    glyph: Glyph,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    animated: Boolean = false,
) {
    val spec = motion(glyph)
    val phase by animateFloatAsState(
        targetValue = if (animated) 1f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "kasha-${glyph.name.lowercase()}",
    )
    Canvas(
        modifier.graphicsLayer {
            rotationZ = spec.rotation * phase
            translationX = spec.x * density * phase
            translationY = spec.y * density * phase
            val s = 1f + (spec.scale - 1f) * phase
            scaleX = s
            scaleY = s
        },
    ) {
        val u = size.minDimension / 24f
        val dx = (size.width - 24f * u) / 2f
        val dy = (size.height - 24f * u) / 2f
        fun p(x: Float, y: Float) = Offset(dx + x * u, dy + y * u)
        val strokeWidth = 1.9f * u
        val stroke = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, p(x1, y1), p(x2, y2), strokeWidth, StrokeCap.Round)
        fun circle(x: Float, y: Float, r: Float, fill: Boolean = false) = drawCircle(color, r * u, p(x, y), style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else stroke)
        fun path(points: List<Pair<Float, Float>>, close: Boolean = false, fill: Boolean = false) {
            val shape = Path().apply {
                if (points.isNotEmpty()) {
                    moveTo(p(points[0].first, points[0].second).x, p(points[0].first, points[0].second).y)
                    points.drop(1).forEach { (x, y) -> lineTo(p(x, y).x, p(x, y).y) }
                    if (close) close()
                }
            }
            drawPath(shape, color, style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else stroke)
        }

        when (glyph) {
            Glyph.RECORD -> circle(12f, 12f, 5.2f, fill = true)
            Glyph.PLAY -> path(listOf(8.5f to 6.2f, 18f to 12f, 8.5f to 17.8f), close = true, fill = true)
            Glyph.PAUSE -> {
                drawRoundRect(color, p(7f, 6f), Size(3.4f * u, 12f * u), CornerRadius(1.2f * u))
                drawRoundRect(color, p(13.6f, 6f), Size(3.4f * u, 12f * u), CornerRadius(1.2f * u))
            }
            Glyph.STOP -> drawRoundRect(color, p(7.5f, 7.5f), Size(9f * u, 9f * u), CornerRadius(2f * u))
            Glyph.SEND -> {
                path(listOf(3.3f to 11.1f, 20.5f to 4.2f, 13.5f to 20.4f, 10.6f to 13.7f), close = true)
                line(10.6f, 13.7f, 20.5f, 4.2f)
            }
            Glyph.HOME -> {
                path(listOf(3.8f to 11f, 12f to 4.4f, 20.2f to 11f))
                path(listOf(6.2f to 9.4f, 6.2f to 19.2f, 17.8f to 19.2f, 17.8f to 9.4f))
                line(10f, 19.2f, 10f, 14f); line(14f, 14f, 14f, 19.2f)
            }
            Glyph.FOLDER -> {
                val shape = Path().apply {
                    moveTo(p(3.3f, 7.3f).x, p(3.3f, 7.3f).y)
                    lineTo(p(9.2f, 7.3f).x, p(9.2f, 7.3f).y)
                    lineTo(p(11.2f, 9.2f).x, p(11.2f, 9.2f).y)
                    lineTo(p(20.7f, 9.2f).x, p(20.7f, 9.2f).y)
                    lineTo(p(19.5f, 18.3f).x, p(19.5f, 18.3f).y)
                    lineTo(p(4.5f, 18.3f).x, p(4.5f, 18.3f).y)
                    close()
                }
                drawPath(shape, color, style = stroke)
            }
            Glyph.TASKS -> {
                drawRoundRect(color, p(4f, 4f), Size(16f * u, 16f * u), CornerRadius(3f * u), style = stroke)
                path(listOf(7f to 9f, 8.6f to 10.5f, 11f to 7.5f))
                line(13f, 9f, 17f, 9f)
                path(listOf(7f to 15f, 8.6f to 16.5f, 11f to 13.5f))
                line(13f, 15f, 17f, 15f)
            }
            Glyph.SETTINGS -> {
                circle(12f, 12f, 3.1f)
                repeat(8) { i ->
                    val a = i * 45.0 * PI / 180.0
                    val x1 = 12f + cos(a).toFloat() * 5.2f
                    val y1 = 12f + sin(a).toFloat() * 5.2f
                    val x2 = 12f + cos(a).toFloat() * 8f
                    val y2 = 12f + sin(a).toFloat() * 8f
                    line(x1, y1, x2, y2)
                }
            }
            Glyph.BACK -> { line(18f, 5f, 11f, 12f); line(11f, 12f, 18f, 19f) }
            Glyph.NEXT -> { line(6f, 5f, 13f, 12f); line(13f, 12f, 6f, 19f) }
            Glyph.UP -> { line(5f, 15f, 12f, 8f); line(12f, 8f, 19f, 15f) }
            Glyph.DOWN -> { line(5f, 9f, 12f, 16f); line(12f, 16f, 19f, 9f) }
            Glyph.PLUS -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            Glyph.DELETE -> {
                path(listOf(7f to 8f, 8f to 20f, 16f to 20f, 17f to 8f))
                line(5.5f, 8f, 18.5f, 8f); line(9f, 5f, 15f, 5f)
                line(10f, 11f, 10.5f, 17f); line(14f, 11f, 13.5f, 17f)
            }
            Glyph.MAGIC -> {
                line(6f, 18f, 16.5f, 7.5f); line(14.8f, 5.8f, 18.2f, 9.2f)
                line(6f, 5f, 6f, 8f); line(4.5f, 6.5f, 7.5f, 6.5f)
                line(18.5f, 15.5f, 18.5f, 19f); line(16.8f, 17.2f, 20.2f, 17.2f)
            }
            Glyph.MORE -> { circle(6f, 12f, 1.35f, true); circle(12f, 12f, 1.35f, true); circle(18f, 12f, 1.35f, true) }
            Glyph.PIN -> {
                path(listOf(8f to 4.5f, 16f to 4.5f, 14.5f to 10f, 17.5f to 13f, 6.5f to 13f, 9.5f to 10f), close = true)
                line(12f, 13f, 12f, 20f)
            }
            Glyph.EDIT -> {
                path(listOf(5f to 16.5f, 5f to 19f, 7.5f to 19f, 18.7f to 7.8f, 16.2f to 5.3f), close = true)
                line(14.8f, 6.7f, 17.3f, 9.2f)
            }
            Glyph.CHECK -> path(listOf(5.5f to 12.5f, 10f to 17f, 18.8f to 7.5f))
            Glyph.ARCHIVE -> {
                drawRoundRect(color, p(5f, 8f), Size(14f * u, 11f * u), CornerRadius(2f * u), style = stroke)
                drawRoundRect(color, p(4f, 5f), Size(16f * u, 4f * u), CornerRadius(1.5f * u), style = stroke)
                line(9f, 12f, 15f, 12f)
            }
            Glyph.CLOCK -> {
                circle(12f, 12f, 8f)
                line(12f, 7.5f, 12f, 12f); line(12f, 12f, 15.5f, 14f)
            }
        }
    }
}

@Composable
fun Symbol(
    glyph: Glyph,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    animated: Boolean = false,
) = KashaIcon(glyph, modifier, color, animated)
