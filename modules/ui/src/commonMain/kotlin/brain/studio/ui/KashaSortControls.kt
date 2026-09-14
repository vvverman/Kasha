package brain.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import brain.model.SortMode

@Composable
fun KashaSortBar(mode: SortMode, labels: Map<SortMode, String>, onSelect: (SortMode) -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SortMode.entries.forEach { item ->
            val selected = item == mode
            Text(
                labels.getValue(item),
                Modifier.clip(RoundedCornerShape(999.dp))
                    .background(if (selected) c.primary else c.surfaceVariant.copy(alpha = .52f))
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = { onSelect(item) },
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) c.onPrimary else c.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
