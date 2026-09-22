package brain.runtime

import brain.ai.ModelArtifacts
import brain.model.Project
import brain.studio.AiSelection
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

class EmbeddingCliAcceptanceTest {
    @Test fun realPinnedModelReadsCompleteMultilineInputs(): Unit = runBlocking {
        assumeTrue(System.getenv("KASHA_CLEANUP_ACCEPTANCE") == "1", "Настоящая модель явно не запрошена")
        check(System.getProperty("os.name").contains("Mac"))
        val bundle = Path.of(checkNotNull(System.getenv("KASHA_CLEANUP_BUNDLE")))
        val artifact = ModelArtifacts.packages.getValue(AiSelection().routing)
        val model = bundle.resolve("models").resolve(artifact.fileName)
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(model).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        assertEquals(artifact.sha256, java.util.HexFormat.of().formatHex(digest.digest()))
        val temporary = Files.createTempDirectory("kasha-real-embeddings-")
        val evidence = Path.of(checkNotNull(System.getenv("KASHA_CLEANUP_OUT")))
        Files.createDirectories(evidence)
        val responses = mutableListOf<JsonObject>()
        var scores: Map<String, Int>? = null
        var failure: String? = null
        try {
            val native = JvmCommandRunner()
            val runner = CommandRunner { args, seconds ->
                val input = Files.readString(Path.of(args[args.indexOf("--file") + 1]))
                val raw = native.run(listOf("/usr/bin/sandbox-exec", "-p", "(version 1)(allow default)(deny network*)") +
                    args + listOf("--n-gpu-layers", "0", "--device", "none"), seconds)
                val vectors = Json.parseToJsonElement(raw).jsonArray
                responses += buildJsonObject { put("syntheticInput", input); put("raw", raw); put("vectorCount", vectors.size) }
                raw
            }
            val projects = listOf(
                Project("app", "Приложение голосовых заметок", "Запись голоса\nСохранение заметок", "Доработки записи и паузы"),
                Project("cooking", "Кулинарные рецепты", "Супы\nДесерты", "Приготовление еды"),
            )
            scores = LocalEmbeddingRouting(bundle.resolve("bin/llama-embedding").toString(), model, temporary, runner)
                .rank("Исправить запись голоса в приложении.\nДобавить кнопку паузы и проверить сохранение заметок.", projects)
            assertEquals(projects.map { it.id }.toSet(), scores.keys)
            assertTrue(scores.values.all { it in 0..4 })
            assertTrue(scores.getValue("app") > scores.getValue("cooking"), scores.toString())
            assertEquals(3, responses.size)
            assertTrue(responses.all { it["vectorCount"]!!.jsonPrimitive.int == 1 })
            Files.list(temporary).use { assertEquals(0L, it.count()) }
        } catch (error: Throwable) { failure = error.toString(); throw error }
        finally {
            temporary.toFile().deleteRecursively()
            val report = buildJsonObject {
                put("kashaSha", System.getenv("GITHUB_SHA")); put("modelSha256", artifact.sha256)
                put("passed", failure == null && scores != null); put("failure", failure)
                put("inferenceSubprocessNetworkBlocked", true)
                put("scores", buildJsonObject { scores?.forEach { (id, value) -> put(id, value) } })
                put("responses", JsonArray(responses))
            }
            Files.writeString(evidence.resolve("routing-cli.json"), Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report))
        }
    }
}
