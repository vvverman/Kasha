package brain.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import brain.model.SortMode

/**
 * Одна команда с текущим режимом вместо четырёх постоянно видимых pills.
 * Список вариантов раскрывается только по запросу и остаётся общим для проектов,
 * заметок и задач.
 */
@Composable
fun KashaSortBar(
    mode: SortMode,
    labels: Map<SortMode, String>,
    onSelect: (SortMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = KashaTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val shape = RoundedCornerShape(KashaMetrics.radiusSmall)

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = KashaMetrics.touchTargetPreferred)
                .clip(shape)
                .background(if (hovered) c.overlayHover else androidx.compose.ui.graphics.Color.Transparent)
                .hoverable(interactions)
                .clickable(
                    interactionSource = interactions,
                    indication = null,
                    role = Role.Button,
                ) { expanded = !expanded }
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                labels.getValue(mode),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = c.textSecondary,
                maxLines = 2,
            )
            Spacer(Modifier.width(8.dp))
            KashaIcon(
                Glyph.DOWN,
                Modifier.size(18.dp),
                c.iconSecondary,
                animated = expanded || hovered,
            )
        }

        if (expanded) {
            Spacer(Modifier.height(6.dp))
            KashaPanel(
                Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                padding = 6.dp,
            ) {
                SortMode.entries.forEach { item ->
                    val selected = item == mode
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = KashaMetrics.touchTargetPreferred)
                            .clip(RoundedCornerShape(KashaMetrics.radiusSmall))
                            .background(if (selected) c.navigationActiveSpot else androidx.compose.ui.graphics.Color.Transparent)
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = {
                                    expanded = false
                                    if (!selected) onSelect(item)
                                },
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            labels.getValue(item),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected) c.textPrimary else c.textSecondary,
                        )
                        if (selected) {
                            Spacer(Modifier.width(10.dp))
                            KashaIcon(
                                Glyph.CHECK,
                                Modifier.size(18.dp),
                                c.accentContent,
                                animated = true,
                            )
                        }
                    }
                }
            }
        }
    }
}
