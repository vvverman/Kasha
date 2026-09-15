package ru.vrmn.kasha.android

import android.content.Context
import brain.ai.BuiltInAi
import brain.ai.BuiltInText
import brain.ai.ModelArtifacts
import brain.model.Project
import brain.studio.*
import java.io.File
import java.util.Locale

/** Только исполнение выбранных AI-ролей; сценарии заметок/задач остаются в Core. */
internal class AndroidIntelligence(
    context: Context,
    private val preferences: suspend () -> Preferences,
) : Intelligence, AiExecutionCapabilityGateway {
    private val speech = AndroidOnDeviceSpeech(context)
    val packages = AndroidWhisperPackages(File(context.filesDir, "Kasha/models"))
    private val whisper = AndroidWhisper(context.applicationContext, packages)
    override val simulated = false
    fun speechAvailable() = speech.available() || packages.hasVerifiedModel()

    override suspend fun transcribe(file: String, language: String, example: String): String {
        val selected = preferences().ai.speechToText
        return when {
            selected in ModelArtifacts.speech -> whisper.transcribe(selected, file, language)
            selected == BuiltInAi.ANDROID_SPEECH -> speech.transcribe(file, language)
            else -> error("androidAiNotConfigured")
        }
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
        val localReady = if (selection.speechToText in ModelArtifacts.speech)
            packages.states().any { it.engineId == selection.speechToText && it.installed } else false
        val nativeReady = selection.speechToText == BuiltInAi.ANDROID_SPEECH && speech.supports(language)
        return AiRole.entries.map { role ->
            val id = selection.engineId(role)
            val model = role == AiRole.SPEECH_TO_TEXT && id in ModelArtifacts.speech
            val supported = model || BuiltInAi.supportsAndroid(role, id)
            val ready = supported && (role != AiRole.SPEECH_TO_TEXT || if (model) localReady else nativeReady)
            AiRoleCapability(role, id, ready, when {
                ready -> null
                supported -> "androidAiNotConfigured"
                else -> "platformUnavailable"
            })
        }
    }
}
