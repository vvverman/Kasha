package ru.vrmn.kasha.android

import android.content.Context
import brain.ai.BuiltInAi
import brain.ai.BuiltInText
import brain.ai.LocalTextRoles
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
    val packages = AndroidWhisperPackages(
        File(context.filesDir, "Kasha/models"),
        artifacts = ModelArtifacts.packages,
        engineAvailable = { id ->
            when (id) {
                in ModelArtifacts.speech -> AndroidWhisperNative.available
                in ModelArtifacts.text -> AndroidLlamaNative.available
                else -> false
            }
        },
    )
    private val whisper = AndroidWhisper(context.applicationContext, packages)
    private val llama = AndroidLlama(packages)
    override val simulated = false
    fun speechAvailable() = speech.available() || packages.hasVerifiedModel(ModelArtifacts.speech.keys)

    override suspend fun transcribe(file: String, language: String, example: String): String {
        val selected = preferences().ai.speechToText
        return when {
            selected in ModelArtifacts.speech -> whisper.transcribe(selected, file, language)
            selected == BuiltInAi.ANDROID_SPEECH -> speech.transcribe(file, language)
            else -> error("androidAiNotConfigured")
        }
    }

    // Выбор фиксируется на всю операцию, в том числе на все проекты одного routing.
    private fun textModel(engine: String) = LocalTextRoles { _, prompt, _, tokens ->
        llama.generate(engine, prompt, tokens)
    }

    override suspend fun title(text: String, language: String): String {
        val selected = preferences().ai.text
        return when (selected) {
            in ModelArtifacts.text -> textModel(selected).title(text)
            BuiltInAi.LOCAL_RULES -> BuiltInText.title(text, language)
            else -> error("androidAiNotConfigured")
        }
    }
    override suspend fun tidy(text: String, language: String): String {
        val selected = preferences().ai.text
        return when (selected) {
            in ModelArtifacts.text -> textModel(selected).tidy(text)
            BuiltInAi.LOCAL_RULES -> BuiltInText.tidy(text, language)
            else -> error("androidAiNotConfigured")
        }
    }
    override suspend fun rank(text: String, projects: List<Project>, language: String): Map<String, Int> {
        if (projects.isEmpty()) return emptyMap()
        val selected = preferences().ai.routing
        return when (selected) {
            in ModelArtifacts.text -> textModel(selected).rank(text, projects)
            BuiltInAi.LOCAL_RULES -> BuiltInText.rank(text, projects, language)
            else -> error("androidAiNotConfigured")
        }
    }

    override suspend fun roles(selection: AiSelection): List<AiRoleCapability> {
        val language = Languages.resolve(preferences().language, Locale.getDefault().toLanguageTag())
        val installed = packages.states().filter { it.installed }.map { it.engineId }.toSet()
        val nativeReady = selection.speechToText == BuiltInAi.ANDROID_SPEECH && speech.supports(language)
        return AiRole.entries.map { role ->
            val id = selection.engineId(role)
            val model = if (role == AiRole.SPEECH_TO_TEXT) id in ModelArtifacts.speech else id in ModelArtifacts.text
            val supported = model || BuiltInAi.supportsAndroid(role, id)
            val ready = supported && when {
                model -> id in installed
                role == AiRole.SPEECH_TO_TEXT -> nativeReady
                else -> true
            }
            AiRoleCapability(role, id, ready, when {
                ready -> null
                supported -> "androidAiNotConfigured"
                else -> "platformUnavailable"
            })
        }
    }
}
