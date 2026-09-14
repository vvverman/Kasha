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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

enum class KashaNavigationLayout { Bottom, Grid, Sidebar }

@Composable
fun KashaNavigationSurface(
    layout: KashaNavigationLayout,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val k = KashaTheme.colors
    val shape = RoundedCornerShape(if (layout == KashaNavigationLayout.Sidebar) 24.dp else 28.dp)
    Box(
        modifier.clip(shape).background(k.surface)
            .border(1.dp, k.borderHairline, shape)
            .padding(if (layout == KashaNavigationLayout.Sidebar) 12.dp else 8.dp),
    ) { content() }
}

@Composable
fun KashaNavigationItem(
    label: String,
    glyph: Glyph,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    layout: KashaNavigationLayout = KashaNavigationLayout.Bottom,
) {
    val k = KashaTheme.colors
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    val focused by interactions.collectIsFocusedAsState()
    val shape = RoundedCornerShape(16.dp)
    val bg = when {
        pressed -> k.overlayPressed
        hovered -> k.overlayHover
        selected -> k.surfaceHigh
        else -> Color.Transparent
    }
    val fg = if (selected) k.textPrimary else k.textSecondary
    val base = modifier
        .heightIn(min = if (layout == KashaNavigationLayout.Bottom) 56.dp else 52.dp)
        .clip(shape).background(bg)
        .border(KashaUi.focusRingWidth, if (focused) k.focusRing else Color.Transparent, shape)
        .hoverable(interactions).focusable(true, interactions)
        .clickable(interactionSource = interactions, indication = null, role = Role.Tab, onClick = onClick)
        .semantics(mergeDescendants = true) { this.selected = selected }

    Box(
        base,
        contentAlignment = if (layout == KashaNavigationLayout.Bottom) Alignment.Center else Alignment.CenterStart,
    ) {
        when (layout) {
            KashaNavigationLayout.Bottom -> Column(
                Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                KashaIcon(glyph, Modifier.size(22.dp), fg, animated = hovered || pressed || focused)
                Spacer(Modifier.height(4.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 2)
            }
            KashaNavigationLayout.Grid, KashaNavigationLayout.Sidebar -> Row(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KashaIcon(glyph, Modifier.size(22.dp), fg, animated = hovered || pressed || focused)
                Spacer(Modifier.width(if (layout == KashaNavigationLayout.Grid) 8.dp else 12.dp))
                Text(label, style = if (layout == KashaNavigationLayout.Sidebar) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelMedium, color = fg, maxLines = 2)
            }
        }
    }
}
