package brain.runtime

import brain.ai.EmbeddingProjectRouting
import brain.model.Project
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Тонкий CLI-адаптер; подготовка запросов и cosine общие с нативными платформами. */
class LocalEmbeddingRouting(
    private val cli: String,
    private val model: Path,
    private val root: Path,
    private val runner: CommandRunner,
) {
    suspend fun rank(text: String, projects: List<Project>): Map<String, Int> =
        EmbeddingProjectRouting.rank(text, projects, ::embedding)

    private suspend fun embedding(text: String): FloatArray {
        require(text.isNotBlank()) { "emptyText" }
        val directory = Files.createTempDirectory(root, ".embedding-request-")
        val prompt = directory.resolve("input.txt")
        try {
            Files.writeString(prompt, text)
            // По умолчанию CLI разбивает запрос по \n и возвращает вектор каждой строки.
            // Служебный разделитель отсутствует во всём тексте: запрос/проект — один вектор.
            var separator: String
            do { separator = "KASHA_EMBEDDING_${UUID.randomUUID()}" } while (separator in text)
            val raw = runner.run(
                listOf(
                    cli, "-m", model.toString(),
                    "--pooling", "last", "--embd-normalize", "2",
                    "--embd-output-format", "array", "--embd-separator", separator,
                    // JSON в закреплённом llama.cpp печатается через LOG уровня 0.
                    // --log-disable подавляет и данные, поэтому оставляем машинный вывод.
                    "--verbosity", "0", "--log-colors", "off",
                    "--no-log-prefix", "--no-log-timestamps",
                    "--no-escape", "--file", prompt.toString(), "-t", "4",
                ),
                300,
            ).trim()
            require(raw.isNotEmpty()) { "Embedding-модель вернула пустой ответ" }
            val vectors = Json.parseToJsonElement(raw) as? JsonArray
                ?: error("Embedding-модель вернула неверный формат")
            require(vectors.size == 1) { "Ожидался один embedding для полного текста" }
            val vector = vectors.single() as? JsonArray
                ?: error("Embedding-модель вернула неверный формат вектора")
            require(vector.isNotEmpty()) { "Embedding-модель вернула пустой вектор" }
            val values = FloatArray(vector.size) { index ->
                val component = vector[index] as? JsonPrimitive
                require(component != null && !component.isString) { "Некорректная компонента embedding" }
                val number = component.floatOrNull
                require(number != null && number.isFinite()) { "Некорректная компонента embedding" }
                number
            }
            require(values.any { it != 0f }) { "Embedding-модель вернула нулевой вектор" }
            return values
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
