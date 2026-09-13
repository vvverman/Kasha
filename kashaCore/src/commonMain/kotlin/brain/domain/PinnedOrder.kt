package brain.domain

/**
 * Обновляет только порядок закреплённых заметок внутри одного проекта.
 * Полный manualOrder заметок не меняется, чтобы unpin возвращал заметку
 * в прежнюю ручную позицию.
 */
fun BrainData.orderNotePins(projectId: String, ids: List<String>): BrainData {
    require(projects.any { it.id == projectId }) { "Проект не найден" }
    val pinned = notes
        .filter { it.projectId == projectId && it.pinned }
        .map { it.id }
        .toSet()
    require(ids.size == pinned.size && ids.toSet() == pinned) {
        "Порядок должен включать все закреплённые заметки проекта ровно один раз"
    }

    val orders = ids.withIndex().associate { it.value to it.index }
    return copy(notes = notes.map { note ->
        if (note.projectId == projectId) {
            orders[note.id]?.let { note.copy(pinOrder = it) } ?: note
        } else {
            note
        }
    })
}
