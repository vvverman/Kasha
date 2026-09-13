package brain.studio

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
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
    val colors = if (dark) darkColorScheme(
        primary = kasha.accent, onPrimary = kasha.onAccent,
        primaryContainer = kasha.accentContainer, onPrimaryContainer = kasha.textPrimary,
        background = kasha.canvas, onBackground = kasha.textPrimary,
        surface = kasha.surface, onSurface = kasha.textPrimary,
        surfaceVariant = kasha.surfaceHigh, onSurfaceVariant = kasha.textSecondary,
        outline = kasha.controlOutline, outlineVariant = kasha.hairline,
        error = kasha.error, onError = kasha.onAccent,
    ) else lightColorScheme(
        primary = kasha.accent, onPrimary = kasha.onAccent,
        primaryContainer = kasha.accentContainer, onPrimaryContainer = kasha.textPrimary,
        background = kasha.canvas, onBackground = kasha.textPrimary,
        surface = kasha.surface, onSurface = kasha.textPrimary,
        surfaceVariant = kasha.surfaceHighest, onSurfaceVariant = kasha.textSecondary,
        outline = kasha.controlOutline, outlineVariant = kasha.hairline,
        error = kasha.error, onError = kasha.onAccent,
    )

    fun text(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
        fontFamily = geologica, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight,
    )
    fun display(size: Int, line: Int) = TextStyle(
        fontFamily = geologica, fontSize = size.sp, lineHeight = line.sp, fontWeight = FontWeight.SemiBold,
    )

    val typography = Typography(
        displayLarge = display(46, 48), displayMedium = text(38, 42, FontWeight(550)), displaySmall = text(30, 35, FontWeight(580)),
        headlineLarge = text(30, 35, FontWeight(580)), headlineMedium = text(25, 30, FontWeight(560)), headlineSmall = text(21, 27, FontWeight(540)),
        titleLarge = text(18, 24, FontWeight(520)), titleMedium = text(18, 24, FontWeight(520)), titleSmall = text(13, 18, FontWeight.Medium),
        bodyLarge = text(17, 25), bodyMedium = text(16, 23), bodySmall = text(14, 20),
        labelLarge = text(13, 18, FontWeight.Medium), labelMedium = text(12, 16, FontWeight.Medium), labelSmall = text(11, 15, FontWeight.Medium),
    )
    CompositionLocalProvider(LocalKashaColors provides kasha) {
        MaterialTheme(colorScheme = colors, typography = typography, content = content)
    }
}

@Composable
fun IconAction(label: String, glyph: Glyph, onClick: () -> Unit, filled: Boolean = false, modifier: Modifier = Modifier) =
    KashaIconButton(label, glyph, onClick, modifier, filled)

@Composable
fun Action(label: String, onClick: () -> Unit, primary: Boolean = false, glyph: Glyph? = null, modifier: Modifier = Modifier, enabled: Boolean = true) =
    KashaButton(label, onClick, modifier, primary, glyph, enabled)

@Composable
fun QuietAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) = KashaQuietButton(label, onClick, modifier)

@Composable
fun Editor(value: String, onValue: (String) -> Unit, hint: String, modifier: Modifier = Modifier, title: Boolean = false, readOnly: Boolean = false) =
    KashaField(value, onValue, hint, modifier, title = title, multiline = !title, readOnly = readOnly, minHeight = if (title) 58.dp else 100.dp)

@Composable fun Wave(peaks: List<Float>, modifier: Modifier = Modifier, progress: Float? = null) = KashaWaveform(peaks, modifier, progress)
@Composable fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) = KashaSwitchRow(label, value, onChange)
@Composable fun ProcessingRing(modifier: Modifier = Modifier) = KashaProcessingRing(modifier)

fun clock(seconds: Long): String = "${seconds.coerceAtLeast(0) / 60}:${(seconds.coerceAtLeast(0) % 60).toString().padStart(2, '0')}"
