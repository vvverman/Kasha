package brain.domain

import brain.model.Note
import brain.model.Project
import brain.model.Task

/**
 * Безопасная миграция старых локальных данных после появления manualOrder/updatedAt.
 * Никаких версий сервера нет: старый порядок JSON-массива становится исходным
 * ручным порядком, поэтому пользователь не видит случайную перестановку по ID.
 */
fun BrainData.migrated(): BrainData {
    val projectOrderNeedsRepair = projects.map { it.manualOrder }.toSet().size != projects.size
    val migratedProjects = projects.mapIndexed { index, project ->
        project.copy(
            updatedAt = project.updatedAt.takeIf { it > 0 } ?: project.createdAt,
            manualOrder = if (projectOrderNeedsRepair) index else project.manualOrder,
        )
    }

    val noteOrderById = mutableMapOf<String, Int>()
    notes.groupBy { it.projectId }.forEach { (_, projectNotes) ->
        val needsRepair = projectNotes.map { it.manualOrder }.toSet().size != projectNotes.size
        if (needsRepair) projectNotes.forEachIndexed { index, note -> noteOrderById[note.id] = index }
    }
    val migratedNotes = notes.map { note ->
        noteOrderById[note.id]?.let { note.copy(manualOrder = it) } ?: note
    }

    // orderTasks меняет только активные задачи. Совпадение с номером в архиве
    // допустимо и не должно сбрасывать сохранённую раскладку при перезапуске.
    val activeTasks = tasks.filterNot { it.completed }
    val taskOrderNeedsRepair = activeTasks.map { it.manualOrder }.toSet().size != activeTasks.size
    var activeIndex = 0
    val migratedTasks = tasks.map { task ->
        if (taskOrderNeedsRepair && !task.completed) task.copy(manualOrder = activeIndex++) else task
    }

    return copy(
        projects = migratedProjects,
        notes = migratedNotes,
        tasks = migratedTasks,
    )
}
