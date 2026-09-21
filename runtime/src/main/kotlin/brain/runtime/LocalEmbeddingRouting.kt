package brain.runtime

import brain.model.Project
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToInt

/** Узкий локальный routing: F2LLM embeddings + cosine, без генеративной LLM. */
class LocalEmbeddingRouting(
    private val cli: String,
    private val model: Path,
    private val root: Path,
    private val runner: CommandRunner,
) {
    suspend fun rank(text: String, projects: List<Project>): Map<String, Int> {
        if (projects.isEmpty()) return emptyMap()
        val source = embedding(text)
        return projects.associate { project ->
            val document = buildString {
                append(project.title.trim())
                project.description.trim().takeIf { it.isNotEmpty() }?.let { append("\n"); append(it) }
                project.instruction.trim().takeIf { it.isNotEmpty() }?.let { append("\n"); append(it) }
            }
            project.id to cosineScore(source, embedding(document))
        }
    }

    private suspend fun embedding(text: String): DoubleArray {
        require(text.isNotBlank()) { "emptyText" }
        val directory = Files.createTempDirectory(root, ".embedding-request-")
        val prompt = directory.resolve("input.txt")
        try {
            Files.writeString(prompt, text)
            val raw = runner.run(
                listOf(
                    cli,
                    "-m", model.toString(),
                    "--pooling", "mean",
                    "--embd-normalize", "2",
                    "--embd-output-format", "array",
                    "--log-disable",
                    "--file", prompt.toString(),
                    "-t", "4",
                ),
                300,
            ).trim()
            val rootJson = Json.parseToJsonElement(raw).jsonArray
            val vector = rootJson.firstOrNull()?.jsonArray
                ?: error("Embedding-модель вернула пустой вектор")
            require(vector.isNotEmpty()) { "Embedding-модель вернула пустой вектор" }
            return DoubleArray(vector.size) { index -> vector[index].jsonPrimitive.double }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun cosineScore(a: DoubleArray, b: DoubleArray): Int {
        require(a.size == b.size && a.isNotEmpty()) { "Некорректный embedding" }
        // --embd-normalize 2 даёт L2-normalized vectors, поэтому dot == cosine.
        var dot = 0.0
        for (i in a.indices) dot += a[i] * b[i]
        return (dot.coerceIn(0.0, 1.0) * 4.0).roundToInt().coerceIn(0, 4)
    }
}
