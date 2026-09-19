package brain.ai

import brain.domain.NoteText
import brain.model.Project

/** Одна существующая базовая реализация для мобильных адаптеров; это не языковая модель. */
object BuiltInText {
    fun title(text: String, language: String): String = NoteText.title(text)

    fun tidy(text: String, language: String): String {
        var result = text.trim()
            .replace(Regex("[ \\t]{2,}"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
        val replacements = mapOf(
            "какая-то фигня" to "проблема",
            "эта хрень" to "эта функция",
            "фигня" to "проблема",
            "хрень" to "проблема",
            "пиздец" to "серьёзная проблема",
            "crap" to "problem",
        )
        replacements.forEach { (from, to) ->
            result = result.replace(
                Regex("(?i)(?<![\\p{L}])${Regex.escape(from)}(?![\\p{L}])"),
                to,
            )
        }
        return result
    }

    fun rank(text: String, projects: List<Project>, language: String): Map<String, Int> {
        fun words(value: String): Set<String> = Regex("[\\p{L}\\p{N}]{3,}")
            .findAll(value.lowercase())
            .map { it.value.take(7) }
            .toSet()
        val source = words(text)
        return projects.associate { project ->
            val titleHits = words(project.title).count { it in source }
            val detailHits = words(project.description + " " + project.instruction).count { it in source }
            project.id to (titleHits * 3 + detailHits).coerceIn(0, 4)
        }
    }

}
