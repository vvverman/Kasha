package brain.ai

import brain.model.Project
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Общая инфраструктурная логика routing для embedding-моделей. */
object EmbeddingProjectRouting {
    fun query(text: String): String =
        "Instruct: Given a voice note, retrieve the most relevant project for filing it.\nQuery: ${text.trim()}"

    fun document(project: Project): String = buildString {
        append(project.title.trim())
        project.description.trim().takeIf { it.isNotEmpty() }?.let { append("\n"); append(it) }
        project.instruction.trim().takeIf { it.isNotEmpty() }?.let { append("\n"); append(it) }
    }

    fun score(a: FloatArray, b: FloatArray): Int {
        require(a.size == b.size && a.isNotEmpty()) { "Некорректный embedding" }
        var dot = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in a.indices) {
            val av = a[i].toDouble()
            val bv = b[i].toDouble()
            dot += av * bv
            aa += av * av
            bb += bv * bv
        }
        val denom = sqrt(aa) * sqrt(bb)
        val cosine = if (denom > 0.0) dot / denom else 0.0
        return (cosine.coerceIn(0.0, 1.0) * 4.0).roundToInt().coerceIn(0, 4)
    }

    suspend fun rank(
        text: String,
        projects: List<Project>,
        embed: suspend (String) -> FloatArray,
    ): Map<String, Int> {
        if (projects.isEmpty()) return emptyMap()
        val source = embed(query(text))
        return projects.associate { project ->
            project.id to score(source, embed(document(project)))
        }
    }
}
