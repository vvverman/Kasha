package ru.vrmn.kasha.android

import android.content.Context
import brain.ai.BuiltInAi
import brain.ai.BuiltInText
import brain.model.Project
import brain.studio.*
import java.util.Locale

/** Порты исполнения, без правил заметок, задач, сохранения и выбора проекта в оболочке. */
internal class AndroidIntelligence(
    context: Context,
    private val preferences: suspend () -> Preferences,
) : Intelligence, AiExecutionCapabilityGateway {
    private val speech = AndroidOnDeviceSpeech(context)
    override val simulated = false
    fun speechAvailable() = speech.available()

    override suspend fun transcribe(file: String, language: String, example: String): String {
        requireRole(AiRole.SPEECH_TO_TEXT)
        return speech.transcribe(file, language)
    }
    override suspend fun title(text: String, language: String): String {
        requireRole(AiRole.TEXT)
        return BuiltInText.title(text, language)
    }
    override suspend fun tidy(text: String, language: String): String {
        requireRole(AiRole.TEXT)
        return BuiltInText.tidy(text, language)
    }
    override suspend fun rank(text: String, projects: List<Project>, language: String): Map<String, Int> {
        requireRole(AiRole.ROUTING)
        return BuiltInText.rank(text, projects, language)
    }
    private suspend fun requireRole(role: AiRole) {
        check(BuiltInAi.supportsAndroid(role, preferences().ai.engineId(role))) { "androidAiNotConfigured" }
    }

    override suspend fun roles(selection: AiSelection): List<AiRoleCapability> {
        val language = Languages.resolve(preferences().language, Locale.getDefault().toLanguageTag())
        val speechReady = selection.speechToText == BuiltInAi.ANDROID_SPEECH && speech.supports(language)
        return AiRole.entries.map { role ->
            val id = selection.engineId(role)
            val supported = BuiltInAi.supportsAndroid(role, id)
            val ready = supported && (role != AiRole.SPEECH_TO_TEXT || speechReady)
            AiRoleCapability(role, id, ready, when {
                ready -> null
                supported -> "androidAiNotConfigured"
                else -> "platformUnavailable"
            })
        }
    }
}
