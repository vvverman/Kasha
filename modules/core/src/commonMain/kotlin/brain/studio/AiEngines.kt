package brain.studio

import brain.model.Project
import kotlinx.serialization.Serializable

/** AI-возможности продукта. Core знает роли, но не конкретные модели и провайдеров. */
@Serializable
enum class AiRole { SPEECH_TO_TEXT, TEXT, ROUTING }

@Serializable
enum class AiLocality { LOCAL, CLOUD, NATIVE }

/** Какие пользовательские данные потенциально покидают устройство. */
@Serializable
enum class AiDataKind { AUDIO, NOTE_TEXT, PROJECT_TITLES, PROJECT_INSTRUCTIONS }

/** Метаданные движка. Конкретный каталог живёт вне kashaCore. */
@Serializable
data class AiEngineDescriptor(
    val id: String,
    val name: String,
    val provider: String,
    val roles: Set<AiRole>,
    val locality: AiLocality,
    val version: String = "",
    val approximateSizeMb: Int? = null,
    val languages: List<String> = emptyList(),
    val defaultInstalled: Boolean = false,
    val installable: Boolean = false,
    val description: String = "",
) {
    fun supports(role: AiRole): Boolean = role in roles
    val isExternal: Boolean get() = locality == AiLocality.CLOUD
}

/**
 * Выбор движков хранится в Core как opaque id. Core намеренно не знает,
 * какой конкретно Whisper/Qwen/провайдер скрывается за id по умолчанию.
 */
@Serializable
data class AiSelection(
    val speechToText: String = DEFAULT_STT,
    val text: String = DEFAULT_TEXT,
    val routing: String = DEFAULT_ROUTING,
) {
    companion object {
        const val DEFAULT_STT = "local.default.stt"
        const val DEFAULT_TEXT = "local.default.text"
        const val DEFAULT_ROUTING = DEFAULT_TEXT
    }

    fun engineId(role: AiRole): String = when (role) {
        AiRole.SPEECH_TO_TEXT -> speechToText
        AiRole.TEXT -> text
        AiRole.ROUTING -> routing
    }

    fun with(role: AiRole, engineId: String): AiSelection = when (role) {
        AiRole.SPEECH_TO_TEXT -> copy(speechToText = engineId)
        AiRole.TEXT -> copy(text = engineId)
        AiRole.ROUTING -> copy(routing = engineId)
    }

    /** Конкретную role compatibility проверяет внешний каталог; Core проверяет только форму state. */
    fun validated(): AiSelection {
        AiRole.entries.forEach { role ->
            val id = engineId(role).trim()
            require(id.isNotEmpty() && id.length <= 200) { "Invalid AI engine id for $role" }
        }
        return this
    }
}

@Serializable
data class CloudProviderDescriptor(
    val id: String,
    val name: String,
    val roles: Set<AiRole>,
    val endpointRequired: Boolean = false,
    val description: String = "",
)

/** API key здесь намеренно отсутствует. */
@Serializable
data class CloudAiConnection(
    val providerId: String,
    val modelIds: Map<AiRole, String> = emptyMap(),
    val endpoint: String? = null,
    val enabled: Boolean = false,
    val privacyConsentVersion: Int = 0,
) {
    fun modelFor(role: AiRole): String? = modelIds[role]?.trim()?.takeIf { it.isNotEmpty() }
    val roles: Set<AiRole> get() = modelIds.filterValues { it.isNotBlank() }.keys
}

@Serializable
data class AiPackageState(
    val engineId: String,
    val installed: Boolean,
    val downloading: Boolean = false,
    val progress: Float? = null,
    val error: String? = null,
)

interface AiPackageGateway {
    val available: Boolean
    suspend fun states(): List<AiPackageState>
    suspend fun install(engineId: String)
    suspend fun remove(engineId: String)
}

object NoopAiPackageGateway : AiPackageGateway {
    override val available = false
    override suspend fun states(): List<AiPackageState> = emptyList()
    override suspend fun install(engineId: String) = error("AI package installation is unavailable on this platform")
    override suspend fun remove(engineId: String) = error("AI package installation is unavailable on this platform")
}

/**
 * Platform gateway облачных подключений. Реализация обязана хранить API key
 * в защищённом системном хранилище. Core получает только безопасные метаданные.
 */
interface CloudAiGateway {
    val available: Boolean
    suspend fun connections(): List<CloudAiConnection>
    suspend fun save(connection: CloudAiConnection, apiKey: String?)
    suspend fun remove(providerId: String)
    suspend fun test(connection: CloudAiConnection, apiKey: String?): Boolean
}

object NoopCloudAiGateway : CloudAiGateway {
    override val available = false
    override suspend fun connections(): List<CloudAiConnection> = emptyList()
    override suspend fun save(connection: CloudAiConnection, apiKey: String?) = error("Cloud AI is unavailable on this platform")
    override suspend fun remove(providerId: String) = Unit
    override suspend fun test(connection: CloudAiConnection, apiKey: String?): Boolean = false
}

interface SpeechToTextEngine {
    val descriptor: AiEngineDescriptor
    suspend fun transcribe(file: String, language: String): String
}

interface TextProcessingEngine {
    val descriptor: AiEngineDescriptor
    suspend fun title(text: String, language: String): String
    suspend fun tidy(text: String, language: String): String
}

interface RoutingEngine {
    val descriptor: AiEngineDescriptor
    suspend fun rank(text: String, projects: List<Project>, language: String): Map<String, Int>
}

class CompositeIntelligence(
    private val speech: SpeechToTextEngine,
    private val text: TextProcessingEngine,
    private val routing: RoutingEngine,
    override val simulated: Boolean = false,
) : Intelligence {
    override suspend fun transcribe(file: String, language: String, example: String): String = speech.transcribe(file, language)
    override suspend fun title(text: String, language: String): String = this.text.title(text, language)
    override suspend fun tidy(text: String, language: String): String = this.text.tidy(text, language)
    override suspend fun rank(text: String, projects: List<Project>, language: String): Map<String, Int> = routing.rank(text, projects, language)
}

object AiPrivacy {
    const val CONSENT_VERSION = 1

    fun dataFor(role: AiRole): Set<AiDataKind> = when (role) {
        AiRole.SPEECH_TO_TEXT -> setOf(AiDataKind.AUDIO)
        AiRole.TEXT -> setOf(AiDataKind.NOTE_TEXT)
        AiRole.ROUTING -> setOf(AiDataKind.NOTE_TEXT, AiDataKind.PROJECT_TITLES, AiDataKind.PROJECT_INSTRUCTIONS)
    }

    fun dataFor(roles: Set<AiRole>): Set<AiDataKind> = roles.flatMap(::dataFor).toSet()
}
