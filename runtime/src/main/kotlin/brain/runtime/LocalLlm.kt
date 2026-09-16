package brain.runtime

import brain.domain.LocalModelText
import java.nio.file.Files
import java.nio.file.Path

/** Единственный платформенный адаптер текстовой модели для текущего локального веба. */
class LocalLlm(private val cli: String, private val model: String, private val root: Path, private val runner: CommandRunner) {
    suspend fun generate(prompt: String, schema: String, tokens: Int): String {
        // Личный текст не попадает в список аргументов, видимый через ps/диспетчер процессов.
        val directory = Files.createTempDirectory(root, ".model-request-")
        val file = directory.resolve("prompt.txt")
        val schemaFile = directory.resolve("schema.json")
        try {
            Files.writeString(file, prompt)
            // JSON не проходит через Windows argv: кавычки схемы должны сохраниться дословно.
            Files.writeString(schemaFile, schema)
            // В completion --log-disable подавляет и генерируемый ответ. Служебный stderr уже отделён runner.
            val result = LocalModelText.jsonPayload(runner.run(listOf(cli, "-m", model,
                "--jinja", "--single-turn", "--reasoning", "off", "--no-display-prompt", "--simple-io",
                "--no-escape", "--file", file.toString(), "--json-schema-file", schemaFile.toString(),
                "-n", tokens.toString(), "-c", "8192", "--temp", "0", "--seed", "0", "-t", "4"), 900))
            require(result.isNotBlank()) { "Локальная модель вернула пустой ответ. Проверьте совместимость движка" }
            return result
        } finally { directory.toFile().deleteRecursively() }
    }
}
