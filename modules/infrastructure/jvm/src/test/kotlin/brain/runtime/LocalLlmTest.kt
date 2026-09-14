package brain.runtime

import brain.domain.LocalModelText
import brain.model.RuntimeStatus
import kotlinx.coroutines.*
import java.nio.file.*
import kotlin.test.*

class LocalLlmTest {
    @Test fun promptIsPrivateFileNotProcessArgument() = runBlocking {
        val root = Files.createTempDirectory("brain-llm")
        var file: Path? = null
        try {
            val prompt = "Секретная заметка: не удалять 15 записей"
            val runner = CommandRunner { command, _ ->
                assertFalse(command.any { it.contains(prompt) })
                assertFalse(command.contains("--log-disable"))
                file = Path.of(command[command.indexOf("--file") + 1])
                assertEquals(prompt, Files.readString(file))
                assertTrue(command.containsAll(listOf("--json-schema", "--no-escape", "--single-turn", "--reasoning", "off")))
                "{\"relevance\":4}\n[end of text]"
            }
            assertEquals("{\"relevance\":4}", LocalLlm("llama", "model", root, runner).generate(prompt, LocalModelText.RANK_SCHEMA, 80))
            assertFalse(Files.exists(file))
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun failedModelCleansTemporaryPrompt() = runBlocking {
        val root = Files.createTempDirectory("brain-llm-fail")
        try {
            assertFails { LocalLlm("llama", "model", root, CommandRunner { _, _ -> error("Нет памяти") }).generate("Текст", "{}", 10) }
            Files.list(root).use { assertEquals(0L, it.count()) }
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun emptyAnswerIsExplicitFailure() = runBlocking {
        val root = Files.createTempDirectory("brain-empty-answer")
        try {
            val error = assertFails { LocalLlm("llama", "model", root, CommandRunner { _, _ -> "\n" }).generate("Текст", "{}", 10) }
            assertTrue(error.message.orEmpty().contains("пустой ответ"))
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun cancelledModelCleansTemporaryPrompt() = runBlocking<Unit> {
        val root = Files.createTempDirectory("brain-llm-cancel")
        try {
            assertFailsWith<CancellationException> {
                LocalLlm("llama", "model", root, CommandRunner { _, _ -> throw CancellationException("Отмена") }).generate("Текст", "{}", 10)
            }
            Files.list(root).use { assertEquals(0L, it.count()) }
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun nonexistentCliIsNotReportedConfigured() {
        val root = Files.createTempDirectory("brain-config")
        try {
            val model = root.resolve("model.bin"); Files.write(model, byteArrayOf(1))
            val service = LocalProcessing(FileBrainStore(root) { RuntimeStatus() }, mapOf(
                "KASHA_WHISPER_CLI" to root.resolve("absent").toString(), "KASHA_WHISPER_MODEL" to model.toString()))
            assertFalse(service.status().whisperConfigured)
        } finally { root.toFile().deleteRecursively() }
    }
}
