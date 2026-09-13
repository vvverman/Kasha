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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
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
    val controlRadius = KashaMetrics.radiusPrimaryButton
    val panelRadius = KashaMetrics.radiusFloating
    val fieldRadius = KashaMetrics.radiusField
    val controlHeight = KashaMetrics.buttonHeight
    val iconSize = KashaMetrics.touchTargetPreferred
}

@Composable
private fun controlColors(
    primary: Boolean,
    hovered: Boolean,
    pressed: Boolean,
    enabled: Boolean,
): Pair<Color, Color> {
    val c = KashaTheme.colors
    if (!enabled) return c.actionDisabledFill to c.actionDisabledContent
    return if (primary) {
        when {
            pressed -> c.accentPressed
            hovered -> c.accentHover
            else -> c.accent
        } to c.onAccent
    } else {
        when {
            pressed -> c.actionSecondaryPressedFill
            hovered -> c.actionSecondaryHoverFill
            else -> c.actionSecondaryFill
        } to c.actionSecondaryContent
    }
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
    val c = KashaTheme.colors
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    val focused by interactions.collectIsFocusedAsState()
    val (bg, fg) = controlColors(primary, hovered, pressed, enabled)
    val shape = RoundedCornerShape(
        if (primary) KashaMetrics.radiusPrimaryButton else KashaMetrics.radiusSecondaryButton,
    )
    val border = when {
        focused -> c.focusRing
        !primary -> c.hairline
        else -> Color.Transparent
    }
    val borderWidth = if (focused) KashaMetrics.focusRingWidth else 1.dp

    Row(
        modifier
            .heightIn(min = KashaUi.controlHeight)
            .clip(shape)
            .background(bg)
            .border(borderWidth, border, shape)
            .hoverable(interactions, enabled)
            .focusable(enabled, interactions)
            .clickable(
                interactionSource = interactions,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(
                horizontal = KashaMetrics.buttonHorizontalPadding,
                vertical = 14.dp,
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            KashaIcon(
                glyph,
                Modifier.size(KashaMetrics.iconGlyph),
                fg,
                animated = hovered || pressed || focused,
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = fg,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
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
    val c = KashaTheme.colors
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    val focused by interactions.collectIsFocusedAsState()
    val (filledBg, filledFg) = controlColors(true, hovered, pressed, enabled)

    val background = if (filled) {
        filledBg
    } else {
        when {
            pressed -> c.overlayPressed
            hovered -> c.overlayHover
            else -> Color.Transparent
        }
    }
    val foreground = when {
        !enabled -> c.iconDisabled
        filled -> filledFg
        else -> c.iconPrimary
    }

    Box(
        modifier
            .size(KashaUi.iconSize)
            .clip(CircleShape)
            .background(background)
            .border(
                if (focused) KashaMetrics.focusRingWidth else 1.dp,
                if (focused) c.focusRing else Color.Transparent,
                CircleShape,
            )
            .hoverable(interactions, enabled)
            .focusable(enabled, interactions)
            .clickable(
                interactionSource = interactions,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        KashaIcon(
            glyph,
            Modifier.size(KashaMetrics.iconGlyph),
            foreground,
            animated = hovered || pressed || focused,
        )
    }
}

@Composable
fun KashaQuietButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = KashaTheme.colors
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    val background = when {
        pressed -> c.overlayPressed
        hovered -> c.overlayHover
        else -> Color.Transparent
    }

    Text(
        label,
        modifier
            .heightIn(min = KashaMetrics.quietButtonHeight)
            .clip(RoundedCornerShape(KashaMetrics.radiusSmall))
            .background(background)
            .hoverable(interactions, enabled)
            .clickable(
                interactionSource = interactions,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = 12.dp, horizontal = 10.dp),
        style = MaterialTheme.typography.bodySmall,
        color = if (enabled) c.actionQuietContent else c.actionDisabledContent,
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
    minHeight: Dp = if (multiline) 180.dp else KashaMetrics.fieldHeight,
    onEditRequest: (() -> Unit)? = null,
) {
    val c = KashaTheme.colors
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(KashaUi.fieldRadius)
    val borderColor = when {
        focused -> c.focusRing
        hovered -> c.fieldOutline
        else -> c.hairline
    }
    val borderWidth = if (focused) KashaMetrics.focusRingWidth else 1.dp

    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = c.textSecondary,
        )
        Spacer(Modifier.height(7.dp))

        val boxModifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(shape)
            .background(c.fieldFill)
            .border(borderWidth, borderColor, shape)
            .hoverable(interactions)
            .then(
                if (readOnly && onEditRequest != null) {
                    Modifier.clickable(
                        interactionSource = interactions,
                        indication = null,
                        role = Role.Button,
                        onClick = onEditRequest,
                    )
                } else {
                    Modifier
                },
            )
            .padding(
                horizontal = KashaMetrics.md,
                vertical = if (multiline) 15.dp else 13.dp,
            )

        Row(
            boxModifier,
            verticalAlignment = if (multiline) Alignment.Top else Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                val style = if (title) {
                    MaterialTheme.typography.headlineSmall
                } else {
                    MaterialTheme.typography.bodyLarge
                }
                if (readOnly) {
                    Text(
                        value.ifEmpty { label },
                        style = style,
                        color = if (value.isEmpty()) c.fieldPlaceholder else c.fieldContent,
                    )
                } else {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focused = it.isFocused }
                            .semantics { contentDescription = label },
                        textStyle = style.copy(color = c.fieldContent),
                        cursorBrush = SolidColor(c.accentContent),
                        interactionSource = interactions,
                        singleLine = !multiline,
                        decorationBox = { inner ->
                            Box {
                                if (value.isEmpty()) {
                                    Text(
                                        label,
                                        style = style,
                                        color = c.fieldPlaceholder,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            KashaIcon(
                Glyph.EDIT,
                Modifier.size(18.dp),
                if (focused) c.iconPrimary else c.iconSecondary,
                animated = focused || hovered,
            )
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
        KashaField(
            title,
            onTitleChange,
            titleLabel,
            Modifier.fillMaxWidth(),
            title = true,
            readOnly = readOnly,
            onEditRequest = onEditRequest,
        )
        Spacer(Modifier.height(18.dp))
        KashaField(
            body,
            onBodyChange,
            bodyLabel,
            Modifier.fillMaxWidth(),
            multiline = true,
            readOnly = readOnly,
            minHeight = 210.dp,
            onEditRequest = onEditRequest,
        )
    }
}

@Composable
fun KashaSwitchRow(
    label: String,
    value: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = KashaTheme.colors
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()

    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = KashaMetrics.rowHeight)
            .clip(RoundedCornerShape(KashaMetrics.radiusField))
            .background(if (hovered) c.overlayHover else Color.Transparent)
            .hoverable(interactions, enabled)
            .toggleable(
                value = value,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onChange,
            )
            .semantics { contentDescription = label }
            .padding(horizontal = KashaMetrics.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            Modifier.weight(1f).padding(end = KashaMetrics.lg),
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) c.textPrimary else c.textDisabled,
        )
        Box(
            Modifier
                .size(42.dp, 24.dp)
                .clip(CircleShape)
                .background(if (value) c.accent else c.surfaceHighest)
                .padding(3.dp),
            contentAlignment = if (value) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(if (value) c.onAccent else c.textPrimary),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KashaSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    label: String,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = KashaTheme.colors
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = range,
        steps = steps,
        onValueChangeFinished = onFinished,
        modifier = modifier
            .fillMaxWidth()
            .height(KashaMetrics.touchTargetPreferred)
            .semantics { contentDescription = label },
        thumb = {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(c.accent),
            )
        },
        track = {
            Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                val y = size.height / 2
                val fraction = (
                    (value - range.start) /
                        (range.endInclusive - range.start)
                    ).coerceIn(0f, 1f)
                drawLine(
                    c.controlOutline,
                    Offset(0f, y),
                    Offset(size.width, y),
                    3.dp.toPx(),
                    StrokeCap.Round,
                )
                drawLine(
                    c.accent,
                    Offset(0f, y),
                    Offset(size.width * fraction, y),
                    3.dp.toPx(),
                    StrokeCap.Round,
                )
            }
        },
    )
}

@Composable
fun KashaPanel(
    modifier: Modifier = Modifier,
    padding: Dp = KashaMetrics.md,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = KashaTheme.colors
    val shape = RoundedCornerShape(KashaUi.panelRadius)
    Column(
        modifier
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.hairline, shape)
            .padding(padding),
        content = content,
    )
}

@Composable
fun KashaListCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val c = KashaTheme.colors
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    val shape = RoundedCornerShape(KashaMetrics.radiusSurface)
    val background = when {
        pressed -> c.surfaceHighest
        hovered -> c.surface
        else -> c.surfaceLow
    }

    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .border(1.dp, c.hairline, shape)
            .hoverable(interactions)
            .clickable(
                interactionSource = interactions,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(KashaMetrics.md),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun KashaWaveform(
    peaks: List<Float>,
    modifier: Modifier = Modifier,
    progress: Float? = null,
) {
    val c = KashaTheme.colors
    Canvas(modifier) {
        val data = if (peaks.isEmpty()) List(48) { 0f } else peaks
        val step = size.width / data.size.coerceAtLeast(1)
        val barWidth = 2.5.dp.toPx().coerceAtMost(step * .55f)
        val minHeight = 4.dp.toPx()
        data.forEachIndexed { index, peak ->
            val height = (peak.coerceIn(0f, 1f) * size.height)
                .coerceIn(minHeight, size.height)
            val fraction = index.toFloat() / data.size.coerceAtLeast(1)
            val color = when {
                progress == null -> c.waveform
                fraction <= progress -> c.waveformPlayed
                else -> c.waveformUnplayed
            }
            val x = (index + .5f) * step
            drawLine(
                color,
                Offset(x, (size.height - height) / 2),
                Offset(x, (size.height + height) / 2),
                barWidth,
                StrokeCap.Round,
            )
        }
    }
}

@Composable
fun KashaProcessingRing(modifier: Modifier = Modifier) {
    val rotation by rememberInfiniteTransition(label = "processing").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            tween(6000, easing = LinearEasing),
        ),
        label = "rotation",
    )
    val c = KashaTheme.colors

    Canvas(modifier) {
        drawCircle(
            c.hairline,
            size.minDimension * .4f,
            style = Stroke(2.dp.toPx()),
        )
        drawArc(
            c.accent,
            rotation,
            80f,
            false,
            Offset(size.width * .1f, size.height * .1f),
            Size(size.width * .8f, size.height * .8f),
            style = Stroke(2.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

/** Главный процедурный объект Kasha. */
@Composable
fun KashaCaptureOrb(
    modifier: Modifier = Modifier,
    recording: Boolean = false,
) {
    val c = KashaTheme.colors
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension * .43f

            drawCircle(
                color = c.orbBloom,
                radius = radius * 1.18f,
                center = center,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = if (recording) {
                        listOf(c.orbHighlight, c.orbBody, c.orbRecordingCore)
                    } else {
                        listOf(c.orbHighlight, c.orbCenter, c.orbBody, c.orbInnerMinimum)
                    },
                    center = Offset(size.width * .42f, size.height * .37f),
                    radius = radius * 1.45f,
                ),
                radius = radius,
                center = center,
            )
            drawCircle(
                color = c.waveformEdgeDecorative,
                radius = radius,
                center = center,
                style = Stroke(1.dp.toPx()),
            )
        }
        if (!recording) {
            KashaIcon(
                Glyph.RECORD,
                Modifier.size(34.dp),
                c.orbInk,
                animated = false,
            )
        }
    }
}

/** Совместимость старых call sites во время поэтапной миграции экранов. */
@Composable
fun KashaCaptureMark(modifier: Modifier = Modifier) {
    KashaCaptureOrb(modifier)
}
