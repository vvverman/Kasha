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

/**
 * Единая точка бренда: обе официальные версии знака лежат в common resources,
 * поэтому платформенные приложения не имеют собственных копий логотипа.
 */
@Composable
fun KashaBrandSlot(
    modifier: Modifier = Modifier,
    solid: Boolean = false,
) {
    Icon(
        painter = painterResource(if (solid) Res.drawable.kasha_logo_solid else Res.drawable.kasha_logo),
        contentDescription = "Kasha",
        modifier = modifier,
        tint = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
fun KashaSplash(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        KashaBrandSlot(
            Modifier.align(Alignment.Center).width(190.dp).height(224.dp),
            solid = true,
        )
        Text(
            "v$KASHA_VERSION",
            Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
