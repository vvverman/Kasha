package brain.studio

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import brain.studio.resources.*
import org.jetbrains.compose.resources.Font

object KashaTheme {
    val colors: KashaColors
        @Composable get() = LocalKashaColors.current

    val isDark: Boolean
        @Composable get() = LocalKashaIsDark.current
}

@Composable
fun StudioTheme(theme: String, content: @Composable () -> Unit) {
    val dark = theme == "dark" || (theme == "system" && isSystemInDarkTheme())
    val geologica = FontFamily(
        Font(Res.font.geologica_variable, FontWeight.Normal),
        Font(Res.font.geologica_variable, FontWeight.Medium),
        Font(Res.font.geologica_variable, FontWeight.SemiBold),
    )
    val kasha = if (dark) KashaDarkColors else KashaLightColors
    val colors = if (dark) {
        darkColorScheme(
            primary = kasha.accent,
            onPrimary = kasha.onAccent,
            primaryContainer = kasha.accentContainer,
            onPrimaryContainer = kasha.textPrimary,
            background = kasha.canvas,
            onBackground = kasha.textPrimary,
            surface = kasha.surface,
            onSurface = kasha.textPrimary,
            surfaceVariant = kasha.surfaceHigh,
            onSurfaceVariant = kasha.textSecondary,
            outline = kasha.controlOutline,
            outlineVariant = kasha.hairline,
            error = kasha.error,
            onError = kasha.actionOnDestructiveFill,
            errorContainer = kasha.errorContainer,
            onErrorContainer = kasha.textPrimary,
        )
    } else {
        lightColorScheme(
            primary = kasha.accent,
            onPrimary = kasha.onAccent,
            primaryContainer = kasha.accentContainer,
            onPrimaryContainer = kasha.textPrimary,
            background = kasha.canvas,
            onBackground = kasha.textPrimary,
            surface = kasha.surface,
            onSurface = kasha.textPrimary,
            surfaceVariant = kasha.surfaceHighest,
            onSurfaceVariant = kasha.textSecondary,
            outline = kasha.controlOutline,
            outlineVariant = kasha.hairline,
            error = kasha.error,
            onError = kasha.actionOnDestructiveFill,
            errorContainer = kasha.errorContainer,
            onErrorContainer = kasha.textPrimary,
        )
    }

    fun text(
        size: Int,
        line: Int,
        weight: FontWeight = FontWeight.Normal,
        letterSpacingSp: Float = 0f,
    ) = TextStyle(
        fontFamily = geologica,
        fontSize = size.sp,
        lineHeight = line.sp,
        fontWeight = weight,
        letterSpacing = letterSpacingSp.sp,
    )

    val typography = Typography(
        displayLarge = text(46, 48, FontWeight.SemiBold, -.92f),
        displayMedium = text(38, 42, FontWeight(550), -.57f),
        displaySmall = text(30, 35, FontWeight(580), -.30f),
        headlineLarge = text(30, 35, FontWeight(580), -.30f),
        headlineMedium = text(25, 30, FontWeight(560), -.125f),
        headlineSmall = text(21, 27, FontWeight(540)),
        titleLarge = text(18, 24, FontWeight(520)),
        titleMedium = text(18, 24, FontWeight(520)),
        titleSmall = text(13, 18, FontWeight.Medium, .065f),
        bodyLarge = text(17, 25),
        bodyMedium = text(16, 23),
        bodySmall = text(14, 20),
        labelLarge = text(16, 22, FontWeight(520)),
        labelMedium = text(13, 18, FontWeight.Medium, .065f),
        labelSmall = text(12, 16, FontWeight.Medium),
    )

    CompositionLocalProvider(
        LocalKashaColors provides kasha,
        LocalKashaIsDark provides dark,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = typography,
            content = content,
        )
    }
}

@Composable
fun IconAction(
    label: String,
    glyph: Glyph,
    onClick: () -> Unit,
    filled: Boolean = false,
    modifier: Modifier = Modifier,
) = KashaIconButton(label, glyph, onClick, modifier, filled)

@Composable
fun Action(
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    glyph: Glyph? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = KashaButton(label, onClick, modifier, primary, glyph, enabled)

@Composable
fun QuietAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) = KashaQuietButton(label, onClick, modifier)

@Composable
fun Editor(
    value: String,
    onValue: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    title: Boolean = false,
    readOnly: Boolean = false,
) = KashaField(
    value,
    onValue,
    hint,
    modifier,
    title = title,
    multiline = !title,
    readOnly = readOnly,
    minHeight = if (title) KashaMetrics.fieldHeight else 100.dp,
)

@Composable
fun Wave(
    peaks: List<Float>,
    modifier: Modifier = Modifier,
    progress: Float? = null,
) = KashaWaveform(peaks, modifier, progress)

@Composable
fun ToggleRow(
    label: String,
    value: Boolean,
    onChange: (Boolean) -> Unit,
) = KashaSwitchRow(label, value, onChange)

@Composable
fun ProcessingRing(modifier: Modifier = Modifier) = KashaProcessingRing(modifier)

fun clock(seconds: Long): String =
    "${seconds.coerceAtLeast(0) / 60}:${(seconds.coerceAtLeast(0) % 60).toString().padStart(2, '0')}"
