package brain.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import brain.studio.resources.Res
import brain.studio.resources.kasha_logo_solid
import org.jetbrains.compose.resources.painterResource
import androidx.compose.material3.Icon

/** Процедурный фон рабочих экранов. Фотографических ассетов здесь нет. */
@Composable
fun KashaWorkspaceBackground(
    modifier: Modifier = Modifier,
    showBrandGeometry: Boolean = false,
    intensity: Float = 1f,
    brandAlpha: Float = .03f,
) {
    val c = KashaTheme.colors
    val normalizedIntensity = intensity.coerceIn(0f, 1f)

    BoxWithConstraints(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(c.canvas)
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        c.backgroundIllumination.copy(
                            alpha = c.backgroundIllumination.alpha * normalizedIntensity,
                        ),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * .40f, size.height * .25f),
                    radius = size.width.coerceAtLeast(1f),
                ),
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        c.backgroundShadowField.copy(
                            alpha = c.backgroundShadowField.alpha * normalizedIntensity,
                        ),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * .95f, size.height * .90f),
                    radius = size.width * .90f,
                ),
            )
        }

        if (showBrandGeometry) {
            val logoWidth = maxWidth * 1.8f
            val logoHeight = logoWidth * (793f / 672f)
            val x = maxWidth * -.02f
            val y = maxHeight * .64f - logoHeight / 2f
            Icon(
                painter = painterResource(Res.drawable.kasha_logo_solid),
                contentDescription = null,
                tint = c.backgroundBrandShape,
                modifier = Modifier
                    .width(logoWidth)
                    .aspectRatio(672f / 793f)
                    .offset(x = x, y = y)
                    .graphicsLayer {
                        alpha = brandAlpha.coerceIn(0f, .04f)
                    },
            )
        }
    }
}
