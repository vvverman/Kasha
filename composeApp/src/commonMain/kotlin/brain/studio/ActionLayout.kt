package brain.studio

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal fun equalActionsFit(available: Float, labels: List<Float>, padding: Float, gap: Float, leading: Float = 0f): Boolean {
    if (!available.isFinite() || available < 0 || labels.isEmpty() || labels.any { !it.isFinite() || it < 0 }) return false
    val gaps = labels.size - 1 + if (leading > 0) 1 else 0
    return leading + (labels.maxOrNull()!! + padding) * labels.size + gaps * gap <= available
}

@Composable
internal fun actionsFit(width: Dp, labels: List<String>, glyph: Boolean = false, gap: Dp = 10.dp, leading: Dp = 0.dp): Boolean {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val widths = labels.map { measurer.measure(it, style = MaterialTheme.typography.labelLarge, maxLines = 1).size.width.toFloat() }
    return with(density) { equalActionsFit(width.toPx(), widths, (40.dp + if (glyph) 32.dp else 0.dp).toPx(), gap.toPx(), leading.toPx()) }
}
