package brain.desktop

import brain.runtime.CommandRunner
import brain.runtime.JvmCommandRunner

/** Обычный запуск сохраняет GPU. Явная CPU-самопроверка не инициализирует Metal до разбора CLI. */
class DesktopInferenceRunner(
    private val cpuOnly: Boolean = false,
    private val delegate: CommandRunner = JvmCommandRunner(if (cpuOnly) mapOf("GGML_METAL_DEVICES" to "0") else emptyMap()),
) : CommandRunner {
    override suspend fun run(command: List<String>, timeoutSeconds: Long): String {
        if (!cpuOnly) return delegate.run(command, timeoutSeconds)
        val name = java.nio.file.Path.of(command.first()).fileName.toString()
        val options = when (name) {
            "whisper-cli" -> listOf("--no-gpu")
            "llama-completion" -> listOf("--n-gpu-layers", "0", "--device", "none")
            else -> emptyList()
        }
        println("Самопроверка: запуск $name на CPU")
        return delegate.run(command + options, timeoutSeconds)
    }
}
