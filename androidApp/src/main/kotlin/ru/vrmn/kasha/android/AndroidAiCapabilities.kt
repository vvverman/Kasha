package ru.vrmn.kasha.android

import brain.ai.BuiltInAi
import brain.ai.ModelArtifacts
import brain.studio.*
import kotlinx.coroutines.CancellationException

/** Только факты Android-исполнения; выбор причины и действия общий. */
internal suspend fun androidRoleCapabilities(
    selection: AiSelection,
    language: String,
    packageStates: suspend () -> List<AiPackageState>,
    runtimeReady: (String) -> Boolean,
    nativeSpeech: suspend () -> AiRoleCapability,
): List<AiRoleCapability> {
    var states: List<AiPackageState>? = null
    return AiRole.entries.map { role ->
        val id = selection.engineId(role)
        try {
            val local = if (role == AiRole.SPEECH_TO_TEXT) id in ModelArtifacts.speech else id in ModelArtifacts.text
            when {
                local -> {
                    val runnable = runtimeReady(id)
                    if (runnable && states == null) states = packageStates()
                    val languages = AiCatalog.engine(id)?.languages.orEmpty()
                    val supportedLanguage = languages.isEmpty() || language.substringBefore('-').substringBefore('_') in languages
                    AiReadiness.local(role, id, true, supportedLanguage, states?.firstOrNull { it.engineId == id }, runnable)
                }
                !BuiltInAi.supportsAndroid(role, id) -> AiReadiness.blocked(role, id, "platformUnavailable")
                role == AiRole.SPEECH_TO_TEXT -> nativeSpeech()
                else -> AiRoleCapability(role, id, true)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { AiReadiness.blocked(role, id, "capabilityCheckFailed") }
    }
}
