package brain.ios

import brain.ai.BuiltInAi
import brain.ai.KashaAiCatalog
import brain.ai.ModelArtifacts
import brain.model.Project
import brain.studio.*

/** Выбранный идентификатор соответствует реальному обработчику, без скрытой подмены. */
internal class IosRoutedIntelligence(
    private val local: IosOnDeviceIntelligence,
    private val cloud: IosCloudAiGateway?,
    private val models: IosLocalModels?,
    private val preferences: () -> Preferences,
) : Intelligence {
    constructor(local: IosOnDeviceIntelligence, cloud: IosCloudAiGateway?, preferences: () -> Preferences) :
        this(local, cloud, null, preferences)

    override val simulated: Boolean = false

    override suspend fun transcribe(file: String, language: String, example: String): String {
        val selected = selectedEngine(AiRole.SPEECH_TO_TEXT)
        val modelId = KashaAiCatalog.canonicalEngineId(selected, AiRole.SPEECH_TO_TEXT)
        if (modelId in ModelArtifacts.speech && models != null) return models.transcribe(modelId, file, language)
        val provider = AiCatalog.cloudProviderId(selected)
        return if (provider == null) {
            BuiltInAi.requireApple(AiRole.SPEECH_TO_TEXT, selected)
            local.transcribe(file, language, example)
        } else {
            requireCloud().transcribe(provider, file, language)
        }
    }

    override suspend fun title(text: String, language: String): String {
        val selected = selectedEngine(AiRole.TEXT)
        val modelId = KashaAiCatalog.canonicalEngineId(selected, AiRole.TEXT)
        if (modelId in ModelArtifacts.text && models != null) return models.title(modelId, text)
        val provider = AiCatalog.cloudProviderId(selected)
        if (provider == null) {
            BuiltInAi.requireApple(AiRole.TEXT, selected)
            return local.title(text, language)
        }
        return external(provider).title(text)
    }

    override suspend fun tidy(text: String, language: String): String {
        val selected = selectedEngine(AiRole.TEXT)
        val modelId = KashaAiCatalog.canonicalEngineId(selected, AiRole.TEXT)
        if (modelId in ModelArtifacts.text && models != null) return models.tidy(modelId, text)
        val provider = AiCatalog.cloudProviderId(selected)
        if (provider != null) return external(provider).tidy(text)
        BuiltInAi.requireApple(AiRole.TEXT, selected)
        return local.tidy(text, language)
    }

    override suspend fun rank(text: String, projects: List<Project>, language: String): Map<String, Int> {
        if (projects.isEmpty()) return emptyMap()
        val selected = selectedEngine(AiRole.ROUTING)
        val modelId = KashaAiCatalog.canonicalEngineId(selected, AiRole.ROUTING)
        if (modelId in ModelArtifacts.routing && models != null) return models.rank(modelId, text, projects)
        val provider = AiCatalog.cloudProviderId(selected)
        if (provider != null) return external(provider).rank(text, projects)
        BuiltInAi.requireApple(AiRole.ROUTING, selected)
        return local.rank(text, projects, language)
    }

    private fun external(provider: String) = brain.ai.ExternalTextRoles { role, prompt -> requireCloud().generate(provider, role, prompt) }

    suspend fun capabilities(selection: AiSelection, language: String): List<AiRoleCapability> =
        AiRole.entries.map { role ->
            val id = selection.engineId(role)
            try {
                val provider = AiCatalog.cloudProviderId(id)
                when {
                    !KashaAiCatalog.supportsSelection(id, role) -> AiReadiness.blocked(role, id, "platformUnavailable")
                    provider != null -> cloud?.capability(role, id, provider)
                        ?: AiReadiness.blocked(role, id, "platformUnavailable")
                    KashaAiCatalog.canonicalEngineId(id, role) in ModelArtifacts.packages && models != null ->
                        models.capability(role, KashaAiCatalog.canonicalEngineId(id, role), language).copy(selectedEngineId = id)
                    !BuiltInAi.supportsApple(role, id) -> AiReadiness.blocked(role, id, "platformUnavailable")
                    role == AiRole.SPEECH_TO_TEXT -> local.capability(language)
                    else -> AiRoleCapability(role, id, true)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { AiReadiness.blocked(role, id, "capabilityCheckFailed") }
        }

    private fun selectedEngine(role: AiRole): String = preferences().ai.engineId(role).also { id ->
        check(KashaAiCatalog.supportsSelection(id, role)) { "aiUnavailable" }
    }
    private fun requireCloud(): IosCloudAiGateway = cloud ?: error("aiUnavailable")
}
