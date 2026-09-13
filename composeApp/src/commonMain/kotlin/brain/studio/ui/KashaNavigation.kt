package brain.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun KashaNavigationItem(
    label: String,
    glyph: Glyph,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    horizontal: Boolean = false,
) {
    val c = KashaTheme.colors
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    val focused by interactions.collectIsFocusedAsState()
    val background = when {
        pressed -> c.overlayPressed
        selected -> c.navigationActiveSpot
        hovered -> c.overlayHover
        else -> Color.Transparent
    }
    val foreground =
        if (selected) c.navigationActiveContent else c.navigationInactiveContent
    val shape = RoundedCornerShape(15.dp)
    val base = modifier
        .clip(shape)
        .background(background)
        .border(
            if (focused) KashaMetrics.focusRingWidth else 1.dp,
            if (focused) c.focusRing else Color.Transparent,
            shape,
        )
        .hoverable(interactions)
        .focusable(true, interactions)
        .clickable(
            interactionSource = interactions,
            indication = null,
            role = Role.Tab,
            onClick = onClick,
        )
        .semantics(mergeDescendants = true) { this.selected = selected }

    if (horizontal) {
        Row(
            base
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KashaIcon(
                glyph,
                Modifier.size(KashaMetrics.iconGlyph),
                foreground,
                animated = hovered || pressed || focused,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = foreground,
            )
        }
    } else {
        Column(
            base
                .heightIn(min = KashaMetrics.touchTargetPreferred)
                .padding(vertical = 7.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            KashaIcon(
                glyph,
                Modifier.size(KashaMetrics.iconGlyph),
                foreground,
                animated = hovered || pressed || focused,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = foreground,
                maxLines = 2,
            )
        }
    }
}

@Composable
fun KashaBottomNavigation(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val c = KashaTheme.colors
    Row(
        modifier
            .clip(RoundedCornerShape(KashaMetrics.radiusNavigation))
            .background(c.navigation)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun KashaSidebarNavigation(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}
