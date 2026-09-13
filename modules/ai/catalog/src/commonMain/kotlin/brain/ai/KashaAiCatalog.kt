package brain.ai

import brain.studio.*

/**
 * Общий каталог конкретных AI-движков Kasha.
 * Он переиспользуется UI/runtime, но намеренно находится вне kashaCore.
 */
object KashaAiCatalog {
    private const val CLOUD_PREFIX = "cloud:"
    private val legacyIds = mapOf(
        "local.whisper.small" to AiSelection.DEFAULT_STT,
        "local.qwen3.4b" to AiSelection.DEFAULT_TEXT,
    )

    val engines: List<AiEngineDescriptor> = listOf(
        AiEngineDescriptor(
            id = AiSelection.DEFAULT_STT,
            name = "Whisper Small",
            provider = "OpenAI / whisper.cpp",
            roles = setOf(AiRole.SPEECH_TO_TEXT),
            locality = AiLocality.LOCAL,
            version = "small",
            approximateSizeMb = 500,
            languages = Languages.codes,
            defaultInstalled = true,
            installable = true,
            description = "Базовая локальная транскрибация",
        ),
        AiEngineDescriptor(
            id = "local.whisper.medium",
            name = "Whisper Medium",
            provider = "OpenAI / whisper.cpp",
            roles = setOf(AiRole.SPEECH_TO_TEXT),
            locality = AiLocality.LOCAL,
            version = "medium",
            approximateSizeMb = 1500,
            languages = Languages.codes,
            installable = true,
            description = "Точнее, но тяжелее",
        ),
        AiEngineDescriptor(
            id = "local.whisper.large-v3",
            name = "Whisper Large v3",
            provider = "OpenAI / whisper.cpp",
            roles = setOf(AiRole.SPEECH_TO_TEXT),
            locality = AiLocality.LOCAL,
            version = "large-v3",
            approximateSizeMb = 3100,
            languages = Languages.codes,
            installable = true,
            description = "Максимальная локальная точность",
        ),
        AiEngineDescriptor(
            id = AiSelection.DEFAULT_TEXT,
            name = "Qwen 4B",
            provider = "Qwen / llama.cpp",
            roles = setOf(AiRole.TEXT, AiRole.ROUTING),
            locality = AiLocality.LOCAL,
            version = "4B Q4",
            approximateSizeMb = 2500,
            languages = Languages.codes,
            defaultInstalled = true,
            installable = true,
            description = "Базовая локальная модель Kasha",
        ),
        AiEngineDescriptor(
            id = "local.gemma.4b",
            name = "Gemma 4B",
            provider = "Google / llama.cpp",
            roles = setOf(AiRole.TEXT, AiRole.ROUTING),
            locality = AiLocality.LOCAL,
            version = "4B Q4",
            approximateSizeMb = 3000,
            languages = Languages.codes,
            installable = false,
            description = "Доступна после принятия условий модели Google",
        ),
        AiEngineDescriptor(
            id = "local.qwen.8b",
            name = "Qwen 8B",
            provider = "Qwen / llama.cpp",
            roles = setOf(AiRole.TEXT, AiRole.ROUTING),
            locality = AiLocality.LOCAL,
            version = "8B Q4",
            approximateSizeMb = 5000,
            languages = Languages.codes,
            installable = true,
            description = "Более тяжёлая локальная модель",
        ),
    )

    val cloudProviders: List<CloudProviderDescriptor> = listOf(
        CloudProviderDescriptor("openai", "OpenAI", setOf(AiRole.SPEECH_TO_TEXT, AiRole.TEXT, AiRole.ROUTING), description = "OpenAI API"),
        CloudProviderDescriptor("anthropic", "Anthropic Claude", setOf(AiRole.TEXT, AiRole.ROUTING), description = "Anthropic API"),
        CloudProviderDescriptor("gemini", "Google Gemini", setOf(AiRole.TEXT, AiRole.ROUTING), description = "Google AI API"),
        CloudProviderDescriptor("openrouter", "OpenRouter", setOf(AiRole.TEXT, AiRole.ROUTING), description = "Множество моделей через единый API"),
        CloudProviderDescriptor("openai-compatible", "OpenAI-compatible", setOf(AiRole.TEXT, AiRole.ROUTING), endpointRequired = true, description = "Любой совместимый сервер"),
        CloudProviderDescriptor("custom", "Custom endpoint", AiRole.entries.toSet(), endpointRequired = true, description = "Собственный API-адаптер"),
    )

    fun canonicalEngineId(id: String): String = legacyIds[id] ?: id
    fun engine(id: String): AiEngineDescriptor? = engines.firstOrNull { it.id == canonicalEngineId(id) }
    fun enginesFor(role: AiRole): List<AiEngineDescriptor> = engines.filter { it.supports(role) }
    fun provider(id: String): CloudProviderDescriptor? = cloudProviders.firstOrNull { it.id == id }

    fun cloudEngineId(providerId: String, role: AiRole): String = "$CLOUD_PREFIX$providerId:${role.name}"

    fun cloudProviderId(engineId: String): String? = if (engineId.startsWith(CLOUD_PREFIX)) {
        engineId.removePrefix(CLOUD_PREFIX).substringBefore(':').takeIf { it.isNotBlank() }
    } else null

    fun cloudRole(engineId: String): AiRole? = if (engineId.startsWith(CLOUD_PREFIX)) {
        runCatching { AiRole.valueOf(engineId.substringAfterLast(':')) }.getOrNull()
    } else null

    fun supportsSelection(engineId: String, role: AiRole): Boolean {
        engine(engineId)?.let { return it.supports(role) }
        val provider = cloudProviderId(engineId)?.let(::provider) ?: return false
        return cloudRole(engineId) == role && role in provider.roles
    }

    fun validateSelection(selection: AiSelection): AiSelection {
        selection.validated()
        AiRole.entries.forEach { role ->
            require(supportsSelection(selection.engineId(role), role)) {
                "AI engine ${selection.engineId(role)} does not support $role"
            }
        }
        return selection
    }

    fun selectedDescriptor(engineId: String): AiEngineDescriptor? {
        engine(engineId)?.let { return it }
        val provider = cloudProviderId(engineId)?.let(::provider) ?: return null
        val role = cloudRole(engineId) ?: return null
        if (role !in provider.roles) return null
        return AiEngineDescriptor(
            id = engineId,
            name = provider.name,
            provider = provider.name,
            roles = setOf(role),
            locality = AiLocality.CLOUD,
            description = provider.description,
        )
    }

    fun connectedCloudChoices(role: AiRole, connections: List<CloudAiConnection>): List<AiEngineDescriptor> = connections
        .filter { it.enabled && it.privacyConsentVersion >= AiPrivacy.CONSENT_VERSION && it.modelFor(role) != null }
        .mapNotNull { connection ->
            val provider = provider(connection.providerId) ?: return@mapNotNull null
            val model = connection.modelFor(role) ?: return@mapNotNull null
            if (role !in provider.roles) return@mapNotNull null
            AiEngineDescriptor(
                id = cloudEngineId(connection.providerId, role),
                name = "${provider.name} · $model",
                provider = provider.name,
                roles = setOf(role),
                locality = AiLocality.CLOUD,
                description = provider.description,
            )
        }
}
