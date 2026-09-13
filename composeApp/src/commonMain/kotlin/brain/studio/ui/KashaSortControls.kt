package brain.studio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import brain.model.SortMode

/**
 * Одна команда сортировки вместо ряда постоянных pills.
 * Текущий режим всегда виден; остальные варианты открываются только по запросу.
 */
@Composable
fun KashaSortBar(
    mode: SortMode,
    labels: Map<SortMode, String>,
    onSelect: (SortMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier.fillMaxWidth()) {
        KashaButton(
            label = labels.getValue(mode),
            onClick = { expanded = true },
            glyph = Glyph.SORT,
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            SortMode.entries.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Text(
                            labels.getValue(item),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    onClick = {
                        expanded = false
                        if (item != mode) onSelect(item)
                    },
                    leadingIcon = if (item == mode) {
                        { KashaIcon(Glyph.CHECK) }
                    } else null,
                )
            }
        }
    }
}
