package brain.studio

import brain.model.Task

/**
 * Platform boundary локальных task-reminders.
 *
 * `notify` — foreground/fallback доставка уже наступившего reminder.
 * `sync` — reconciliation системной очереди: платформа получает актуальный список задач
 * и обязана не держать pending notification для completed/deleted/unscheduled task.
 * Платформа, которая не умеет заранее планировать системные notifications, может оставить
 * default no-op `sync` и использовать только `notify` пока приложение запущено.
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
