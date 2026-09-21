package brain.studio

/** Только временное представление drag. Подтверждённый порядок хранится в Core. */
internal fun <T> reorderPreview(items: List<T>, order: List<String>?, key: (T) -> String): List<T> {
    if (order == null) return items
    val byId = items.associateBy(key)
    if (byId.size != items.size || order.size != items.size || order.toSet() != byId.keys) return items
    return order.map { byId.getValue(it) }
}

internal fun movedItemKeys(keys: List<String>, id: String, delta: Int): List<String>? {
    if (keys.toSet().size != keys.size || delta !in listOf(-1, 1)) return null
    val from = keys.indexOf(id)
    val to = from + delta
    if (from !in keys.indices || to !in keys.indices) return null
    return keys.toMutableList().apply { add(to, removeAt(from)) }
}

internal fun reorderCommit(start: List<String>?, current: List<String>, preview: List<String>?): List<String>? =
    preview?.takeIf { start == current && it != current && it.size == current.size && it.toSet() == current.toSet() }
