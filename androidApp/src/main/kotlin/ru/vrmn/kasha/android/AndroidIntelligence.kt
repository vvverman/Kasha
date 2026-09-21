package ru.vrmn.kasha.android

import android.content.Context
import brain.ai.KashaAiCatalog
import brain.ai.ManagedCloudGateway
import brain.ai.ExternalTextRoles
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
    private val cloud: ManagedCloudGateway? = null,
    private val preferences: suspend () -> Preferences,
) : Intelligence, AiExecutionCapabilityGateway {
    private val speech = AndroidOnDeviceSpeech(context)
    val packages = AndroidWhisperPackages(
        File(context.filesDir, "Kasha/models"),
        artifacts = ModelArtifacts.packages,
        engineAvailable = ::runtimeReady,
    )
    private val whisper = AndroidWhisper(context.applicationContext, packages)
    private val llama = AndroidLlama(packages)
    override val simulated = false
    fun speechAvailable() = speech.available() || packages.hasVerifiedModel(ModelArtifacts.speech.keys)

    override suspend fun transcribe(file: String, language: String, example: String): String {
        val selected = selected(AiRole.SPEECH_TO_TEXT)
        AiCatalog.cloudProviderId(selected)?.let { return requireCloud().transcribe(it, file, language) }
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
        val selected = selected(AiRole.TEXT)
        AiCatalog.cloudProviderId(selected)?.let { return external(it).title(text) }
        return when (selected) {
            in ModelArtifacts.text -> textModel(selected).title(text)
            BuiltInAi.LOCAL_RULES -> BuiltInText.title(text, language)
            else -> error("androidAiNotConfigured")
        }
    }
    override suspend fun tidy(text: String, language: String): String {
        val selected = selected(AiRole.TEXT)
        AiCatalog.cloudProviderId(selected)?.let { return external(it).tidy(text) }
        return when (selected) {
            in ModelArtifacts.text -> textModel(selected).tidy(text)
            BuiltInAi.LOCAL_RULES -> BuiltInText.tidy(text, language)
            else -> error("androidAiNotConfigured")
        }
    }
    override suspend fun rank(text: String, projects: List<Project>, language: String): Map<String, Int> {
        if (projects.isEmpty()) return emptyMap()
        val selected = selected(AiRole.ROUTING)
        AiCatalog.cloudProviderId(selected)?.let { return external(it).rank(text, projects) }
        return when (selected) {
            in ModelArtifacts.text -> textModel(selected).rank(text, projects)
            BuiltInAi.LOCAL_RULES -> BuiltInText.rank(text, projects, language)
            else -> error("androidAiNotConfigured")
        }
    }

    private suspend fun selected(role: AiRole): String {
        val id = preferences().ai.engineId(role)
        check(KashaAiCatalog.supportsSelection(id, role)) { "aiUnavailable" }
        return KashaAiCatalog.canonicalEngineId(id)
    }
    private fun requireCloud() = cloud ?: error("cloudConnectionUnavailable")
    private fun external(provider: String) = ExternalTextRoles { role, prompt -> requireCloud().generate(provider, role, prompt) }

    private fun runtimeReady(id: String): Boolean = when (id) {
        in ModelArtifacts.speech -> AndroidWhisperNative.available
        in ModelArtifacts.text -> AndroidLlamaNative.available
        else -> false
    }

    override suspend fun roles(selection: AiSelection): List<AiRoleCapability> {
        val language = Languages.resolve(preferences().language, Locale.getDefault().toLanguageTag())
        return androidRoleCapabilities(selection, language, packages::states, ::runtimeReady,
            cloud = { role, id, provider -> cloud?.capability(role, id, provider) ?: AiReadiness.blocked(role, id, "platformUnavailable") },
            nativeSpeech = { speech.capability(language) })
    }
}
