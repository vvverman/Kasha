package brain.runtime.ai

import brain.model.Project
import brain.runtime.*
import brain.studio.*
import java.nio.file.Path

/**
 * Runtime-router: на каждом вызове читает актуальный выбор пользователя.
 * Core workflow остаётся прежним и видит только Intelligence facade.
 */
class RoutedStudioIntelligence(
    private val preferences: PreferenceStore,
    private val env: Map<String, String>,
    private val root: Path,
    private val packages: JvmAiPackageGateway,
    private val cloud: JvmCloudAiGateway,
    private val runner: CommandRunner = JvmCommandRunner(),
) : Intelligence {
    override val simulated = false

    override suspend fun transcribe(file: String, language: String, example: String): String {
        val selected = selectedEngine(AiRole.SPEECH_TO_TEXT)
        val provider = AiCatalog.cloudProviderId(selected)
        return if (provider != null) {
            cloud.transcribe(provider, Path.of(file), language)
        } else {
            local(selected, AiRole.SPEECH_TO_TEXT) { it.transcribe(file, language, example) }
        }
    }

    override suspend fun title(text: String, language: String): String {
        val selected = selectedEngine(AiRole.TEXT)
        val provider = AiCatalog.cloudProviderId(selected)
        if (provider == null) return local(selected, AiRole.TEXT) { it.title(text, language) }

        return external(provider).title(text)
    }

    override suspend fun tidy(text: String, language: String): String {
        val selected = selectedEngine(AiRole.TEXT)
        val provider = AiCatalog.cloudProviderId(selected)
        return if (provider == null) local(selected, AiRole.TEXT) { it.tidy(text, language) } else external(provider).tidy(text)
    }

    override suspend fun rank(text: String, projects: List<Project>, language: String): Map<String, Int> {
        if (projects.isEmpty()) return emptyMap()
        val selected = selectedEngine(AiRole.ROUTING)
        val provider = AiCatalog.cloudProviderId(selected)
        return if (provider == null) local(selected, AiRole.ROUTING) { it.rank(text, projects, language) } else external(provider).rank(text, projects)
    }

    private fun external(provider: String) = brain.ai.ExternalTextRoles { role, prompt -> cloud.generate(provider, role, prompt) }

    private suspend fun selectedEngine(role: AiRole): String = preferences.read().ai.engineId(role).also {
        check(AiCatalog.supportsSelection(it, role)) { "aiUnavailable" }
    }

    private suspend fun <T> local(engineId: String, role: AiRole, action: suspend (LocalStudioIntelligence) -> T): T {
        val descriptor = AiCatalog.engine(engineId) ?: error("Неизвестный локальный AI engine: $engineId")
        require(descriptor.supports(role))
        return packages.withModel(descriptor.id) { model ->
            val configured = when (role) {
                AiRole.SPEECH_TO_TEXT -> env + ("KASHA_WHISPER_MODEL" to model.toString())
                AiRole.TEXT, AiRole.ROUTING -> env + ("KASHA_LLAMA_MODEL" to model.toString())
            }
            action(LocalStudioIntelligence(configured, root, runner))
        }
    }
}
