package brain.runtime.ai

import brain.ai.KashaAiCatalog
import brain.runtime.CommandRunner
import brain.runtime.JvmCommandRunner
import brain.studio.*
import kotlinx.coroutines.CancellationException

/** Общая реализация Desktop и локального Web runtime, без сетевой проверки провайдера. */
class JvmAiExecutionCapabilities(
    private val packages: AiPackageGateway,
    private val cloud: CloudAiGateway,
    private val runtimeReady: suspend (AiRole) -> Boolean,
    private val language: suspend () -> String,
) : AiExecutionCapabilityGateway {
    override suspend fun roles(selection: AiSelection): List<AiRoleCapability> {
        var states: Map<String, AiPackageState>? = null
        var locale: String? = null
        val runtime = mutableMapOf<AiRole, Boolean>()
        return AiRole.entries.map { role ->
            val id = selection.engineId(role)
            try {
                val canonicalId = KashaAiCatalog.canonicalEngineId(id, role)
                val descriptor = AiCatalog.selectedDescriptor(canonicalId)
                when {
                    descriptor == null || !KashaAiCatalog.supportsSelection(id, role) ->
                        AiReadiness.blocked(role, id, "platformUnavailable")
                    descriptor.locality == AiLocality.CLOUD -> {
                        val provider = AiCatalog.cloudProviderId(id)
                        if (cloud is JvmCloudAiGateway && provider != null) cloud.capability(role, id, provider)
                        else AiReadiness.blocked(role, id, "platformUnavailable")
                    }
                    descriptor.locality == AiLocality.LOCAL -> {
                        if (states == null) states = packages.states().associateBy { it.engineId }
                        if (locale == null) locale = language()
                        val state = states?.get(descriptor.id)
                        val supported = packages.available && (descriptor.id in JvmModelManifest.packages || state?.installed == true)
                        val runnable = if (!supported) false else runtime[role]
                            ?: runtimeReady(role).also { runtime[role] = it }
                        val supportedLanguage = descriptor.languages.isEmpty() || (locale ?: "") in descriptor.languages
                        AiReadiness.local(role, id, supported, supportedLanguage, state, runnable)
                    }
                    else -> AiReadiness.blocked(role, id, "platformUnavailable")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { AiReadiness.blocked(role, id, "capabilityCheckFailed") }
        }
    }
}

/** Проверяет запуск и зависимости; не загружает модель и не компилирует GPU-ядра. */
class JvmAiRuntimeProbe(
    private val environment: Map<String, String>,
    private val runner: CommandRunner = JvmCommandRunner(mapOf("GGML_METAL_DEVICES" to "0")),
) {
    suspend fun available(role: AiRole): Boolean {
        val executableKey = when (role) {
            AiRole.SPEECH_TO_TEXT -> "KASHA_WHISPER_CLI"
            AiRole.TEXT -> "KASHA_LLAMA_CLI"
            AiRole.ROUTING -> "KASHA_EMBEDDING_CLI"
        }
        val executable = environment[executableKey]?.takeIf(String::isNotBlank) ?: return false
        return try {
            // Закреплённый llama.cpp перечисляет Metal до разбора CLI; окружение
            // выше отключает только это перечисление в отдельном процессе probe.
            runner.run(listOf(executable, "--version"), 5)
            if (role == AiRole.SPEECH_TO_TEXT)
                runner.run(listOf(environment["KASHA_FFMPEG"] ?: "ffmpeg", "-version"), 5)
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }
}
