package brain.studio

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import brain.studio.resources.*
import org.jetbrains.compose.resources.Font

private val LightInk = Color(0xFF1D1B18)
private val LightPaper = Color(0xFFF7F5EE)
private val LightSurface = Color(0xFFFFFEFA)
private val DarkPaper = Color(0xFF191715)
private val DarkSurface = Color(0xFF24211E)

@Composable
fun StudioTheme(theme: String, content: @Composable () -> Unit) {
    val dark = theme == "dark" || (theme == "system" && isSystemInDarkTheme())
    val textFamily = FontFamily(
        Font(Res.font.commissioner_regular, FontWeight.Normal),
        Font(Res.font.commissioner_medium, FontWeight.Medium),
        Font(Res.font.commissioner_semibold, FontWeight.SemiBold),
    )
    val displayFamily = FontFamily(Font(Res.font.commissioner_display_semibold, FontWeight.SemiBold))

    val colors = if (!dark) lightColorScheme(
        primary = LightInk, onPrimary = LightSurface, background = LightPaper,
        surface = LightSurface, onSurface = LightInk, onBackground = LightInk,
        surfaceVariant = Color(0xFFEDE9DF), onSurfaceVariant = Color(0xFF706B63),
        outline = Color(0xFFBAB4AA), error = Color(0xFFA33A35),
    ) else darkColorScheme(
        primary = Color(0xFFF5F0E8), onPrimary = DarkPaper, background = DarkPaper,
        surface = DarkSurface, onSurface = Color(0xFFF5F1EA), onBackground = Color(0xFFF5F1EA),
        surfaceVariant = Color(0xFF302C28), onSurfaceVariant = Color(0xFFB4ADA5),
        outline = Color(0xFF625B54), error = Color(0xFFEE9A92),
    )

    fun text(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
        fontFamily = textFamily, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight,
    )
    fun display(size: Int, line: Int) = TextStyle(
        fontFamily = displayFamily, fontSize = size.sp, lineHeight = line.sp, fontWeight = FontWeight.SemiBold,
    )

    val typography = Typography(
        displayLarge = display(46, 50), displayMedium = display(38, 43), displaySmall = display(32, 37),
        headlineLarge = display(28, 34), headlineMedium = display(24, 30), headlineSmall = display(20, 26),
        titleLarge = text(20, 27, FontWeight.Medium), titleMedium = text(17, 24, FontWeight.Medium), titleSmall = text(15, 21, FontWeight.Medium),
        bodyLarge = text(17, 26), bodyMedium = text(15, 22), bodySmall = text(13, 19),
        labelLarge = text(15, 20, FontWeight.Medium), labelMedium = text(13, 18, FontWeight.Medium), labelSmall = text(11, 16, FontWeight.Medium),
    )
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
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
