package brain.studio

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.pow

/** Визуальные состояния фирменного объекта записи. Доменные состояния остаются в Core. */
enum class KashaOrbState { IDLE, RECORDING, PAUSED, PROCESSING }

@Composable
fun KashaProceduralBackground(
    modifier: Modifier = Modifier,
    home: Boolean = false,
) {
    val k = KashaTheme.colors
    val dark = k.canvas.luminance() < .5f
    Canvas(modifier.fillMaxSize()) {
        drawRect(k.canvas)
        val warm = if (dark) Color(0xFF765B43).copy(alpha = if (home) .15f else .075f) else Color(0xFFE8C79F).copy(alpha = if (home) .14f else .07f)
        drawRect(
            Brush.radialGradient(
                0f to warm,
                1f to Color.Transparent,
                center = Offset(size.width * .40f, size.height * .25f),
                radius = size.width.coerceAtLeast(1f),
            )
        )
        val shadow = if (dark) Color.Black.copy(alpha = .16f) else Color(0xFF503A26).copy(alpha = .04f)
        drawRect(
            Brush.radialGradient(
                0f to shadow,
                1f to Color.Transparent,
                center = Offset(size.width * .95f, size.height * .90f),
                radius = size.width * .90f,
            )
        )
    }
}

@Composable
fun KashaRecordingOrb(
    state: KashaOrbState,
    modifier: Modifier = Modifier,
    peaks: List<Float> = emptyList(),
    level: Float = 0f,
) {
    val k = KashaTheme.colors
    val reduced = KashaMotion.reduced
    val dark = k.canvas.luminance() < .5f
    val breathing = if (state == KashaOrbState.RECORDING && !reduced) {
        val transition = rememberInfiniteTransition(label = "orb-breath")
        val scale by transition.animateFloat(
            1f,
            1.025f,
            infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
            label = "orb-scale",
        )
        scale
    } else 1f
    val diameter = when (state) {
        KashaOrbState.IDLE -> 184.dp
        KashaOrbState.RECORDING, KashaOrbState.PAUSED -> 196.dp
        KashaOrbState.PROCESSING -> 168.dp
    }
    val g = ((level.coerceIn(.05f, .70f) - .05f) / .65f).coerceIn(0f, 1f)
    val bloomAlpha = when (state) {
        KashaOrbState.RECORDING -> if (reduced) .065f else .045f + .070f * g
        KashaOrbState.PAUSED -> .025f
        else -> if (dark) .08f else .04f
    }
    Box(modifier.size(diameter).scale(breathing), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val d = size.minDimension
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color(0xFFD6AC7C).copy(alpha = bloomAlpha),
                    .67f to Color(0xFFD6AC7C).copy(alpha = bloomAlpha * .5f),
                    1f to Color.Transparent,
                    center = center,
                    radius = d * .82f,
                ),
                radius = d * .5f,
                center = center,
            )
            val sphere = if (state == KashaOrbState.RECORDING || state == KashaOrbState.PAUSED || state == KashaOrbState.PROCESSING) {
                Brush.radialGradient(
                    0f to Color(0xFF302A25),
                    .55f to Color(0xFF39312B),
                    .80f to Color(0xFF7A5E43),
                    .95f to Color(0xFFD4AD80),
                    1f to Color(0xFFE8C79F),
                    center = Offset(d * .40f, d * .31f),
                    radius = d * .78f,
                )
            } else {
                Brush.radialGradient(
                    0f to Color(0xFFF2D9BB),
                    .35f to Color(0xFFE8C79F),
                    .72f to Color(0xFFC6A078),
                    1f to Color(0xFF8D6C4D),
                    center = Offset(d * .40f, d * .31f),
                    radius = d * .78f,
                )
            }
            drawCircle(sphere, radius = d * .5f, center = center)
            drawCircle(
                Brush.radialGradient(
                    0f to Color(0xFFFFF1DE).copy(alpha = if (state == KashaOrbState.IDLE) .20f else .12f),
                    1f to Color.Transparent,
                    center = Offset(d * .29f, d * .24f),
                    radius = d * .48f,
                ),
                radius = d * .5f,
                center = center,
            )
            drawArc(
                color = Color(0xFFF8E3C8).copy(alpha = .52f),
                startAngle = 200f,
                sweepAngle = 155f,
                useCenter = false,
                topLeft = Offset(1.dp.toPx(), 1.dp.toPx()),
                size = Size(d - 2.dp.toPx(), d - 2.dp.toPx()),
                style = Stroke(1.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        when (state) {
            KashaOrbState.IDLE -> KashaIcon(Glyph.MIC, Modifier.size(32.dp), Color(0xFF211E1B))
            KashaOrbState.RECORDING, KashaOrbState.PAUSED -> KashaWaveform(peaks, Modifier.size(156.dp, 96.dp), insideRecordingOrb = true)
            KashaOrbState.PROCESSING -> KashaProcessingRing(Modifier.size(74.dp))
        }
    }
}

@Composable
fun KashaWaveform(
    peaks: List<Float>,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    insideRecordingOrb: Boolean = false,
) {
    val k = KashaTheme.colors
    val dark = k.canvas.luminance() < .5f
    Canvas(modifier) {
        val compact = size.height <= 40.dp.toPx()
        val bar = (if (compact) 2.dp else 2.5.dp).toPx()
        val gap = (if (compact) 3.dp else 4.dp).toPx()
        val minimum = (if (compact) 3.dp else 4.dp).toPx()
        val count = floor((size.width + gap) / (bar + gap)).toInt().coerceAtLeast(1)
        val source = if (peaks.isEmpty()) List(count) { 0f } else List(count) { index ->
            val start = index * peaks.size / count
            val end = ((index + 1) * peaks.size / count).coerceAtLeast(start + 1).coerceAtMost(peaks.size)
            peaks.subList(start.coerceAtMost(peaks.lastIndex), end).maxOrNull() ?: 0f
        }
        val occupied = count * bar + (count - 1) * gap
        val left = ((size.width - occupied) / 2f).coerceAtLeast(0f)
        source.forEachIndexed { index, raw ->
            val u = raw.coerceIn(0f, 1f).pow(.85f)
            val height = minimum + u * (size.height - minimum)
            val x = left + bar / 2f + index * (bar + gap)
            val fraction = if (count == 1) .5f else index.toFloat() / (count - 1)
            val edgeMask = when {
                fraction < .10f -> .25f + .75f * (fraction / .10f)
                fraction > .90f -> .25f + .75f * ((1f - fraction) / .10f)
                else -> 1f
            }
            val played = progress == null || fraction <= progress.coerceIn(0f, 1f)
            val base = when {
                insideRecordingOrb -> Color(0xFFE8C79F)
                progress != null && !played -> k.borderControl
                progress != null -> k.accentFill
                dark -> k.accentContent
                else -> k.accentContent
            }
            drawLine(
                base.copy(alpha = if (progress == null) edgeMask else 1f),
                Offset(x, (size.height - height) / 2f),
                Offset(x, (size.height + height) / 2f),
                bar,
                StrokeCap.Round,
            )
        }
    }
}

@Composable
fun KashaProcessingRing(modifier: Modifier = Modifier) {
    val reduced = KashaMotion.reduced
    val rotation = if (reduced) 0f else {
        val value by rememberInfiniteTransition(label = "processing").animateFloat(
            0f,
            360f,
            infiniteRepeatable(tween(6000, easing = LinearEasing)),
            label = "rotation",
        )
        value
    }
    val k = KashaTheme.colors
    Canvas(modifier) {
        drawCircle(k.borderControl, size.minDimension * .4f, style = Stroke(2.dp.toPx()))
        drawArc(
            k.accentContent,
            rotation,
            80f,
            false,
            Offset(size.width * .1f, size.height * .1f),
            Size(size.width * .8f, size.height * .8f),
            style = Stroke(2.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

/** Legacy call site compatibility; target Home uses the same canonical idle orb. */
@Composable
fun KashaCaptureMark(modifier: Modifier = Modifier) {
    KashaRecordingOrb(KashaOrbState.IDLE, modifier)
}
