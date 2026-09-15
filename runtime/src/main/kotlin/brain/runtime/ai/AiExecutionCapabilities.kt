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
                val descriptor = AiCatalog.selectedDescriptor(id)
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
                        val engineRole = if (role == AiRole.SPEECH_TO_TEXT) role else AiRole.TEXT
                        val runnable = if (!supported) false else runtime[engineRole]
                            ?: runtimeReady(engineRole).also { runtime[engineRole] = it }
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

/** Проверяет запуск программы и её зависимостей, не обрабатывает модель или пользовательские данные. */
class JvmAiRuntimeProbe(
    private val environment: Map<String, String>,
    private val runner: CommandRunner = JvmCommandRunner(),
) {
    suspend fun available(role: AiRole): Boolean {
        val executable = environment[if (role == AiRole.SPEECH_TO_TEXT) "KASHA_WHISPER_CLI" else "KASHA_LLAMA_CLI"]
            ?.takeIf(String::isNotBlank) ?: return false
        return try {
            // У закреплённого llama.cpp --version завершает parser сразу;
            // --help проходит обработчики моделей и не подходит для лёгкого probe.
            runner.run(listOf(executable, "--version"), 5)
            if (role == AiRole.SPEECH_TO_TEXT)
                runner.run(listOf(environment["KASHA_FFMPEG"] ?: "ffmpeg", "-version"), 5)
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }
}
