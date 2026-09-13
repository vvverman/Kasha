package brain.studio

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Единственная библиотека продуктовых контролов Kasha. */
object KashaUi {
    val primaryButtonRadius = 20.dp
    val secondaryButtonRadius = 18.dp
    val panelRadius = 20.dp
    val fieldRadius = 16.dp
    val controlHeight = 56.dp
    val quietControlHeight = 48.dp
    val iconSize = 48.dp
    val iconGlyphSize = 22.dp
    val rowHeight = 64.dp
    val focusRingWidth = 2.dp
}

@Composable
private fun controlColors(primary: Boolean, hovered: Boolean, pressed: Boolean, enabled: Boolean): Pair<Color, Color> {
    val k = KashaTheme.colors
    val background = when {
        !enabled -> k.disabledFill
        primary && pressed -> k.accentPressedFill
        primary && hovered -> k.accentHoverFill
        primary -> k.accentFill
        pressed -> k.secondaryPressedFill
        hovered -> k.secondaryHoverFill
        else -> k.secondaryFill
    }
    val foreground = when {
        !enabled -> k.disabledContent
        primary -> k.onAccentFill
        else -> k.secondaryContent
    }
    return background to foreground
}

@Composable
fun KashaButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, glyph: Glyph? = null, enabled: Boolean = true) {
    val k = KashaTheme.colors
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    val focused by interactions.collectIsFocusedAsState()
    val (bg, fg) = controlColors(primary, hovered, pressed, enabled)
    val shape = RoundedCornerShape(if (primary) KashaUi.primaryButtonRadius else KashaUi.secondaryButtonRadius)
    Row(
        modifier.heightIn(min = KashaUi.controlHeight).clip(shape).background(bg)
            .border(KashaUi.focusRingWidth, if (focused) k.focusRing else Color.Transparent, shape)
            .hoverable(interactions, enabled).focusable(enabled, interactions)
            .clickable(interactionSource = interactions, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            KashaIcon(glyph, Modifier.size(KashaUi.iconGlyphSize), fg, animated = hovered || pressed || focused)
            Spacer(Modifier.width(10.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun KashaIconButton(label: String, glyph: Glyph, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = false, enabled: Boolean = true) {
    val k = KashaTheme.colors
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    val focused by interactions.collectIsFocusedAsState()
    val (filledBg, filledFg) = controlColors(true, hovered, pressed, enabled)
    val bg = when {
        !enabled -> k.disabledFill
        filled -> filledBg
        pressed -> k.secondaryPressedFill
        hovered -> k.secondaryHoverFill
        else -> k.secondaryFill
    }
    val fg = when {
        !enabled -> k.disabledContent
        filled -> filledFg
        else -> k.secondaryContent
    }
    Box(
        modifier.size(KashaUi.iconSize).clip(CircleShape).background(bg)
            .border(KashaUi.focusRingWidth, if (focused) k.focusRing else Color.Transparent, CircleShape)
            .hoverable(interactions, enabled).focusable(enabled, interactions)
            .clickable(interactionSource = interactions, indication = null, enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        KashaIcon(glyph, Modifier.size(KashaUi.iconGlyphSize), fg, animated = hovered || pressed || focused)
    }
}

@Composable
fun KashaQuietButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val k = KashaTheme.colors
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    val focused by interactions.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    val bg = when {
        pressed -> k.overlayPressed
        hovered -> k.overlayHover
        else -> Color.Transparent
    }
    Text(
        label,
        modifier.heightIn(min = KashaUi.quietControlHeight).clip(shape).background(bg)
            .border(KashaUi.focusRingWidth, if (focused) k.focusRing else Color.Transparent, shape)
            .hoverable(interactions, enabled).focusable(enabled, interactions)
            .clickable(interactionSource = interactions, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        style = MaterialTheme.typography.bodySmall,
        color = if (enabled) k.quietContent else k.disabledContent,
    )
}

@Composable
fun KashaField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    title: Boolean = false,
    multiline: Boolean = false,
    readOnly: Boolean = false,
    minHeight: Dp = if (multiline) 180.dp else 56.dp,
    onEditRequest: (() -> Unit)? = null,
) {
    val k = KashaTheme.colors
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(KashaUi.fieldRadius)
    val borderColor = when {
        focused -> k.focusRing
        hovered -> k.fieldOutline
        else -> k.borderHairline
    }
    val background = if (hovered && !focused) k.surfaceHigh else k.fieldFill
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = k.textSecondary)
        Spacer(Modifier.height(8.dp))
        val boxModifier = Modifier.fillMaxWidth().heightIn(min = minHeight).clip(shape).background(background)
            .border(if (focused) KashaUi.focusRingWidth else 1.dp, borderColor, shape)
            .hoverable(interactions)
            .then(if (readOnly && onEditRequest != null) Modifier.clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onEditRequest) else Modifier)
            .padding(horizontal = 16.dp, vertical = if (multiline) 15.dp else 14.dp)
        Row(boxModifier, verticalAlignment = if (multiline) Alignment.Top else Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (readOnly) {
                    Text(value.ifEmpty { label }, style = if (title) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge, color = if (value.isEmpty()) k.fieldPlaceholder else k.fieldContent)
                } else {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.semantics { contentDescription = label },
                        textStyle = (if (title) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge).copy(color = k.fieldContent),
                        cursorBrush = SolidColor(k.accentContent),
                        interactionSource = interactions,
                        singleLine = !multiline,
                        decorationBox = { inner -> Box { if (value.isEmpty()) Text(label, style = if (title) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge, color = k.fieldPlaceholder); inner() } },
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            KashaIcon(Glyph.EDIT, Modifier.size(18.dp), if (focused) k.textPrimary else k.textSecondary, animated = focused || hovered)
        }
    }
}

@Composable
fun KashaEditableNote(title: String, onTitleChange: (String) -> Unit, body: String, onBodyChange: (String) -> Unit, titleLabel: String, bodyLabel: String, modifier: Modifier = Modifier, readOnly: Boolean = false, onEditRequest: (() -> Unit)? = null) {
    Column(modifier) {
        KashaField(title, onTitleChange, titleLabel, Modifier.fillMaxWidth(), title = true, readOnly = readOnly, onEditRequest = onEditRequest)
        Spacer(Modifier.height(20.dp))
        KashaField(body, onBodyChange, bodyLabel, Modifier.fillMaxWidth(), multiline = true, readOnly = readOnly, minHeight = 210.dp, onEditRequest = onEditRequest)
    }
}

@Composable
fun KashaSwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val k = KashaTheme.colors
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    Row(
        modifier.fillMaxWidth().heightIn(min = KashaUi.rowHeight).clip(RoundedCornerShape(16.dp))
            .background(if (hovered) k.overlayHover else Color.Transparent)
            .hoverable(interactions, enabled).toggleable(value = value, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = label }.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f).padding(end = 20.dp), style = MaterialTheme.typography.bodyMedium, color = if (enabled) k.textPrimary else k.disabledContent)
        Box(Modifier.size(42.dp, 24.dp).clip(CircleShape).background(if (value) k.accentFill else k.surfaceHighest).padding(3.dp), contentAlignment = if (value) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.size(18.dp).clip(CircleShape).background(if (value) k.onAccentFill else k.textSecondary))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KashaSlider(value: Float, onValueChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, steps: Int, label: String, onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val k = KashaTheme.colors
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = range,
        steps = steps,
        onValueChangeFinished = onFinished,
        modifier = modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = label },
        thumb = { Box(Modifier.size(16.dp).clip(CircleShape).background(k.accentFill)) },
        track = {
            Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                val y = size.height / 2
                val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                drawLine(k.borderControl, Offset(0f, y), Offset(size.width, y), 3.dp.toPx(), StrokeCap.Round)
                drawLine(k.accentFill, Offset(0f, y), Offset(size.width * fraction, y), 3.dp.toPx(), StrokeCap.Round)
            }
        },
    )
}

@Composable
fun KashaPanel(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    val k = KashaTheme.colors
    val shape = RoundedCornerShape(KashaUi.panelRadius)
    Column(modifier.clip(shape).background(k.surface).border(1.dp, k.borderHairline, shape).padding(padding), content = content)
}

@Composable
fun KashaListCard(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val k = KashaTheme.colors
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    val focused by interactions.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    val bg = when {
        pressed -> k.overlayPressed
        hovered -> k.overlayHover
        else -> Color.Transparent
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = KashaUi.rowHeight).clip(shape).background(bg)
            .border(KashaUi.focusRingWidth, if (focused) k.focusRing else Color.Transparent, shape)
            .hoverable(interactions).focusable(true, interactions)
            .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
