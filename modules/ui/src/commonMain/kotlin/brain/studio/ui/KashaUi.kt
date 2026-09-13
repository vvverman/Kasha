package brain.studio

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Единственная библиотека продуктовых контролов Kasha. */
object KashaUi {
    val controlRadius = 16.dp
    val panelRadius = 22.dp
    val fieldRadius = 18.dp
    val controlHeight = 50.dp
    val iconSize = 44.dp
}

@Composable
private fun controlColors(primary: Boolean, hovered: Boolean, pressed: Boolean, focused: Boolean, enabled: Boolean): Pair<Color, Color> {
    val c = MaterialTheme.colorScheme
    val base = if (primary) c.primary else c.surface
    val foreground = if (primary) c.onPrimary else c.onSurface
    val background = when {
        !enabled -> base.copy(alpha = if (primary) .42f else .58f)
        pressed -> if (primary) base.copy(alpha = .82f) else c.surfaceVariant.copy(alpha = .92f)
        hovered -> if (primary) base.copy(alpha = .9f) else c.surfaceVariant.copy(alpha = .62f)
        focused -> if (primary) base else c.surfaceVariant.copy(alpha = .42f)
        else -> base
    }
    return background to foreground.copy(alpha = if (enabled) 1f else .58f)
}

@Composable
fun KashaButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    glyph: Glyph? = null,
    enabled: Boolean = true,
) {
    val c = MaterialTheme.colorScheme
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    val focused by interactions.collectIsFocusedAsState()
    val (bg, fg) = controlColors(primary, hovered, pressed, focused, enabled)
    val border = when {
        focused -> c.primary.copy(alpha = .62f)
        hovered && !primary -> c.outline.copy(alpha = .42f)
        else -> Color.Transparent
    }
    Row(
        modifier
            .heightIn(min = KashaUi.controlHeight)
            .clip(RoundedCornerShape(KashaUi.controlRadius))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(KashaUi.controlRadius))
            .hoverable(interactions, enabled)
            .focusable(enabled, interactions)
            .clickable(interactionSource = interactions, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            KashaIcon(glyph, Modifier.size(18.dp), fg, animated = hovered || pressed || focused)
            Spacer(Modifier.width(10.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun KashaIconButton(
    label: String,
    glyph: Glyph,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    enabled: Boolean = true,
) {
    val c = MaterialTheme.colorScheme
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    val focused by interactions.collectIsFocusedAsState()
    val (bg, fg) = controlColors(filled, hovered, pressed, focused, enabled)
    val quietBg = when {
        filled -> bg
        pressed -> c.surfaceVariant
        hovered -> c.surfaceVariant.copy(alpha = .72f)
        else -> c.surfaceVariant.copy(alpha = .46f)
    }
    Box(
        modifier
            .size(KashaUi.iconSize)
            .clip(CircleShape)
            .background(quietBg)
            .border(1.dp, if (focused) c.primary.copy(alpha = .58f) else Color.Transparent, CircleShape)
            .hoverable(interactions, enabled)
            .focusable(enabled, interactions)
            .clickable(interactionSource = interactions, indication = null, enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        KashaIcon(glyph, Modifier.size(20.dp), if (filled) fg else c.onSurface.copy(alpha = if (enabled) 1f else .45f), animated = hovered || pressed || focused)
    }
}

@Composable
fun KashaQuietButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = MaterialTheme.colorScheme
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    Text(
        label,
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (pressed) c.surfaceVariant else if (hovered) c.surfaceVariant.copy(alpha = .55f) else Color.Transparent)
            .hoverable(interactions, enabled)
            .clickable(interactionSource = interactions, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 11.dp, horizontal = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = c.onSurfaceVariant.copy(alpha = if (enabled) 1f else .45f),
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
    minHeight: Dp = if (multiline) 180.dp else 58.dp,
    onEditRequest: (() -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(KashaUi.fieldRadius)
    val borderColor = when {
        focused -> c.primary.copy(alpha = .72f)
        hovered -> c.outline.copy(alpha = .58f)
        else -> c.outline.copy(alpha = .26f)
    }
    val background = if (hovered && !focused) c.surfaceVariant.copy(alpha = .32f) else c.surface
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
        Spacer(Modifier.height(7.dp))
        val boxModifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(shape)
            .background(background)
            .border(1.dp, borderColor, shape)
            .hoverable(interactions)
            .then(if (readOnly && onEditRequest != null) Modifier.clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onEditRequest) else Modifier)
            .padding(horizontal = 16.dp, vertical = if (multiline) 15.dp else 13.dp)
        Row(boxModifier, verticalAlignment = if (multiline) Alignment.Top else Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (readOnly) {
                    Text(value.ifEmpty { label }, style = if (title) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge,
                        color = if (value.isEmpty()) c.onSurfaceVariant.copy(alpha = .55f) else c.onSurface)
                } else {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.semantics { contentDescription = label },
                        textStyle = (if (title) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge).copy(color = c.onSurface),
                        cursorBrush = SolidColor(c.onSurface),
                        interactionSource = interactions,
                        singleLine = !multiline,
                        decorationBox = { inner ->
                            Box {
                                if (value.isEmpty()) Text(label, style = if (title) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant.copy(alpha = .5f))
                                inner()
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            KashaIcon(Glyph.EDIT, Modifier.size(17.dp), if (focused) c.onSurface else c.onSurfaceVariant.copy(alpha = .72f), animated = focused || hovered)
        }
    }
}

@Composable
fun KashaEditableNote(
    title: String,
    onTitleChange: (String) -> Unit,
    body: String,
    onBodyChange: (String) -> Unit,
    titleLabel: String,
    bodyLabel: String,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    onEditRequest: (() -> Unit)? = null,
) {
    Column(modifier) {
        KashaField(title, onTitleChange, titleLabel, Modifier.fillMaxWidth(), title = true, readOnly = readOnly, onEditRequest = onEditRequest)
        Spacer(Modifier.height(18.dp))
        KashaField(body, onBodyChange, bodyLabel, Modifier.fillMaxWidth(), multiline = true, readOnly = readOnly, minHeight = 210.dp, onEditRequest = onEditRequest)
    }
}

@Composable
fun KashaSwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = MaterialTheme.colorScheme
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    Row(
        modifier.fillMaxWidth().heightIn(min = 62.dp).clip(RoundedCornerShape(16.dp))
            .background(if (hovered) c.surfaceVariant.copy(alpha = .3f) else Color.Transparent)
            .hoverable(interactions, enabled).toggleable(value = value, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = label }.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f).padding(end = 20.dp), style = MaterialTheme.typography.bodyMedium, color = c.onSurface.copy(alpha = if (enabled) 1f else .5f))
        Box(Modifier.size(42.dp, 24.dp).clip(CircleShape).background(if (value) c.primary else c.outline.copy(alpha = .48f)).padding(3.dp),
            contentAlignment = if (value) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.size(18.dp).clip(CircleShape).background(if (value) c.onPrimary else c.surface))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KashaSlider(value: Float, onValueChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, steps: Int, label: String,
    onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Slider(
        value = value, onValueChange = onValueChange, valueRange = range, steps = steps, onValueChangeFinished = onFinished,
        modifier = modifier.fillMaxWidth().height(40.dp).semantics { contentDescription = label },
        thumb = { Box(Modifier.size(14.dp).clip(CircleShape).background(c.primary)) },
        track = {
            Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                val y = size.height / 2
                val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                drawLine(c.outline.copy(alpha = .28f), Offset(0f, y), Offset(size.width, y), 3.dp.toPx(), StrokeCap.Round)
                drawLine(c.primary, Offset(0f, y), Offset(size.width * fraction, y), 3.dp.toPx(), StrokeCap.Round)
            }
        },
    )
}

@Composable
fun KashaPanel(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(modifier.clip(RoundedCornerShape(KashaUi.panelRadius)).background(c.surface)
        .border(1.dp, c.outline.copy(alpha = .16f), RoundedCornerShape(KashaUi.panelRadius)).padding(padding), content = content)
}

@Composable
fun KashaListCard(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val c = MaterialTheme.colorScheme
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(if (pressed) c.surfaceVariant.copy(alpha = .65f) else if (hovered) c.surfaceVariant.copy(alpha = .38f) else c.surface)
            .border(1.dp, c.outline.copy(alpha = if (hovered) .28f else .14f), RoundedCornerShape(18.dp))
            .hoverable(interactions).clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun KashaWaveform(peaks: List<Float>, modifier: Modifier = Modifier, progress: Float? = null) {
    val c = MaterialTheme.colorScheme
    Canvas(modifier) {
        val data = if (peaks.isEmpty()) List(80) { 0f } else peaks
        val step = size.width / data.size
        data.forEachIndexed { i, p ->
            val height = (p.coerceIn(0f, 1f) * size.height * .88f).coerceAtLeast(1.2.dp.toPx())
            val color = if (progress != null && i.toFloat() / data.size > progress) c.onSurface.copy(alpha = .2f) else c.onSurface.copy(alpha = .82f)
            drawLine(color, Offset((i + .5f) * step, (size.height - height) / 2), Offset((i + .5f) * step, (size.height + height) / 2),
                (step * .34f).coerceIn(.8.dp.toPx(), 2.2.dp.toPx()), StrokeCap.Round)
        }
    }
}

@Composable
fun KashaProcessingRing(modifier: Modifier = Modifier) {
    val rotation by rememberInfiniteTransition(label = "processing").animateFloat(0f, 360f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "rotation")
    val c = MaterialTheme.colorScheme
    Canvas(modifier) {
        drawCircle(c.outline.copy(alpha = .18f), size.minDimension * .4f, style = Stroke(2.dp.toPx()))
        drawArc(c.primary, rotation, 80f, false, Offset(size.width * .1f, size.height * .1f), Size(size.width * .8f, size.height * .8f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
fun KashaCaptureMark(modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Canvas(modifier) {
        val w = size.width; val h = size.height; val left = w * .08f; val top = h * .08f; val cardW = w * .84f; val cardH = h * .8f; val radius = w * .09f
        drawRoundRect(c.surface, Offset(left, top), Size(cardW, cardH), CornerRadius(radius, radius))
        drawRoundRect(c.outline.copy(alpha = .2f), Offset(left, top), Size(cardW, cardH), CornerRadius(radius, radius), style = Stroke(1.dp.toPx()))
        drawCircle(c.primary, radius = w * .035f, center = Offset(w * .2f, h * .21f))
        drawLine(c.onSurfaceVariant.copy(alpha = .45f), Offset(w * .28f, h * .21f), Offset(w * .57f, h * .21f), 2.dp.toPx(), StrokeCap.Round)
        val bars = listOf(.18f, .38f, .62f, .84f, .48f, .72f, .34f, .56f, .24f)
        val startX = w * .2f; val endX = w * .8f; val step = (endX - startX) / (bars.size - 1)
        bars.forEachIndexed { index, value ->
            val x = startX + step * index; val barH = h * .22f * value
            drawLine(c.onSurface, Offset(x, h * .46f - barH / 2), Offset(x, h * .46f + barH / 2), 4.dp.toPx(), StrokeCap.Round)
        }
        listOf(.64f, .76f, .56f).forEachIndexed { index, widthFraction ->
            val y = h * (.67f + index * .075f)
            drawLine(c.onSurfaceVariant.copy(alpha = .34f), Offset(w * .2f, y), Offset(w * widthFraction, y), 2.dp.toPx(), StrokeCap.Round)
        }
    }
}
