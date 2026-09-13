package brain.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Главный процедурный объект записи Kasha из docs/design. */
@Composable
fun KashaCaptureOrb(
    modifier: Modifier = Modifier,
    recording: Boolean = false,
) {
    val c = KashaTheme.colors

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension * .43f

            drawCircle(
                color = c.orbBloom,
                radius = radius * 1.18f,
                center = center,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = if (recording) {
                        listOf(c.orbHighlight, c.orbBody, c.orbRecordingCore)
                    } else {
                        listOf(
                            c.orbHighlight,
                            c.orbCenter,
                            c.orbBody,
                            c.orbInnerMinimum,
                        )
                    },
                    center = Offset(size.width * .42f, size.height * .37f),
                    radius = radius * 1.45f,
                ),
                radius = radius,
                center = center,
            )
            drawCircle(
                color = c.waveformEdgeDecorative,
                radius = radius,
                center = center,
                style = Stroke(1.dp.toPx()),
            )
        }

        if (!recording) {
            KashaIcon(
                Glyph.RECORD,
                Modifier.size(34.dp),
                c.orbInk,
                animated = false,
            )
        }
    }
}
