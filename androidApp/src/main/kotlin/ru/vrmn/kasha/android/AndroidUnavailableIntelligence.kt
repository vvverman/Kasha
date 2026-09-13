package ru.vrmn.kasha.android

import brain.model.Project
import brain.studio.Intelligence

/**
 * Честная capability-заглушка до подключения Android AI-role adapters.
 * Она не симулирует результат и оставляет сохранённое аудио в NEEDS_MODEL.
 */
internal object AndroidUnavailableIntelligence : Intelligence {
    override val simulated: Boolean = false

    override suspend fun transcribe(file: String, language: String, example: String): String =
        error("androidAiNotConfigured")

    override suspend fun title(text: String, language: String): String =
        error("androidAiNotConfigured")

    override suspend fun tidy(text: String, language: String): String =
        error("androidAiNotConfigured")

    override suspend fun rank(text: String, projects: List<Project>, language: String): Map<String, Int> =
        error("androidAiNotConfigured")
}
