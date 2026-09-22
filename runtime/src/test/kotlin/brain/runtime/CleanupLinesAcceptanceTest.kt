package brain.runtime

import brain.ai.LocalTextRoles
import brain.ai.ModelArtifacts
import brain.domain.TranscriptCleanup
import brain.studio.AiSelection
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

/** Настоящий закреплённый GGUF; запускается только явно в отдельном проверочном workflow. */
class CleanupLinesAcceptanceTest {
    private fun checkCase(name: String, source: String, verify: (String) -> Unit): Unit = runBlocking {
        assumeTrue(System.getenv("KASHA_CLEANUP_ACCEPTANCE") == "1", "Real-model acceptance was not requested")
        check(System.getProperty("os.name").contains("Mac")) { "This diagnostic requires sandbox-exec" }
        val bundle = Path.of(checkNotNull(System.getenv("KASHA_CLEANUP_BUNDLE")))
        val artifact = ModelArtifacts.packages.getValue(AiSelection().text)
        val model = bundle.resolve("models").resolve(artifact.fileName)
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(model).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        assertEquals(artifact.sha256, java.util.HexFormat.of().formatHex(digest.digest()))
        val output = Path.of(checkNotNull(System.getenv("KASHA_CLEANUP_OUT")))
        Files.createDirectories(output)
        val temporary = Files.createTempDirectory("kasha-cleanup-lines-")
        val requests = mutableListOf<JsonObject>()
        var result: String? = null
        var failure: String? = null
        val started = System.nanoTime()
        try {
            val realRunner = JvmCommandRunner()
            val runner = CommandRunner { args, seconds ->
                realRunner.run(listOf("/usr/bin/sandbox-exec", "-p", "(version 1)(allow default)(deny network*)") +
                    args + listOf("--n-gpu-layers", "0", "--device", "none"), seconds)
            }
            val llm = LocalLlm(bundle.resolve("bin/llama-completion").toString(), model.toString(), temporary, runner)
            val roles = LocalTextRoles { role, prompt, schema, tokens ->
                val raw = llm.generate(prompt, schema, tokens)
                requests += buildJsonObject { put("role", role.name); put("prompt", prompt); put("raw", raw) }
                raw
            }
            val cleaned = roles.tidy(source)
            result = cleaned
            verify(cleaned)
        } catch (error: Throwable) {
            failure = error.toString()
            throw error
        } finally {
            temporary.toFile().deleteRecursively()
            val report = buildJsonObject {
                put("source", source); put("output", result); put("failure", failure)
                put("passed", failure == null && result != null)
                put("syntheticInputs", true); put("inferenceSubprocessNetworkBlocked", true)
                put("modelSha256", artifact.sha256); put("kashaSha", System.getenv("GITHUB_SHA"))
                put("seconds", (System.nanoTime() - started) / 1e9)
                put("parts", JsonArray(TranscriptCleanup.parts(source).map {
                    buildJsonObject { put("source", it.source); put("input", it.input); put("separator", it.separator) }
                }))
                put("requests", JsonArray(requests))
            }
            Files.writeString(output.resolve("$name.json"), Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report))
        }
        Unit
    }

    @Test fun fullRussianNote() = checkCase("ru-note", "В проекте приложения нужно исправить запись голоса. Добавить кнопку паузы и проверить сохранение заметок. Старый текст удалять нельзя.") {
        for (word in listOf("проект", "приложен", "голос", "пауз", "замет", "нельзя")) assertTrue(it.contains(word, true), it)
    }
    @Test fun correctedRussianDay() = checkCase("ru-day", "Эээ, встреча завтра, нет, в среду в три часа.") {
        assertTrue(it.contains("встреча", true) && it.contains("среду", true) && it.contains("три", true), it)
        assertFalse(it.contains("завтра", true), it)
    }
    @Test fun correctedAmountAndProhibition() = checkCase("ru-number", "Так, Марина получит 2400 рублей, нет, 2800 рублей. Отчёт удалять нельзя.") {
        assertTrue(it.contains("Марина") && it.contains("2800") && it.contains("рубл") && it.contains("нельзя"), it)
        assertFalse(it.contains("2400"), it)
    }
    @Test fun listAndAllFollowingSentences() = checkCase("ru-list", "Ну, мне нужно купить молоко, молоко и хлеб. Потом зайти к Антону. Ключи оставь дома.") {
        for (word in listOf("молоко", "хлеб", "Антону", "Ключи", "дома")) assertTrue(it.contains(word, true), it)
    }
    @Test fun questionRemainsQuestion() = checkCase("ru-question", "Эээ, почему Ирина не пришла на встречу? Я не знаю ответа.") {
        assertTrue(it.contains("почему", true) && it.contains("?") && it.contains("не знаю", true), it)
        assertFalse(it.contains("потому", true), it)
    }
    @Test fun englishCorrectionKeepsDigitsAndProhibition() = checkCase("en-day", "Um, we meet on Monday, no, on Friday at 3. Do not delete the old notes.") {
        assertTrue(it.contains("Friday") && it.contains("3") && it.contains("not delete", true) && it.contains("notes", true), it)
        assertFalse(it.contains("Monday"), it)
    }
    @Test fun germanNamesAndNumbers() = checkCase("de-identity", "Anna hat 12 Notizen nicht gelöscht.") {
        assertTrue(it.contains("Anna") && it.contains("12") && it.contains("nicht") && it.contains("Notizen"), it)
    }
    @Test fun ukrainianNamesAndNumbers() = checkCase("uk-identity", "Олена не видаляла 12 записів.") {
        assertTrue(it.contains("Олена") && it.contains("12") && it.contains("не") && it.contains("записів"), it)
    }
}
