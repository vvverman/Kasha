package brain.studio

import brain.ai.KashaAiCatalog

/**
 * Совместимый facade каталога для shared UI/runtime.
 * Файл находится в модуле aiCatalog, а не в kashaCore.
 */
object AiCatalog {
    const val DEFAULT_STT = AiSelection.DEFAULT_STT
    const val DEFAULT_TEXT = AiSelection.DEFAULT_TEXT
    const val DEFAULT_ROUTING = AiSelection.DEFAULT_ROUTING

    val engines: List<AiEngineDescriptor> get() = KashaAiCatalog.engines
    val cloudProviders: List<CloudProviderDescriptor> get() = KashaAiCatalog.cloudProviders

    fun engine(id: String): AiEngineDescriptor? = KashaAiCatalog.engine(id)
    fun enginesFor(role: AiRole): List<AiEngineDescriptor> = KashaAiCatalog.enginesFor(role)
    fun provider(id: String): CloudProviderDescriptor? = KashaAiCatalog.provider(id)
    fun cloudEngineId(providerId: String, role: AiRole): String = KashaAiCatalog.cloudEngineId(providerId, role)
    fun cloudProviderId(engineId: String): String? = KashaAiCatalog.cloudProviderId(engineId)
    fun cloudRole(engineId: String): AiRole? = KashaAiCatalog.cloudRole(engineId)
    fun supportsSelection(engineId: String, role: AiRole): Boolean = KashaAiCatalog.supportsSelection(engineId, role)
    fun validateSelection(selection: AiSelection): AiSelection = KashaAiCatalog.validateSelection(selection)
    fun selectedDescriptor(engineId: String): AiEngineDescriptor? = KashaAiCatalog.selectedDescriptor(engineId)
    fun connectedCloudChoices(role: AiRole, connections: List<CloudAiConnection>): List<AiEngineDescriptor> =
        KashaAiCatalog.connectedCloudChoices(role, connections)
}
