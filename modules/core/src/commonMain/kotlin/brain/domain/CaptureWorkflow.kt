package brain.domain

import brain.model.Capture
import brain.model.CaptureStatus
import brain.model.Project
import brain.studio.Intelligence
import kotlin.math.max

/**
 * Общая AI/text-оркестрация одной голосовой записи.
 *
 * Заголовок больше не является отдельным пользовательским или AI-полем:
 * он всегда вычисляется из первой непустой строки текста, как в Apple Notes.
 */
class CaptureWorkflow(private val intelligence: Intelligence) {

    private fun validatedScores(projects: List<Project>, scores: Map<String, Int>): Map<String, Int> {
        val expected = projects.map { it.id }.toSet()
        require(scores.keys == expected) { "Модель вернула оценки не для того набора проектов" }
        require(scores.values.all { it in 0..4 }) { "Модель вернула некорректную оценку проекта" }
        return scores
    }

    suspend fun finish(capture: Capture, projects: List<Project>, language: String): Capture {
        require(capture.isInbox)
        val original = capture.textToSave
        val scores = validatedScores(projects, intelligence.rank(original, projects, language))
        return capture.copy(
            title = NoteText.title(original),
            relevance = scores,
            rankingApplied = true,
            status = CaptureStatus.READY,
            message = "",
            simulated = intelligence.simulated,
        )
    }

    suspend fun tidy(capture: Capture, language: String): Capture {
        require(capture.isInbox && !capture.status.isWorking)
        val original = capture.textToSave
        val text = intelligence.tidy(original, language)
        require(text.isNotBlank())
        require(text.length >= original.trim().length / 2) { "Модель слишком сильно сократила текст. Оставлен исходный текст" }
        require(text.length <= max(200, original.length * 2)) { "Модель добавила слишком много текста. Оставлен исходный текст" }
        LocalModelText.requirePreserved(original, text)
        return capture.copy(
            title = NoteText.title(text),
            preparedText = text,
            draftEdited = true,
            llmApplied = true,
            rankingApplied = false,
            relevance = emptyMap(),
            status = CaptureStatus.READY,
            message = "",
            simulated = intelligence.simulated,
        )
    }

    suspend fun rank(capture: Capture, projects: List<Project>, language: String): Capture {
        require(capture.isInbox && !capture.status.isWorking)
        val scores = validatedScores(projects, intelligence.rank(capture.textToSave, projects, language))
        return capture.copy(
            title = NoteText.title(capture.textToSave),
            relevance = scores,
            rankingApplied = true,
            simulated = intelligence.simulated,
        )
    }
}
