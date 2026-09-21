package brain.ai

import brain.studio.*

/**
 * Общий каталог конкретных AI-движков Kasha.
 * Он переиспользуется UI/runtime, но намеренно находится вне kashaCore.
 */
object KashaAiCatalog {
    private const val CLOUD_PREFIX = "cloud:"
    private val legacyTextIds = setOf("local.qwen3.4b", "local.qwen.8b")
    private val legacyIds = mapOf(
        "local.whisper.small" to AiSelection.DEFAULT_STT,
    )

    val engines: List<AiEngineDescriptor> = listOf(
        AiEngineDescriptor(
            id = AiSelection.DEFAULT_STT,
            name = "Whisper Large-v3 Turbo Q5",
            provider = "OpenAI / whisper.cpp",
            roles = setOf(AiRole.SPEECH_TO_TEXT),
            locality = AiLocality.LOCAL,
            version = "large-v3-turbo q5_0",
            approximateSizeMb = 574,
            languages = Languages.codes,
            defaultInstalled = true,
            installable = true,
            description = "Основная локальная транскрибация Kasha",
        ),
        AiEngineDescriptor(
            id = AiSelection.DEFAULT_TEXT,
            name = "Transcrib Cleanup 0.6B",
            provider = "NicolaiMTLassen",
            roles = setOf(AiRole.TEXT),
            locality = AiLocality.LOCAL,
            version = "0.6B · 4-bit",
            approximateSizeMb = 347,
            languages = listOf("ru", "en", "de", "uk", "pt", "da"),
            defaultInstalled = false,
            installable = false,
            description = "Узкая модель нормализации транскриптов; без чат-функций",
        ),
        AiEngineDescriptor(
            id = AiSelection.DEFAULT_ROUTING,
            name = "F2LLM-v2 80M",
            provider = "CodeFuse / llama.cpp",
            roles = setOf(AiRole.ROUTING),
            locality = AiLocality.LOCAL,
            version = "80M Q8_0",
            approximateSizeMb = 91,
            languages = Languages.codes,
            defaultInstalled = true,
            installable = true,
            description = "Embedding-модель для подбора проектов",
        ),
    ) + BuiltInAi.engines

    val cloudProviders: List<CloudProviderDescriptor> = listOf(
        CloudProviderDescriptor("openai", "OpenAI", setOf(AiRole.SPEECH_TO_TEXT, AiRole.TEXT, AiRole.ROUTING), description = "OpenAI API"),
        CloudProviderDescriptor("anthropic", "Anthropic Claude", setOf(AiRole.TEXT, AiRole.ROUTING), description = "Anthropic API"),
        CloudProviderDescriptor("gemini", "Google Gemini", setOf(AiRole.TEXT, AiRole.ROUTING), description = "Google AI API"),
        CloudProviderDescriptor("openrouter", "OpenRouter", setOf(AiRole.TEXT, AiRole.ROUTING), description = "Множество моделей через единый API"),
        CloudProviderDescriptor("openai-compatible", "OpenAI-compatible", setOf(AiRole.TEXT, AiRole.ROUTING), endpointRequired = true, description = "Любой совместимый сервер"),
        CloudProviderDescriptor("custom", "Custom endpoint", AiRole.entries.toSet(), endpointRequired = true, description = "Собственный API-адаптер"),
    )

    fun canonicalEngineId(id: String, role: AiRole? = null): String = when {
        id in legacyTextIds && role == AiRole.ROUTING -> AiSelection.DEFAULT_ROUTING
        id in legacyTextIds -> AiSelection.DEFAULT_TEXT
        else -> legacyIds[id] ?: id
    }
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
        engines.firstOrNull { it.id == canonicalEngineId(engineId, role) }?.let { return it.supports(role) }
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
