package brain.runtime

import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

fun interface CommandRunner {
    suspend fun run(command: List<String>, timeoutSeconds: Long): String
}

/** Без shell, раздельные stdout/stderr, ограниченные вывод и время, отмена дочерних процессов. */
class JvmCommandRunner : CommandRunner {
    override suspend fun run(command: List<String>, timeoutSeconds: Long): String = withContext(Dispatchers.IO) {
        val dir = Files.createTempDirectory("kasha-command-")
        val out = dir.resolve("stdout"); val err = dir.resolve("stderr")
        var process: Process? = null
        try {
            val builder = ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile())
            // Не наследуем скрытые RPC/URL-настройки llama.cpp из окружения компьютера.
            builder.environment().keys.removeIf { it.startsWith("LLAMA_ARG_") }
            builder.environment()["HF_HUB_OFFLINE"] = "1"
            process = builder.start()
            process.outputStream.close()
            val started = TimeSource.Monotonic.markNow()
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                ensureActive()
                require(started.elapsedNow() < timeoutSeconds.seconds) { "Истекло время локальной обработки; источник сохранён" }
                require(Files.size(out) < 8 * 1024 * 1024 && Files.size(err) < 8 * 1024 * 1024) { "Слишком большой вывод локальной модели" }
            }
            ensureActive()
            require(process.exitValue() == 0) { "${java.nio.file.Path.of(command.first()).fileName} завершился с кодом ${process.exitValue()}. Проверьте модель и параметры запуска" }
            require(Files.size(out) < 8 * 1024 * 1024) { "Слишком большой ответ локальной модели" }
            Files.readString(out)
        } finally {
            process?.let { p -> if (p.isAlive) { p.descendants().forEach { it.destroyForcibly() }; p.destroyForcibly(); p.waitFor(5, TimeUnit.SECONDS) } }
            dir.toFile().deleteRecursively()
        }
    }
}
