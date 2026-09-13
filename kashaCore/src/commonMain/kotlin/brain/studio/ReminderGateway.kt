package brain.studio

import brain.model.Task

/**
 * Платформа доставляет локальные уведомления. `sync` позволяет ОС заранее поставить
 * системные напоминания, поэтому они могут сработать даже когда приложение закрыто.
 */
interface ReminderGateway {
    val available: Boolean
    suspend fun notify(task: Task)
    suspend fun sync(tasks: List<Task>) = Unit
}

object NoopReminderGateway : ReminderGateway {
    override val available: Boolean = false
    override suspend fun notify(task: Task) = Unit
}
