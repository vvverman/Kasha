package brain.domain

import brain.model.*
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable

/**
 * Полностью платформонезависимое состояние Kasha.
 * Формат хранит ручной порядок отдельно от выбранного способа сортировки:
 * пользователь может уйти с MANUAL и вернуться к нему без потери раскладки.
 */
@Serializable
data class BrainData(
    val projects: List<Project> = emptyList(),
    val notes: List<Note> = emptyList(),
    val captures: List<Capture> = emptyList(),
    val tasks: List<Task> = emptyList(),
) {
    fun addProject(id: String, now: Long, draft: ProjectDraft): BrainData {
        require(projects.none { it.id == id }) { "Повторный идентификатор проекта" }
        require(draft.title.isNotBlank()) { "Введите название проекта" }
        val order = (projects.maxOfOrNull { it.manualOrder } ?: -1) + 1
        return copy(projects = projects + Project(
            id = id,
            title = draft.title.trim(),
            description = draft.description.trim(),
            instruction = draft.instruction.trim(),
            createdAt = now,
            updatedAt = now,
            manualOrder = order,
        ))
    }

    fun updateProject(id: String, update: ProjectUpdate, now: Long = 0): BrainData {
        require(update.title.isNotBlank()) { "Введите название проекта" }
        val project = projects.firstOrNull { it.id == id } ?: error("Проект не найден")
        val changed = project.copy(
            title = update.title.trim(),
            description = update.description.trim(),
            instruction = update.instruction.trim(),
            updatedAt = if (now > 0) now else project.updatedAt,
        )
        return copy(projects = projects.map { if (it.id == id) changed else it })
    }

    fun pinProject(id: String, pinned: Boolean): BrainData {
        val old = projects.firstOrNull { it.id == id } ?: error("Проект не найден")
        if (old.pinned == pinned) return this
        val order = if (pinned) (projects.filter { it.pinned }.maxOfOrNull { it.pinOrder } ?: -1) + 1 else old.pinOrder
        return copy(projects = projects.map { if (it.id == id) old.copy(pinned = pinned, pinOrder = order) else it })
    }

    fun orderPins(ids: List<String>): BrainData {
        val pinned = projects.filter { it.pinned }.map { it.id }.toSet()
        require(ids.size == pinned.size && ids.toSet() == pinned) { "Порядок должен включать все закреплённые проекты ровно один раз" }
        val orders = ids.withIndex().associate { it.value to it.index }
        return copy(projects = projects.map { p -> orders[p.id]?.let { p.copy(pinOrder = it) } ?: p })
    }

    fun orderProjects(ids: List<String>): BrainData {
        val existing = projects.map { it.id }.toSet()
        require(ids.size == existing.size && ids.toSet() == existing) { "Ручной порядок должен включать все проекты ровно один раз" }
        val orders = ids.withIndex().associate { it.value to it.index }
        return copy(projects = projects.map { it.copy(manualOrder = orders.getValue(it.id)) })
    }

    fun updateNote(id: String, update: NoteUpdate, now: Long): BrainData {
        val old = notes.firstOrNull { it.id == id } ?: error("Заметка не найдена")
        require(update.body.isNotBlank()) { "Введите текст заметки" }
        val body = update.body.trimEnd()
        val changed = old.copy(title = NoteText.title(body), body = body, updatedAt = now)
        return copy(notes = notes.map { if (it.id == id) changed else it })
    }

    fun pinNote(id: String, pinned: Boolean): BrainData {
        val old = notes.firstOrNull { it.id == id } ?: error("Заметка не найдена")
        if (old.pinned == pinned) return this
        val order = if (pinned) {
            (notes.filter { it.projectId == old.projectId && it.pinned }.maxOfOrNull { it.pinOrder } ?: -1) + 1
        } else old.pinOrder
        val changed = old.copy(pinned = pinned, pinOrder = order)
        return copy(notes = notes.map { if (it.id == id) changed else it })
    }

    fun orderNotes(projectId: String, ids: List<String>): BrainData {
        require(projects.any { it.id == projectId }) { "Проект не найден" }
        val existing = notes.filter { it.projectId == projectId }.map { it.id }.toSet()
        require(ids.size == existing.size && ids.toSet() == existing) { "Ручной порядок должен включать все заметки проекта ровно один раз" }
        val orders = ids.withIndex().associate { it.value to it.index }
        return copy(notes = notes.map { n ->
            if (n.projectId == projectId) n.copy(manualOrder = orders.getValue(n.id)) else n
        })
    }

    fun updateTask(id: String, update: TaskUpdate, now: Long): BrainData {
        val old = tasks.firstOrNull { it.id == id } ?: error("Задача не найдена")
        require(update.text.isNotBlank()) { "Введите текст задачи" }
        val changed = old.copy(text = update.text.trimEnd(), updatedAt = now)
        return copy(tasks = tasks.map { if (it.id == id) changed else it })
    }

    fun rescheduleTask(id: String, update: TaskScheduleUpdate, now: Long): BrainData {
        val old = tasks.firstOrNull { it.id == id } ?: error("Задача не найдена")
        require(!old.completed) { "Выполненная задача уже в архиве" }
        require(update.dueAt > now) { "Срок должен быть в будущем" }
        val changed = old.copy(
            dueAt = update.dueAt,
            nextReminderAt = update.dueAt,
            reminderRepeat = update.reminderRepeat,
            updatedAt = now,
        )
        return copy(tasks = tasks.map { if (it.id == id) changed else it })
    }

    fun completeTask(id: String, now: Long): BrainData {
        val old = tasks.firstOrNull { it.id == id } ?: error("Задача не найдена")
        if (old.completed) return this
        return copy(tasks = tasks.map {
            if (it.id == id) old.copy(completedAt = now, nextReminderAt = 0, updatedAt = now) else it
        })
    }

    fun deleteTask(id: String): BrainData {
        require(tasks.any { it.id == id }) { "Задача не найдена" }
        return copy(tasks = tasks.filterNot { it.id == id })
    }

    fun orderTasks(ids: List<String>): BrainData {
        val active = tasks.filterNot { it.completed }.map { it.id }.toSet()
        require(ids.size == active.size && ids.toSet() == active) { "Ручной порядок должен включать все активные задачи ровно один раз" }
        val orders = ids.withIndex().associate { it.value to it.index }
        return copy(tasks = tasks.map { task -> orders[task.id]?.let { task.copy(manualOrder = it) } ?: task })
    }

    /**
     * Атомарно забирает напоминания, срок которых наступил. Одновременно переносит
     * просроченный срок на следующий день в то же локальное время и назначает
     * следующее напоминание по выбранной пользователем частоте.
     */
    fun claimDueReminders(now: Long, zoneId: String): Pair<BrainData, List<Task>> {
        val due = tasks.filter { !it.completed && it.nextReminderAt > 0 && it.nextReminderAt <= now }
        if (due.isEmpty()) return this to emptyList()
        val zone = TimeZone.of(zoneId)
        val dueIds = due.map { it.id }.toSet()
        val changed = tasks.map { task ->
            if (task.id !in dueIds) task else task.copy(
                dueAt = TaskSchedule.rollDeadline(task.dueAt, now, zone),
                nextReminderAt = TaskSchedule.nextReminder(now, task.reminderRepeat, zone),
            )
        }
        return copy(tasks = changed) to due
    }

    fun addCapture(capture: Capture): BrainData {
        require(captures.none { it.id == capture.id }) { "Повторный идентификатор записи" }
        return copy(captures = captures + capture)
    }

    fun updateCapture(id: String, transform: (Capture) -> Capture): BrainData {
        val old = captures.firstOrNull { it.id == id } ?: error("Запись не найдена")
        val changed = transform(old)
        require(changed.id == old.id && changed.createdAt == old.createdAt && changed.audioFileName == old.audioFileName) { "Нельзя подменить источник записи" }
        return copy(captures = captures.map { if (it.id == id) changed else it })
    }

    fun updateDraft(id: String, update: CaptureDraftUpdate): BrainData = updateCapture(id) { old ->
        require(!old.status.isWorking && old.isInbox) { "Дождитесь обработки. Сохранённый источник изменять нельзя" }
        old.copy(
            title = NoteText.title(update.text),
            preparedText = update.text,
            draftEdited = true,
            relevance = emptyMap(),
            rankingApplied = false,
        )
    }

    fun distribute(id: String, request: DistributionRequest, newNoteId: String, now: Long): Pair<BrainData, Note> {
        val capture = captures.firstOrNull { it.id == id } ?: error("Запись не найдена")
        capture.noteId?.let { existing -> return this to (notes.firstOrNull { it.id == existing } ?: error("Заметка источника не найдена")) }
        require(capture.taskId == null) { "Запись уже сохранена как задача" }
        require(!capture.status.isWorking) { "Дождитесь завершения обработки" }
        val project = projects.firstOrNull { it.id == request.projectId } ?: error("Проект не найден")
        val addition = capture.textToSave.trimEnd()
        require(addition.isNotBlank()) { "В записи пока нет текста" }
        val note = if (request.noteId == null) {
            require(notes.none { it.id == newNoteId }) { "Повторный идентификатор заметки" }
            val manualOrder = (notes.filter { it.projectId == project.id }.maxOfOrNull { it.manualOrder } ?: -1) + 1
            Note(
                id = newNoteId,
                projectId = project.id,
                title = NoteText.title(addition),
                body = addition,
                createdAt = now,
                updatedAt = now,
                manualOrder = manualOrder,
            )
        } else {
            val old = notes.firstOrNull { it.id == request.noteId } ?: error("Заметка не найдена")
            require(old.projectId == project.id) { "Заметка относится к другому проекту" }
            val body = NoteText.append(old.body, addition)
            old.copy(title = NoteText.title(body), body = body, updatedAt = now)
        }
        val updated = if (request.noteId == null) notes + note else notes.map { if (it.id == note.id) note else it }
        return copy(
            notes = updated,
            captures = captures.map { if (it.id == id) it.copy(noteId = note.id, appendedAt = now) else it },
        ) to note
    }

    fun distributeTask(id: String, request: TaskDistributionRequest, newTaskId: String, now: Long): Pair<BrainData, Task> {
        val capture = captures.firstOrNull { it.id == id } ?: error("Запись не найдена")
        capture.taskId?.let { existing -> return this to (tasks.firstOrNull { it.id == existing } ?: error("Задача источника не найдена")) }
        require(capture.noteId == null) { "Запись уже сохранена как заметка" }
        require(!capture.status.isWorking) { "Дождитесь завершения обработки" }
        request.projectId?.let { projectId -> require(projects.any { it.id == projectId }) { "Проект не найден" } }
        require(request.dueAt > now) { "Срок должен быть в будущем" }
        val text = capture.textToSave.trim()
        require(text.isNotBlank()) { "В записи пока нет текста" }
        require(tasks.none { it.id == newTaskId }) { "Повторный идентификатор задачи" }
        val order = (tasks.filterNot { it.completed }.maxOfOrNull { it.manualOrder } ?: -1) + 1
        val task = Task(
            id = newTaskId,
            projectId = request.projectId,
            text = text,
            createdAt = now,
            updatedAt = now,
            manualOrder = order,
            dueAt = request.dueAt,
            reminderRepeat = request.reminderRepeat,
            nextReminderAt = request.dueAt,
        )
        return copy(
            tasks = tasks + task,
            captures = captures.map { if (it.id == id) it.copy(taskId = task.id, appendedAt = now) else it },
        ) to task
    }
}
