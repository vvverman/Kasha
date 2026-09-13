package brain.studio

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun KashaWaveform(peaks: List<Float>, modifier: Modifier = Modifier, progress: Float? = null) {
    val c = MaterialTheme.colorScheme
    Canvas(modifier) {
        val data = if (peaks.isEmpty()) List(80) { 0f } else peaks
        val step = size.width / data.size
        data.forEachIndexed { i, p ->
            val height = (p.coerceIn(0f, 1f) * size.height * .88f).coerceAtLeast(1.2.dp.toPx())
            val color = if (progress != null && i.toFloat() / data.size > progress) c.onSurface.copy(alpha = .2f) else c.onSurface.copy(alpha = .82f)
            drawLine(
                color,
                Offset((i + .5f) * step, (size.height - height) / 2),
                Offset((i + .5f) * step, (size.height + height) / 2),
                (step * .34f).coerceIn(.8.dp.toPx(), 2.2.dp.toPx()),
                StrokeCap.Round,
            )
        }
    }
}

@Composable
fun KashaProcessingRing(modifier: Modifier = Modifier) {
    val rotation by rememberInfiniteTransition(label = "processing").animateFloat(
        0f,
        360f,
        infiniteRepeatable(tween(1800, easing = LinearEasing)),
        label = "rotation",
    )
    val c = MaterialTheme.colorScheme
    Canvas(modifier) {
        drawCircle(c.outline.copy(alpha = .18f), size.minDimension * .4f, style = Stroke(2.dp.toPx()))
        drawArc(
            c.primary,
            rotation,
            80f,
            false,
            Offset(size.width * .1f, size.height * .1f),
            Size(size.width * .8f, size.height * .8f),
            style = Stroke(2.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

@Composable
fun KashaCaptureMark(modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val left = w * .08f
        val top = h * .08f
        val cardW = w * .84f
        val cardH = h * .8f
        val radius = w * .09f
        drawRoundRect(c.surface, Offset(left, top), Size(cardW, cardH), CornerRadius(radius, radius))
        drawRoundRect(c.outline.copy(alpha = .2f), Offset(left, top), Size(cardW, cardH), CornerRadius(radius, radius), style = Stroke(1.dp.toPx()))
        drawCircle(c.primary, radius = w * .035f, center = Offset(w * .2f, h * .21f))
        drawLine(c.onSurfaceVariant.copy(alpha = .45f), Offset(w * .28f, h * .21f), Offset(w * .57f, h * .21f), 2.dp.toPx(), StrokeCap.Round)
        val bars = listOf(.18f, .38f, .62f, .84f, .48f, .72f, .34f, .56f, .24f)
        val startX = w * .2f
        val endX = w * .8f
        val step = (endX - startX) / (bars.size - 1)
        bars.forEachIndexed { index, value ->
            val x = startX + step * index
            val barH = h * .22f * value
            drawLine(c.onSurface, Offset(x, h * .46f - barH / 2), Offset(x, h * .46f + barH / 2), 4.dp.toPx(), StrokeCap.Round)
        }
        listOf(.64f, .76f, .56f).forEachIndexed { index, widthFraction ->
            val y = h * (.67f + index * .075f)
            drawLine(c.onSurfaceVariant.copy(alpha = .34f), Offset(w * .2f, y), Offset(w * widthFraction, y), 2.dp.toPx(), StrokeCap.Round)
        }
    }
}
