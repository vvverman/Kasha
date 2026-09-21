package brain.ios

import brain.ai.LocalTextRoles
import brain.ai.EmbeddingProjectRouting
import brain.ai.ModelArtifacts
import brain.model.Project
import brain.studio.*
import kotlinx.coroutines.CancellationException

/** Один каталог и LocalTextRoles с Android/Desktop; здесь только iOS-исполнение. */
internal class IosLocalModels(val packages: IosModelPackages, private val native: IosNativeModels) {
    suspend fun transcribe(id: String, file: String, language: String): String =
        packages.withModel(id) { native.transcribe(it, file, language) }

    private suspend fun <T> text(id: String, use: suspend (LocalTextRoles) -> T): T =
        packages.withModel(id) { model ->
            use(LocalTextRoles { _, prompt, _, tokens -> native.generate(model, prompt, tokens) })
        }
    suspend fun title(id: String, value: String) = text(id) { it.title(value) }
    suspend fun tidy(id: String, value: String) = text(id) { it.tidy(value) }
    suspend fun rank(id: String, value: String, projects: List<Project>) =
        EmbeddingProjectRouting.rank(value, projects) { text ->
            packages.withModel(id) { model -> native.embed(model, text) }
        }

    suspend fun capability(role: AiRole, id: String, language: String): AiRoleCapability = try {
        val supported = when (role) {
            AiRole.SPEECH_TO_TEXT -> id in ModelArtifacts.speech
            AiRole.TEXT -> id in ModelArtifacts.text
            AiRole.ROUTING -> id in ModelArtifacts.routing
        }
        val languages = AiCatalog.engine(id)?.languages.orEmpty()
        val supportedLanguage = languages.isEmpty() || language.substringBefore('-').substringBefore('_') in languages
        AiReadiness.local(role, id, supported, supportedLanguage,
            packages.states().firstOrNull { it.engineId == id }, native.available(role))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { AiReadiness.blocked(role, id, "capabilityCheckFailed") }
}
