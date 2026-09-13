package brain.desktop

import brain.runtime.CommandRunner
import brain.runtime.JvmCommandRunner
import java.nio.file.Path

/** CI не имеет полноценного Metal GPU. Выбор CPU меняет только устройство, не веса и не результат-заглушку. */
class DesktopInferenceRunner(
    private val cpuOnly: Boolean = false,
    private val delegate: CommandRunner = JvmCommandRunner(),
) : CommandRunner {
    override suspend fun run(command: List<String>, timeoutSeconds: Long): String {
        require(command.isNotEmpty())
        val name = Path.of(command.first()).fileName.toString()
        val flags = if (!cpuOnly) emptyList() else when (name) {
            "whisper-cli" -> listOf("--no-gpu")
            "llama-completion" -> listOf("--n-gpu-layers", "0", "--device", "none")
            else -> emptyList()
        }
        if (cpuOnly) println("Самопроверка: запуск $name на CPU")
        return delegate.run(command + flags, timeoutSeconds)
    }
}
