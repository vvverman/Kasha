package brain.runtime.system

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Общий технический адаптер процессов для Desktop и локального Web runtime. */
data class JvmProcessResult(val exitCode: Int, val output: String)

interface JvmProcessGateway {
    fun available(command: String): Boolean
    fun run(command: List<String>, stdin: String? = null): JvmProcessResult
}

object SystemJvmProcessGateway : JvmProcessGateway {
    override fun available(command: String): Boolean = runCatching {
        val path = runCatching { Path.of(command) }.getOrNull()
        if (path != null && path.isAbsolute) return@runCatching Files.isExecutable(path)
        val probe = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            ProcessBuilder("where.exe", command)
        } else {
            ProcessBuilder("sh", "-c", "command -v -- \"\$1\" >/dev/null 2>&1", "sh", command)
        }.redirectErrorStream(true).start()
        probe.inputStream.use { it.readBytes() }
        probe.waitFor() == 0
    }.getOrDefault(false)

    override fun run(command: List<String>, stdin: String?): JvmProcessResult {
        require(command.isNotEmpty())
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            if (stdin != null) writer.write(stdin)
        }
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        return JvmProcessResult(process.waitFor(), output)
    }
}
