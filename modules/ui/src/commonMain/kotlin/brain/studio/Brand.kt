package brain.studio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import brain.studio.resources.*
import org.jetbrains.compose.resources.painterResource

const val KASHA_VERSION = "1.1.4"

/** Официальный общий знак Kasha. Платформенные приложения не держат собственных копий. */
@Composable
fun KashaBrandSlot(
    modifier: Modifier = Modifier,
    solid: Boolean = false,
    contentDescription: String? = "Kasha",
) {
    Icon(
        painter = painterResource(if (solid) Res.drawable.kasha_logo_solid else Res.drawable.kasha_logo),
        contentDescription = contentDescription,
        modifier = modifier,
        tint = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
fun KashaSplash(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        // Процедурный fallback обязателен и одинаков на всех платформах.
        // Dark macro-photo подключается только как splash resource; рабочие экраны её не наследуют.
        KashaProceduralBackground(Modifier.fillMaxSize(), home = false)
        KashaBrandSlot(
            Modifier.align(Alignment.Center).width(168.dp).height(198.dp),
            solid = true,
        )
        Text(
            "v$KASHA_VERSION",
            Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp),
            style = MaterialTheme.typography.labelSmall,
            color = KashaTheme.colors.textSecondary,
        )
    }
}
