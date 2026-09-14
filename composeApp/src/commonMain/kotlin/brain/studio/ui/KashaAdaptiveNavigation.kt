package brain.studio

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/**
 * Перекладывает одни и те же navigation nodes между 4×1 и 2×2,
 * не пересоздавая semantic tree при смене ширины окна.
 */
@Composable
fun KashaAdaptiveNavigationItems(
    grid: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val columns = if (grid) 2 else 4
        val gap = 4.dp.roundToPx()
        val availableWidth = constraints.maxWidth.coerceAtLeast(0)
        val itemWidth = ((availableWidth - gap * (columns - 1)) / columns).coerceAtLeast(0)
        val childConstraints = Constraints(
            minWidth = itemWidth,
            maxWidth = itemWidth,
            minHeight = 0,
            maxHeight = constraints.maxHeight,
        )
        val placeables = measurables.map { it.measure(childConstraints) }
        val rows = if (placeables.isEmpty()) 0 else (placeables.size + columns - 1) / columns
        val rowHeights = IntArray(rows) { row ->
            val from = row * columns
            val to = minOf(from + columns, placeables.size)
            (from until to).maxOfOrNull { placeables[it].height } ?: 0
        }
        val contentHeight = rowHeights.sum() + gap * (rows - 1).coerceAtLeast(0)
        val height = contentHeight.coerceIn(constraints.minHeight, constraints.maxHeight)

        layout(availableWidth, height) {
            var y = 0
            rowHeights.forEachIndexed { row, rowHeight ->
                val from = row * columns
                val to = minOf(from + columns, placeables.size)
                for (index in from until to) {
                    val column = index - from
                    placeables[index].placeRelative(column * (itemWidth + gap), y)
                }
                y += rowHeight + gap
            }
        }
    }
}
