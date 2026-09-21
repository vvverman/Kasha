package ru.vrmn.kasha.android

import brain.ai.KashaAiCatalog
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
    cloud: suspend (AiRole, String, String) -> AiRoleCapability = { role, id, _ -> AiReadiness.blocked(role, id, "platformUnavailable") },
    nativeSpeech: suspend () -> AiRoleCapability,
): List<AiRoleCapability> {
    var states: List<AiPackageState>? = null
    return AiRole.entries.map { role ->
        val selectedId = selection.engineId(role)
        val id = KashaAiCatalog.canonicalEngineId(selectedId, role)
        try {
            val local = when (role) {
                AiRole.SPEECH_TO_TEXT -> id in ModelArtifacts.speech
                AiRole.TEXT -> id in ModelArtifacts.text
                AiRole.ROUTING -> id in ModelArtifacts.routing
            }
            when {
                !KashaAiCatalog.supportsSelection(selectedId, role) -> AiReadiness.blocked(role, selectedId, "platformUnavailable")
                AiCatalog.cloudProviderId(id) != null -> cloud(role, selectedId, AiCatalog.cloudProviderId(id)!!)
                local -> {
                    val runnable = runtimeReady(id)
                    if (runnable && states == null) states = packageStates()
                    val languages = AiCatalog.engine(id)?.languages.orEmpty()
                    val supportedLanguage = languages.isEmpty() || language.substringBefore('-').substringBefore('_') in languages
                    AiReadiness.local(role, id, true, supportedLanguage, states?.firstOrNull { it.engineId == id }, runnable).copy(selectedEngineId = selectedId)
                }
                !BuiltInAi.supportsAndroid(role, id) -> AiReadiness.blocked(role, id, "platformUnavailable")
                role == AiRole.SPEECH_TO_TEXT -> nativeSpeech()
                else -> AiRoleCapability(role, id, true)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { AiReadiness.blocked(role, selectedId, "capabilityCheckFailed") }
    }
}
