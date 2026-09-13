package brain.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import brain.studio.resources.*
import org.jetbrains.compose.resources.painterResource

const val KASHA_VERSION = "1.1.4"

/**
 * Единая точка бренда: обе официальные версии знака лежат в common resources,
 * поэтому платформенные приложения не имеют собственных копий логотипа.
 */
@Composable
fun KashaBrandSlot(
    modifier: Modifier = Modifier,
    solid: Boolean = false,
    contentDescription: String? = "Kasha",
) {
    Icon(
        painter = painterResource(
            if (solid) Res.drawable.kasha_logo_solid else Res.drawable.kasha_logo,
        ),
        contentDescription = contentDescription,
        modifier = modifier,
        tint = KashaTheme.colors.textPrimary,
    )
}

@Composable
private fun KashaPaperSplashBackground() {
    val c = KashaTheme.colors
    Canvas(Modifier.fillMaxSize()) {
        drawRect(c.canvas)
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(c.backgroundIllumination, Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(
                    size.width * .4f,
                    size.height * .25f,
                ),
                radius = size.width,
            ),
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(c.backgroundShadowField, Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(
                    size.width * .95f,
                    size.height * .9f,
                ),
                radius = size.width * .9f,
            ),
        )
    }
}

@Composable
fun KashaSplash(modifier: Modifier = Modifier) {
    val dark = KashaTheme.isDark
    Box(modifier.fillMaxSize()) {
        if (dark) {
            Image(
                painter = painterResource(Res.drawable.buckwheat_dark_macro),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = Alignment.BottomEnd,
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x4D191715)),
            )
        } else {
            KashaPaperSplashBackground()
        }

        KashaBrandSlot(
            Modifier
                .align(Alignment.Center)
                .width(168.dp)
                .aspectRatio(672f / 793f),
            solid = true,
        )

        Text(
            "v$KASHA_VERSION",
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 26.dp),
            style = MaterialTheme.typography.labelSmall,
            color = KashaTheme.colors.textSecondary,
        )
    }
}
