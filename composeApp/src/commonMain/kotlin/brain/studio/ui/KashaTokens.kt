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
    val textDisabled: Color,
    val textPlaceholder: Color,
    val iconPrimary: Color,
    val iconSecondary: Color,
    val iconInactive: Color,
    val iconDisabled: Color,
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
    val successContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val error: Color,
    val errorContainer: Color,
    val actionSecondaryFill: Color,
    val actionSecondaryHoverFill: Color,
    val actionSecondaryPressedFill: Color,
    val actionSecondaryContent: Color,
    val actionQuietContent: Color,
    val actionDisabledFill: Color,
    val actionDisabledContent: Color,
    val actionDestructiveFill: Color,
    val actionOnDestructiveFill: Color,
    val navigation: Color,
    val navigationActiveContent: Color,
    val navigationInactiveContent: Color,
    val navigationActiveSpot: Color,
    val fieldFill: Color,
    val fieldContent: Color,
    val fieldPlaceholder: Color,
    val fieldOutline: Color,
    val waveform: Color,
    val waveformCenter: Color,
    val waveformEdgeDecorative: Color,
    val waveformPlayed: Color,
    val waveformUnplayed: Color,
    val waveformPlayhead: Color,
    val orbInk: Color,
    val orbCenter: Color,
    val orbBody: Color,
    val orbInnerMinimum: Color,
    val orbHighlight: Color,
    val orbBloom: Color,
    val orbRecordingCore: Color,
    val orbRecordingWaveform: Color,
    val backgroundIllumination: Color,
    val backgroundShadowField: Color,
    val backgroundGrainLight: Color,
    val backgroundGrainDark: Color,
    val backgroundBrandShape: Color,
    val overlayScrim: Color,
    val overlayHover: Color,
    val overlayPressed: Color,
    val shadowFloating: Color,
    val shadowLift: Color,
)

internal val KashaDarkColors = KashaColors(
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
    textPlaceholder = Color(0xFFACA095),
    iconPrimary = Color(0xFFF5F0E8),
    iconSecondary = Color(0xFFC3B9AE),
    iconInactive = Color(0xFF91877D),
    iconDisabled = Color(0x4DF5F0E8),
    accent = Color(0xFFE8C79F),
    accentHover = Color(0xFFEFCEAA),
    accentPressed = Color(0xFFDFC09A),
    accentContent = Color(0xFFE8C79F),
    onAccent = Color(0xFF211E1B),
    accentContainer = Color(0x1FE8C79F),
    hairline = Color(0x14FFF5EB),
    controlOutline = Color(0xFF91877D),
    focusRing = Color(0xFFE8C79F),
    success = Color(0xFF8EA184),
    successContainer = Color(0xFF283027),
    warning = Color(0xFFBA9665),
    warningContainer = Color(0xFF342B20),
    error = Color(0xFFD08E86),
    errorContainer = Color(0xFF342321),
    actionSecondaryFill = Color(0xFF302A25),
    actionSecondaryHoverFill = Color(0xFF39312B),
    actionSecondaryPressedFill = Color(0xFF39312B),
    actionSecondaryContent = Color(0xFFF5F0E8),
    actionQuietContent = Color(0xFFC3B9AE),
    actionDisabledFill = Color(0xFF302A25),
    actionDisabledContent = Color(0x4DF5F0E8),
    actionDestructiveFill = Color(0xFFD08E86),
    actionOnDestructiveFill = Color(0xFF211E1B),
    navigation = Color(0xFF302A25),
    navigationActiveContent = Color(0xFFF5F0E8),
    navigationInactiveContent = Color(0xFFC3B9AE),
    navigationActiveSpot = Color(0x14E8C79F),
    fieldFill = Color(0xFF27231F),
    fieldContent = Color(0xFFF5F0E8),
    fieldPlaceholder = Color(0xFFACA095),
    fieldOutline = Color(0xFF91877D),
    waveform = Color(0xFFE8C79F),
    waveformCenter = Color(0xFFF5E0C7),
    waveformEdgeDecorative = Color(0x40E8C79F),
    waveformPlayed = Color(0xFFE8C79F),
    waveformUnplayed = Color(0xFF91877D),
    waveformPlayhead = Color(0xFFF5F0E8),
    orbInk = Color(0xFF211E1B),
    orbCenter = Color(0xFFF2D9BB),
    orbBody = Color(0xFFE8C79F),
    orbInnerMinimum = Color(0xFFC6A078),
    orbHighlight = Color(0xFFF5E0C7),
    orbBloom = Color(0x24E8C79F),
    orbRecordingCore = Color(0xFF302A25),
    orbRecordingWaveform = Color(0xFFE8C79F),
    backgroundIllumination = Color(0x26765B43),
    backgroundShadowField = Color(0x29000000),
    backgroundGrainLight = Color(0xFFF5F0E8),
    backgroundGrainDark = Color(0xFF191715),
    backgroundBrandShape = Color(0xFFE8C79F),
    overlayScrim = Color(0x8F0A0807),
    overlayHover = Color(0x0AF5F0E8),
    overlayPressed = Color(0x12F5F0E8),
    shadowFloating = Color(0x33000000),
    shadowLift = Color(0x3D000000),
)

internal val KashaLightColors = KashaColors(
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
    textPlaceholder = Color(0xFF70665D),
    iconPrimary = Color(0xFF211E1B),
    iconSecondary = Color(0xFF6C645D),
    iconInactive = Color(0xFF81776D),
    iconDisabled = Color(0x4D211E1B),
    accent = Color(0xFF886445),
    accentHover = Color(0xFF805C3E),
    accentPressed = Color(0xFF785538),
    accentContent = Color(0xFF7A583B),
    onAccent = Color(0xFFFEFCF6),
    accentContainer = Color(0x1A886445),
    hairline = Color(0x141E1914),
    controlOutline = Color(0xFF81776D),
    focusRing = Color(0xFF7A583B),
    success = Color(0xFF4E6547),
    successContainer = Color(0xFFE7EEE2),
    warning = Color(0xFF745629),
    warningContainer = Color(0xFFF5EBD8),
    error = Color(0xFF974C45),
    errorContainer = Color(0xFFF6E6E1),
    actionSecondaryFill = Color(0xFFFEFCF6),
    actionSecondaryHoverFill = Color(0xFFF2EFE6),
    actionSecondaryPressedFill = Color(0xFFEEEAE0),
    actionSecondaryContent = Color(0xFF211E1B),
    actionQuietContent = Color(0xFF6C645D),
    actionDisabledFill = Color(0xFFEEEAE0),
    actionDisabledContent = Color(0x4D211E1B),
    actionDestructiveFill = Color(0xFF974C45),
    actionOnDestructiveFill = Color(0xFFFEFCF6),
    navigation = Color(0xFFFEFCF6),
    navigationActiveContent = Color(0xFF211E1B),
    navigationInactiveContent = Color(0xFF6C645D),
    navigationActiveSpot = Color(0x14886445),
    fieldFill = Color(0xFFFEFCF6),
    fieldContent = Color(0xFF211E1B),
    fieldPlaceholder = Color(0xFF70665D),
    fieldOutline = Color(0xFF81776D),
    waveform = Color(0xFF7A583B),
    waveformCenter = Color(0xFF7A583B),
    waveformEdgeDecorative = Color(0x40886445),
    waveformPlayed = Color(0xFF886445),
    waveformUnplayed = Color(0xFF81776D),
    waveformPlayhead = Color(0xFF211E1B),
    orbInk = Color(0xFF211E1B),
    orbCenter = Color(0xFFF2D9BB),
    orbBody = Color(0xFFE8C79F),
    orbInnerMinimum = Color(0xFFC6A078),
    orbHighlight = Color(0xFFF5E0C7),
    orbBloom = Color(0x14886445),
    orbRecordingCore = Color(0xFF302A25),
    orbRecordingWaveform = Color(0xFFE8C79F),
    backgroundIllumination = Color(0x24E8C79F),
    backgroundShadowField = Color(0x0A503A26),
    backgroundGrainLight = Color(0xFFFEFCF6),
    backgroundGrainDark = Color(0xFF211E1B),
    backgroundBrandShape = Color(0xFF886445),
    overlayScrim = Color(0x52211A15),
    overlayHover = Color(0x08211E1B),
    overlayPressed = Color(0x0F211E1B),
    shadowFloating = Color(0x142A2018),
    shadowLift = Color(0x1F2A2018),
)

internal val LocalKashaColors = staticCompositionLocalOf { KashaDarkColors }
internal val LocalKashaIsDark = staticCompositionLocalOf { true }

object KashaMetrics {
    val hair = 2.dp
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 40.dp
    val section = 48.dp
    val hero = 64.dp
    val heroLarge = 80.dp

    val touchTargetMinimum = 44.dp
    val touchTargetPreferred = 48.dp
    val buttonHeight = 56.dp
    val quietButtonHeight = 48.dp
    val buttonHorizontalPadding = 20.dp
    val fieldHeight = 56.dp
    val rowHeight = 64.dp
    val rowPaddingVertical = 12.dp
    val navigationBaseHeight = 72.dp
    val navigationGapToPlayer = 8.dp

    val iconGlyph = 22.dp
    val focusRingWidth = 2.dp
    val focusRingOffset = 2.dp
    val controlOutlineWidth = 1.5.dp

    val radiusTiny = 8.dp
    val radiusSmall = 12.dp
    val radiusField = 16.dp
    val radiusSecondaryButton = 18.dp
    val radiusPrimaryButton = 20.dp
    val radiusSurface = 20.dp
    val radiusFloating = 24.dp
    val radiusNavigation = 28.dp
    val radiusSheetTop = 28.dp

    val pageMarginNarrow = 16.dp
    val pageMarginCompact = 20.dp
    val pageMarginMedium = 24.dp
    val pageMarginExpanded = 32.dp
    val readingColumnMax = 640.dp
    val contentColumnMax = 720.dp
    val navigationMax = 560.dp
    val sidebarWidth = 224.dp
    val shellMaxWidth = 1440.dp

    val orbIdle = 184.dp
    val orbIdleMin = 168.dp
    val orbIdleMax = 190.dp
    val orbRecording = 196.dp
    val captureRegion = 224.dp
    val waveformRecordingMaxWidth = 320.dp
    val waveformMiniMaxHeight = 24.dp

    val compactBreakpoint = 600.dp
    val desktopBreakpoint = 1024.dp
}
