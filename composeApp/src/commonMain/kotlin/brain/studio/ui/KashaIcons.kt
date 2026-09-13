package brain.studio

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser

/**
 * Единственный runtime-реестр Kasha Icons.
 * Канонические id совпадают с docs/design/icons/registry.json; legacy значения оставлены как aliases.
 */
enum class Glyph(val id: String) {
    HOME("home"), PROJECTS("projects"), TASKS("tasks"), SETTINGS("settings"),
    RECORD("record"), MIC("mic"), PLAY("play"), PAUSE("pause"), STOP("stop"), SEND("send"),
    BACK("back"), NEXT("next"), ADD("add"), DELETE("delete"), TEXT_PROCESSING("text-processing"), MORE("more"),
    PIN("pin"), UNPIN("unpin"), EDIT("edit"), UP("up"), DOWN("down"), CHECK("check"), ARCHIVE("archive"), CLOCK("clock"),
    NOTE("note"), CLOSE("close"), SORT("sort"), DRAG("drag"), SEARCH("search"),
    CALENDAR("calendar"), REMINDER("reminder"), REPEAT("repeat"), COMPLETE("complete"),
    LANGUAGE("language"), THEME("theme"), LOCAL("local"), EXTERNAL("external"), PRIVACY("privacy"),
    SPEECH("speech"), ROUTING("routing"), VISIBILITY("visibility"), VISIBILITY_OFF("visibility-off"),

    // Совместимость со старым shared UI. Новые вызовы используют semantic names выше.
    FOLDER("projects"), PLUS("add"), MAGIC("text-processing"),
}

private data class Motion(
    val rotation: Float = 0f,
    val x: Float = 0f,
    val y: Float = 0f,
    val scale: Float = 1f,
)

/** Небольшая одноразовая реакция; геометрия смысла не меняется. */
private fun motion(glyph: Glyph): Motion = when (glyph.id) {
    "record" -> Motion(scale = 1.035f)
    "play" -> Motion(x = .7f, scale = 1.025f)
    "pause", "stop" -> Motion(scale = .98f)
    "send" -> Motion(x = .8f, y = -.5f, scale = 1.02f)
    "home", "projects", "tasks" -> Motion(y = -.5f, scale = 1.02f)
    "settings" -> Motion(rotation = 8f)
    "back" -> Motion(x = -.8f)
    "next" -> Motion(x = .8f)
    "add" -> Motion(scale = 1.04f)
    "delete" -> Motion(rotation = -3f, y = .4f)
    "text-processing" -> Motion(y = -.45f, scale = 1.02f)
    "more" -> Motion(scale = 1.035f)
    "pin", "unpin" -> Motion(y = -.6f, scale = 1.02f)
    "edit" -> Motion(rotation = -3f, x = .45f, y = -.35f)
    "up" -> Motion(y = -.8f)
    "down" -> Motion(y = .8f)
    "check", "complete" -> Motion(scale = 1.035f)
    "archive" -> Motion(y = .6f, scale = .985f)
    "clock" -> Motion(rotation = 6f)
    "search" -> Motion(scale = 1.025f)
    "visibility", "visibility-off" -> Motion(scale = .98f)
    else -> Motion(scale = 1.015f)
}

@Composable
fun KashaIcon(
    glyph: Glyph,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    animated: Boolean = false,
    variant: String? = null,
) {
    val reducedMotion = KashaMotion.reduced
    val spec = motion(glyph)
    val phase by animateFloatAsState(
        targetValue = if (animated && !reducedMotion) 1f else 0f,
        animationSpec = tween(if (reducedMotion) 120 else 240, easing = FastOutSlowInEasing),
        label = "kasha-${glyph.id}",
    )
    val definition = generatedKashaIcons[glyph.id]
    Canvas(
        modifier.graphicsLayer {
            rotationZ = spec.rotation * phase
            translationX = spec.x * density * phase
            translationY = spec.y * density * phase
            val s = 1f + (spec.scale - 1f) * phase
            scaleX = s
            scaleY = s
        },
    ) {
        if (definition == null) return@Canvas
        val geometry = variant?.let { definition.variants[it] } ?: definition.geometry
        val u = size.minDimension / 24f
        val dx = (size.width - 24f * u) / 2f
        val dy = (size.height - 24f * u) / 2f
        val stroke = Stroke(GENERATED_KASHA_ICON_STROKE * u)
        fun p(x: Float, y: Float) = Offset(dx + x * u, dy + y * u)
        geometry.forEach { shape ->
            val shapeColor = color.copy(alpha = color.alpha * shape.opacity)
            when (shape.type) {
                "path" -> {
                    val raw = PathParser().parsePathString(shape.d ?: return@forEach).toPath()
                    val path = androidx.compose.ui.graphics.Path().also { target ->
                        target.addPath(raw)
                        target.translate(Offset(dx, dy))
                        target.transform(androidx.compose.ui.graphics.Matrix().apply { scale(u, u) })
                    }
                    if (shape.fill) drawPath(path, shapeColor, style = Fill)
                    if (shape.stroke) drawPath(path, shapeColor, style = stroke)
                }
                "line" -> if (shape.stroke) drawLine(shapeColor, p(shape.x1 ?: 0f, shape.y1 ?: 0f), p(shape.x2 ?: 0f, shape.y2 ?: 0f), GENERATED_KASHA_ICON_STROKE * u)
                "circle" -> {
                    val center = p(shape.cx ?: 0f, shape.cy ?: 0f)
                    val radius = (shape.r ?: 0f) * u
                    if (shape.fill) drawCircle(shapeColor, radius, center, style = Fill)
                    if (shape.stroke) drawCircle(shapeColor, radius, center, style = stroke)
                }
                "rect" -> {
                    val topLeft = p(shape.x ?: 0f, shape.y ?: 0f)
                    val sizePx = Size((shape.width ?: 0f) * u, (shape.height ?: 0f) * u)
                    val corner = CornerRadius((shape.rx ?: 0f) * u)
                    if (shape.fill) drawRoundRect(shapeColor, topLeft, sizePx, corner, style = Fill)
                    if (shape.stroke) drawRoundRect(shapeColor, topLeft, sizePx, corner, style = stroke)
                }
            }
        }
    }
}

@Composable
fun Symbol(
    glyph: Glyph,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    animated: Boolean = false,
) = KashaIcon(glyph, modifier, color, animated)
