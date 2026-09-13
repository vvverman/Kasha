package brain.studio

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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
    val iconInactive: Color,
    val accent: Color,
    val accentHover: Color,
    val accentPressed: Color,
    val accentContent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val hairline: Color,
    val controlOutline: Color,
    val focusRing: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val navigation: Color,
    val navigationActiveSpot: Color,
    val waveform: Color,
    val waveformCenter: Color,
    val waveformUnplayed: Color,
    val scrim: Color,
)

internal val KashaDarkColors = KashaColors(
    canvas = Color(0xFF191715), canvasSubtle = Color(0xFF1D1A18),
    surfaceLow = Color(0xFF211E1B), surface = Color(0xFF27231F),
    surfaceHigh = Color(0xFF302A25), surfaceHighest = Color(0xFF39312B),
    textPrimary = Color(0xFFF5F0E8), textSecondary = Color(0xFFC3B9AE),
    textTertiary = Color(0xFFACA095), iconInactive = Color(0xFF91877D),
    accent = Color(0xFFE8C79F), accentHover = Color(0xFFEFCEAA),
    accentPressed = Color(0xFFDFC09A), accentContent = Color(0xFFE8C79F),
    onAccent = Color(0xFF211E1B), accentContainer = Color(0x1FE8C79F),
    hairline = Color(0x14FFF5EB), controlOutline = Color(0xFF91877D),
    focusRing = Color(0xFFE8C79F), success = Color(0xFF8EA184),
    warning = Color(0xFFBA9665), error = Color(0xFFD08E86),
    navigation = Color(0xFF302A25), navigationActiveSpot = Color(0x14E8C79F),
    waveform = Color(0xFFE8C79F), waveformCenter = Color(0xFFF5E0C7),
    waveformUnplayed = Color(0xFF91877D), scrim = Color(0x8F0A0807),
)

internal val KashaLightColors = KashaColors(
    canvas = Color(0xFFF7F5EE), canvasSubtle = Color(0xFFF2EFE6),
    surfaceLow = Color(0xFFF2EFE6), surface = Color(0xFFFEFCF6),
    surfaceHigh = Color(0xFFFEFCF6), surfaceHighest = Color(0xFFEEEAE0),
    textPrimary = Color(0xFF211E1B), textSecondary = Color(0xFF6C645D),
    textTertiary = Color(0xFF70665D), iconInactive = Color(0xFF81776D),
    accent = Color(0xFF886445), accentHover = Color(0xFF805C3E),
    accentPressed = Color(0xFF785538), accentContent = Color(0xFF7A583B),
    onAccent = Color(0xFFFEFCF6), accentContainer = Color(0x1A886445),
    hairline = Color(0x141E1914), controlOutline = Color(0xFF81776D),
    focusRing = Color(0xFF7A583B), success = Color(0xFF4E6547),
    warning = Color(0xFF745629), error = Color(0xFF974C45),
    navigation = Color(0xFFFEFCF6), navigationActiveSpot = Color(0x14886445),
    waveform = Color(0xFF7A583B), waveformCenter = Color(0xFF7A583B),
    waveformUnplayed = Color(0xFF81776D), scrim = Color(0x52211A15),
)

internal val LocalKashaColors = staticCompositionLocalOf { KashaDarkColors }

object KashaMetrics {
    val touchTargetMinimum = 44.dp
    val touchTargetPreferred = 48.dp
    val buttonHeight = 56.dp
    val quietButtonHeight = 48.dp
    val fieldHeight = 56.dp
    val rowHeight = 64.dp
    val iconGlyph = 22.dp
    val radiusField = 16.dp
    val radiusSecondaryButton = 18.dp
    val radiusPrimaryButton = 20.dp
    val radiusSurface = 20.dp
    val radiusFloating = 24.dp
    val radiusNavigation = 28.dp
    val pageMarginNarrow = 16.dp
    val pageMarginCompact = 20.dp
    val pageMarginMedium = 24.dp
    val pageMarginExpanded = 32.dp
    val readingColumnMax = 640.dp
    val contentColumnMax = 720.dp
    val navigationMax = 560.dp
}
