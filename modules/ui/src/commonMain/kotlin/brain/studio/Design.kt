package brain.studio

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import brain.studio.resources.Res
import brain.studio.resources.geologica_variable
import org.jetbrains.compose.resources.Font

@Immutable
data class KashaColors(
    val canvas: Color,
    val canvasSubtle: Color,
    val surfaceLow: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val surfaceHighest: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textDisabled: Color,
    val accentFill: Color,
    val accentHoverFill: Color,
    val accentPressedFill: Color,
    val accentContent: Color,
    val onAccentFill: Color,
    val borderHairline: Color,
    val borderControl: Color,
    val focusRing: Color,
    val successContent: Color,
    val successContainer: Color,
    val warningContent: Color,
    val warningContainer: Color,
    val errorContent: Color,
    val errorContainer: Color,
    val secondaryFill: Color,
    val secondaryHoverFill: Color,
    val secondaryPressedFill: Color,
    val secondaryContent: Color,
    val quietContent: Color,
    val disabledFill: Color,
    val disabledContent: Color,
    val fieldFill: Color,
    val fieldContent: Color,
    val fieldPlaceholder: Color,
    val fieldOutline: Color,
    val overlayHover: Color,
    val overlayPressed: Color,
)

private val DarkKashaColors = KashaColors(
    canvas = Color(0xFF191715),
    canvasSubtle = Color(0xFF1D1A18),
    surfaceLow = Color(0xFF211E1B),
    surface = Color(0xFF27231F),
    surfaceHigh = Color(0xFF302A25),
    surfaceHighest = Color(0xFF39312B),
    textPrimary = Color(0xFFF5F0E8),
    textSecondary = Color(0xFFC3B9AE),
    textTertiary = Color(0xFFACA095),
    textDisabled = Color(0x4DF5F0E8),
    accentFill = Color(0xFFE8C79F),
    accentHoverFill = Color(0xFFEFCEAA),
    accentPressedFill = Color(0xFFDFC09A),
    accentContent = Color(0xFFE8C79F),
    onAccentFill = Color(0xFF211E1B),
    borderHairline = Color(0x14FFF5EB),
    borderControl = Color(0xFF91877D),
    focusRing = Color(0xFFE8C79F),
    successContent = Color(0xFF8EA184),
    successContainer = Color(0xFF283027),
    warningContent = Color(0xFFBA9665),
    warningContainer = Color(0xFF342B20),
    errorContent = Color(0xFFD08E86),
    errorContainer = Color(0xFF342321),
    secondaryFill = Color(0xFF302A25),
    secondaryHoverFill = Color(0xFF39312B),
    secondaryPressedFill = Color(0xFF39312B),
    secondaryContent = Color(0xFFF5F0E8),
    quietContent = Color(0xFFC3B9AE),
    disabledFill = Color(0xFF302A25),
    disabledContent = Color(0x4DF5F0E8),
    fieldFill = Color(0xFF27231F),
    fieldContent = Color(0xFFF5F0E8),
    fieldPlaceholder = Color(0xFFACA095),
    fieldOutline = Color(0xFF91877D),
    overlayHover = Color(0x0AF5F0E8),
    overlayPressed = Color(0x12F5F0E8),
)

private val LightKashaColors = KashaColors(
    canvas = Color(0xFFF7F5EE),
    canvasSubtle = Color(0xFFF2EFE6),
    surfaceLow = Color(0xFFF2EFE6),
    surface = Color(0xFFFEFCF6),
    surfaceHigh = Color(0xFFFEFCF6),
    surfaceHighest = Color(0xFFEEEAE0),
    textPrimary = Color(0xFF211E1B),
    textSecondary = Color(0xFF6C645D),
    textTertiary = Color(0xFF70665D),
    textDisabled = Color(0x4D211E1B),
    accentFill = Color(0xFF886445),
    accentHoverFill = Color(0xFF805C3E),
    accentPressedFill = Color(0xFF785538),
    accentContent = Color(0xFF7A583B),
    onAccentFill = Color(0xFFFEFCF6),
    borderHairline = Color(0x141E1914),
    borderControl = Color(0xFF81776D),
    focusRing = Color(0xFF7A583B),
    successContent = Color(0xFF4E6547),
    successContainer = Color(0xFFE7EEE2),
    warningContent = Color(0xFF745629),
    warningContainer = Color(0xFFF5EBD8),
    errorContent = Color(0xFF974C45),
    errorContainer = Color(0xFFF6E6E1),
    secondaryFill = Color(0xFFFEFCF6),
    secondaryHoverFill = Color(0xFFF2EFE6),
    secondaryPressedFill = Color(0xFFEEEAE0),
    secondaryContent = Color(0xFF211E1B),
    quietContent = Color(0xFF6C645D),
    disabledFill = Color(0xFFEEEAE0),
    disabledContent = Color(0x4D211E1B),
    fieldFill = Color(0xFFFEFCF6),
    fieldContent = Color(0xFF211E1B),
    fieldPlaceholder = Color(0xFF70665D),
    fieldOutline = Color(0xFF81776D),
    overlayHover = Color(0x08211E1B),
    overlayPressed = Color(0x0F211E1B),
)

private val LocalKashaColors = staticCompositionLocalOf { LightKashaColors }

object KashaTheme {
    val colors: KashaColors
        @Composable get() = LocalKashaColors.current
}

@Composable
fun StudioTheme(theme: String, content: @Composable () -> Unit) {
    val dark = theme == "dark" || (theme == "system" && isSystemInDarkTheme())
    val kashaColors = if (dark) DarkKashaColors else LightKashaColors
    val family = FontFamily(
        Font(Res.font.geologica_variable, FontWeight.Normal),
        Font(Res.font.geologica_variable, FontWeight.Medium),
        Font(Res.font.geologica_variable, FontWeight.SemiBold),
    )

    fun text(size: Int, line: Int, weight: Int = 400) = TextStyle(
        fontFamily = family,
        fontSize = size.sp,
        lineHeight = line.sp,
        fontWeight = FontWeight(weight),
    )

    val typography = Typography(
        displayLarge = text(46, 48, 600),
        displayMedium = text(38, 42, 550),
        displaySmall = text(30, 35, 580),
        headlineLarge = text(30, 35, 580),
        headlineMedium = text(25, 30, 560),
        headlineSmall = text(21, 27, 540),
        titleLarge = text(18, 24, 520),
        titleMedium = text(18, 24, 520),
        titleSmall = text(13, 18, 500),
        bodyLarge = text(17, 25),
        bodyMedium = text(16, 23),
        bodySmall = text(14, 20),
        labelLarge = text(16, 22, 520),
        labelMedium = text(13, 18, 500),
        labelSmall = text(11, 15, 500),
    )

    val colors = if (dark) darkColorScheme(
        primary = kashaColors.accentFill,
        onPrimary = kashaColors.onAccentFill,
        primaryContainer = kashaColors.surfaceHighest,
        onPrimaryContainer = kashaColors.textPrimary,
        background = kashaColors.canvas,
        onBackground = kashaColors.textPrimary,
        surface = kashaColors.surface,
        onSurface = kashaColors.textPrimary,
        surfaceVariant = kashaColors.surfaceHigh,
        onSurfaceVariant = kashaColors.textSecondary,
        outline = kashaColors.borderControl,
        outlineVariant = kashaColors.borderHairline,
        error = kashaColors.errorContent,
        onError = kashaColors.onAccentFill,
        errorContainer = kashaColors.errorContainer,
        onErrorContainer = kashaColors.textPrimary,
    ) else lightColorScheme(
        primary = kashaColors.accentFill,
        onPrimary = kashaColors.onAccentFill,
        primaryContainer = kashaColors.surfaceHighest,
        onPrimaryContainer = kashaColors.textPrimary,
        background = kashaColors.canvas,
        onBackground = kashaColors.textPrimary,
        surface = kashaColors.surface,
        onSurface = kashaColors.textPrimary,
        surfaceVariant = kashaColors.surfaceHighest,
        onSurfaceVariant = kashaColors.textSecondary,
        outline = kashaColors.borderControl,
        outlineVariant = kashaColors.borderHairline,
        error = kashaColors.errorContent,
        onError = kashaColors.onAccentFill,
        errorContainer = kashaColors.errorContainer,
        onErrorContainer = kashaColors.textPrimary,
    )

    val shapes = Shapes(
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(20.dp),
    )

    CompositionLocalProvider(LocalKashaColors provides kashaColors) {
        MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
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
    KashaField(value, onValue, hint, modifier, title = title, multiline = !title, readOnly = readOnly, minHeight = if (title) 56.dp else 100.dp)

@Composable fun Wave(peaks: List<Float>, modifier: Modifier = Modifier, progress: Float? = null) = KashaWaveform(peaks, modifier, progress)
@Composable fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) = KashaSwitchRow(label, value, onChange)
@Composable fun ProcessingRing(modifier: Modifier = Modifier) = KashaProcessingRing(modifier)

fun clock(seconds: Long): String = "${seconds.coerceAtLeast(0) / 60}:${(seconds.coerceAtLeast(0) % 60).toString().padStart(2, '0')}"
