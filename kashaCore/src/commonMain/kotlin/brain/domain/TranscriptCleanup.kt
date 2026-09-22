package brain.domain

/** Подготовка копии транскрипта: только явные однотипные поправки, без выбора новых фактов. */
object TranscriptCleanup {
    data class Part(val source: String, val input: String, val separator: String)
    private val hesitation = Regex("(?iu)(?:э{2,}|м{3,}|u{2,}m+|um{2,}|uh{2,}|hmm+|erm)")
    fun isHesitation(word: String): Boolean = hesitation.matches(word)

    private val correction = "[,.]\\s*(?:нет(?:,?\\s+(?:точнее|вернее))?|точнее|вернее|no(?:,?\\s+wait)?|actually|rather)(?:\\s*,\\s*|\\s+)"
    private val day = "(?:(?:(?:в|во|на)\\s+)?(?:понедельник|вторник|среду|четверг|пятницу|субботу|воскресенье)|сегодня|завтра|послезавтра|вчера|(?:(?:on|by)\\s+)?(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)|today|tomorrow|yesterday)"
    private val dayCorrection = Regex("(?iu)(?<![\\p{L}\\p{N}_])(?:$day)$correction($day)(?![\\p{L}\\p{N}_])")
    private val numeral = "[0-9]+(?:[.,][0-9]+)?"
    private val numberCorrection = Regex(
        "(?iu)(?<![\\p{L}\\p{N}_:./+\\-])" +
            "((?:(?:в|к|на|at|by)\\s+)?)($numeral)(\\s+[\\p{L}%]+)?" +
            correction +
            "((?:(?:в|к|на|at|by)\\s+)?)($numeral)(\\s+[\\p{L}%]+)?" +
            "(?!(?:[\\p{L}\\p{N}_:/+\\-]|\\.[0-9]))"
    )
    fun temporalFacts(text: String): List<String> = Regex("(?iu)(?<![\\p{L}\\p{N}_])($day)(?![\\p{L}\\p{N}_])")
        .findAll(text).map { it.value.lowercase().replaceFirst(Regex("^(?:в|во|на|on|by)\\s+"), "") }.toList().sorted()

    private val correctionStart = Regex("(?iu)^(?:нет|точнее|вернее|no|actually|rather|sorry)(?![\\p{L}])")

    fun prepare(source: String): String {
        // Цитаты и код не интерпретируются как поправки говорящего.
        if (source.any { it in "\"«»“”„`" } || Regex("(?:^|\\s)'").containsMatchIn(source)) return source.trim()
        var value = source.trim()
        // Не удаляем лексические «так», «ну», actually и т.п. по одному словарю.
        val filler = Regex("(?iu)^(?:э{2,}|м{3,}|u{2,}m+|um{2,}|uh{2,}|hmm+|erm)(?:[,\\s]+)(?=\\S)").find(value)
        if (filler != null) value = value.removeRange(filler.range).trimStart()
        var previous: String
        do {
            previous = value
            value = dayCorrection.replace(value) { it.groupValues[1] }
            value = numberCorrection.replace(value) { match ->
                val beforeUnit = match.groups[3]?.value.orEmpty()
                val afterUnit = match.groups[6]?.value.orEmpty()
                // Не теряем единицу и не угадываем соответствие рублей, часов, процентов и т.п.
                if (beforeUnit.isNotBlank() && !beforeUnit.trim().equals(afterUnit.trim(), ignoreCase = true)) match.value
                else {
                    val prefix = match.groups[4]?.value.orEmpty()
                        .ifEmpty { match.groups[1]?.value.orEmpty() }
                    prefix + match.groups[5]!!.value + afterUnit
                }
            }
        } while (value != previous)
        return value
    }

    private val abbreviations = setOf("т", "д", "е", "г", "ул", "стр", "рис", "руб", "коп", "см", "им", "др", "etc", "mr", "mrs", "ms", "dr", "st", "vs", "prof", "inc", "ltd")

    fun parts(text: String): List<Part> {
        val source = text.trim()
        if (source.isEmpty()) return emptyList()
        val result = mutableListOf<Part>()
        var start = 0
        var index = 0
        var quote: Char? = null
        while (index < source.length) {
            val char = source[index]
            if (quote != null) {
                if (char == quote) quote = null
                index++
                continue
            }
            quote = when (char) { '"' -> '"'; '«' -> '»'; '“', '„' -> '”'; '`' -> '`'; else -> null }
            if (quote != null || !char.isWhitespace()) { index++; continue }
            val whitespaceStart = index
            while (index < source.length && source[index].isWhitespace()) index++
            val separator = source.substring(whitespaceStart, index)
            val preceding = source.getOrNull(whitespaceStart - 1)
            var wordStart = (whitespaceStart - 1).coerceAtLeast(start)
            while (wordStart > start && source[wordStart - 1].isLetterOrDigit()) wordStart--
            val word = source.substring(wordStart, (whitespaceStart - 1).coerceAtLeast(wordStart))
            val paragraph = separator.count { it == '\n' } >= 2
            val sentence = preceding in listOf('.', '!', '?') &&
                !(preceding == '.' && (word.length == 1 || word.lowercase() in abbreviations || (word.isNotEmpty() && word.all { it.isDigit() })))
            if ((paragraph || sentence) && index < source.length &&
                !correctionStart.containsMatchIn(source.substring(index))) {
                val part = source.substring(start, whitespaceStart)
                result += Part(part, prepare(part), separator)
                start = index
            }
        }
        val last = source.substring(start)
        result += Part(last, prepare(last), "")
        return result
    }
}
