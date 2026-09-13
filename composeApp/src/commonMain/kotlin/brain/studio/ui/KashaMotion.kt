package brain.studio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

@Immutable
data class KashaMotionSettings(
    val reduced: Boolean = false,
)

private val LocalKashaMotionSettings = staticCompositionLocalOf { KashaMotionSettings() }

object KashaMotion {
    val reduced: Boolean
        @Composable get() = LocalKashaMotionSettings.current.reduced
}

/**
 * Shared UI contract for the system Reduce Motion capability.
 * Platform shells provide the actual OS value; Kasha UI only consumes it.
 */
@Composable
fun KashaMotionProvider(reduced: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalKashaMotionSettings provides KashaMotionSettings(reduced), content = content)
}
