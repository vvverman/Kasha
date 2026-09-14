package brain.domain

import brain.model.AudioSpan
import brain.model.TranscriptPiece
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.math.max
import kotlin.math.min

object AudioTimeline {
    fun compactTime(time: Double, spans: List<AudioSpan>): Double {
        val t = if (time.isFinite()) max(0.0, time) else 0.0
        for (span in spans) {
            if (t < span.originalStart) return span.compactStart
            if (t <= span.originalStart + span.duration) return span.compactStart + t - span.originalStart
        }
        return spans.lastOrNull()?.let { it.compactStart + it.duration } ?: t
    }
}

object SilencePlanner {
    fun spans(levels: List<Double>, frameSeconds: Double, duration: Double): List<AudioSpan> {
        if (!duration.isFinite() || duration <= 0) return emptyList()
        val all = listOf(AudioSpan(0.0, duration, 0.0))
        if (frameSeconds <= 0 || !frameSeconds.isFinite()) return all
        val ranges = mutableListOf<Pair<Double, Double>>()
        levels.forEachIndexed { i, db ->
            if (db.isFinite() && db > -48) {
                val start = max(0.0, i * frameSeconds - 0.25)
                val end = min(duration, (i + 1) * frameSeconds + 0.25)
                if (end > start) {
                    val previous = ranges.lastOrNull()
                    if (previous != null && start - previous.second < 0.9) ranges[ranges.lastIndex] = previous.first to max(previous.second, end)
                    else ranges += start to end
                }
            }
        }
        if (ranges.isEmpty()) return all
        var cursor = 0.0
        return ranges.map { (start, end) -> AudioSpan(start, end - start, cursor).also { cursor += it.duration } }
    }
}

object TextChunks {
    fun split(text: String, maxChars: Int = 1800): List<String> {
        require(maxChars >= 2)
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = min(text.length, start + maxChars)
            if (end < text.length) {
                if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
                val boundary = (end - 1 downTo start + maxChars / 2).firstOrNull { text[it].isWhitespace() }
                if (boundary != null) end = boundary + 1
            }
            result += text.substring(start, end); start = end
        }
        return result
    }
}

@Serializable data class CleanedText(val title: String, val text: String)
@Serializable data class Relevance(val relevance: Int)

object ModelOutput {
    private val json = Json { ignoreUnknownKeys = true }
    private fun unfence(value: String) = value.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    fun cleaned(value: String, original: String): CleanedText {
        val result = json.decodeFromString<CleanedText>(unfence(value))
        require(result.text.isNotBlank() && result.text.length >= original.trim().length / 2) { "Модель слишком сильно сократила текст. Полный транскрипт сохранён" }
        require(result.text.length <= max(200, original.length * 2)) { "Модель добавила слишком много текста. Полный транскрипт сохранён" }
        LocalModelText.requirePreserved(original, result.text)
        return result.copy(title = LocalModelText.safeTitle(result.title, original))
    }
    fun relevance(value: String): Int = json.decodeFromString<Relevance>(unfence(value)).relevance.also {
        require(it in 0..4) { "Модель вернула некорректную оценку проекта" }
    }
    fun whisperPieces(value: String, duration: Double): List<TranscriptPiece> {
        val segments = json.parseToJsonElement(value).jsonObject["transcription"]?.jsonArray ?: return emptyList()
        return segments.mapNotNull { item ->
            val obj = item.jsonObject
            val offsets = obj["offsets"]?.jsonObject ?: return@mapNotNull null
            val from = offsets["from"]?.jsonPrimitive?.doubleOrNull?.div(1000) ?: return@mapNotNull null
            val to = offsets["to"]?.jsonPrimitive?.doubleOrNull?.div(1000) ?: return@mapNotNull null
            if (!from.isFinite() || !to.isFinite() || from < 0 || to < from || from > duration) null
            else TranscriptPiece(from, min(duration, to), obj["text"]?.jsonPrimitive?.content.orEmpty())
        }
    }
}
