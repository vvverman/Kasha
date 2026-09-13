package brain.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Один текстовый документ, без отдельного поля title. Первая непустая строка
 * автоматически становится названием заметки в списках и сортировке.
 */
@Composable
fun KashaNoteText(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    onEditRequest: (() -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(KashaUi.fieldRadius)
    val border = when {
        focused -> c.primary.copy(alpha = .72f)
        hovered -> c.outline.copy(alpha = .52f)
        else -> c.outline.copy(alpha = .20f)
    }
    val boxModifier = modifier
        .fillMaxWidth()
        .heightIn(min = 260.dp)
        .clip(shape)
        .background(if (hovered && !focused) c.surfaceVariant.copy(alpha = .24f) else c.surface)
        .border(1.dp, border, shape)
        .hoverable(interactions)
        .then(
            if (readOnly && onEditRequest != null) Modifier.clickable(
                interactionSource = interactions,
                indication = null,
                role = Role.Button,
                onClick = onEditRequest,
            ) else Modifier,
        )
        .padding(18.dp)

    Row(boxModifier, verticalAlignment = Alignment.Top) {
        Box(Modifier.weight(1f)) {
            if (readOnly) {
                Text(
                    value.ifBlank { placeholder },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (value.isBlank()) c.onSurfaceVariant.copy(alpha = .5f) else c.onSurface,
                )
            } else {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
                        .semantics { contentDescription = placeholder },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onSurface),
                    cursorBrush = SolidColor(c.onSurface),
                    interactionSource = interactions,
                    decorationBox = { inner ->
                        Box {
                            if (value.isBlank()) Text(
                                placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = c.onSurfaceVariant.copy(alpha = .5f),
                            )
                            inner()
                        }
                    },
                )
            }
        }
        if (readOnly && onEditRequest != null) {
            Spacer(Modifier.width(12.dp))
            KashaIcon(Glyph.EDIT, Modifier.size(18.dp), c.onSurfaceVariant, animated = hovered)
        }
    }
}
