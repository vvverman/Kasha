package brain.domain

import brain.model.*
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

object ProjectOrder {
    /** При выборе назначения релевантность важнее пользовательской сортировки. */
    fun sorted(projects: List<Project>, scores: Map<String, Int> = emptyMap()): List<Project> =
        projects.sortedWith(
            compareByDescending<Project> { it.pinned }
                .thenComparator { a, b ->
                    if (a.pinned && b.pinned && a.pinOrder != b.pinOrder) a.pinOrder.compareTo(b.pinOrder)
                    else if (!a.pinned && !b.pinned && (scores[a.id] ?: 0) != (scores[b.id] ?: 0)) (scores[b.id] ?: 0).compareTo(scores[a.id] ?: 0)
                    else a.title.lowercase().compareTo(b.title.lowercase())
                }
                .thenBy { it.id }
        )
}

/** Одни и те же правила сортировки используются на всех платформах. */
object UserSort {
    private fun Project.modified() = if (updatedAt > 0) updatedAt else createdAt

    fun projects(items: List<Project>, mode: SortMode): List<Project> {
        if (mode == SortMode.MANUAL) return items.sortedWith(compareBy<Project> { it.manualOrder }.thenBy { it.id })
        return items.sortedWith(
            compareByDescending<Project> { it.pinned }
                .thenComparator { a, b ->
                    if (a.pinned && b.pinned && a.pinOrder != b.pinOrder) return@thenComparator a.pinOrder.compareTo(b.pinOrder)
                    when (mode) {
                        SortMode.ALPHABETICAL -> a.title.lowercase().compareTo(b.title.lowercase())
                        SortMode.CREATED -> b.createdAt.compareTo(a.createdAt)
                        SortMode.UPDATED -> b.modified().compareTo(a.modified())
                        SortMode.MANUAL -> 0
                    }
                }
                .thenBy { it.id }
        )
    }

    fun notes(items: List<Note>, mode: SortMode): List<Note> {
        if (mode == SortMode.MANUAL) return items.sortedWith(compareBy<Note> { it.manualOrder }.thenBy { it.id })
        return items.sortedWith(
            compareByDescending<Note> { it.pinned }
                .thenComparator { a, b ->
                    if (a.pinned && b.pinned && a.pinOrder != b.pinOrder) return@thenComparator a.pinOrder.compareTo(b.pinOrder)
                    when (mode) {
                        SortMode.ALPHABETICAL -> NoteText.title(a.body).lowercase().compareTo(NoteText.title(b.body).lowercase())
                        SortMode.CREATED -> b.createdAt.compareTo(a.createdAt)
                        SortMode.UPDATED -> b.updatedAt.compareTo(a.updatedAt)
                        SortMode.MANUAL -> 0
                    }
                }
                .thenBy { it.id }
        )
    }

    fun tasks(items: List<Task>, mode: SortMode): List<Task> = items.sortedWith(
        Comparator { a, b ->
            val result = when (mode) {
                SortMode.ALPHABETICAL -> a.text.lowercase().compareTo(b.text.lowercase())
                SortMode.CREATED -> b.createdAt.compareTo(a.createdAt)
                SortMode.UPDATED -> b.updatedAt.compareTo(a.updatedAt)
                SortMode.MANUAL -> a.manualOrder.compareTo(b.manualOrder)
            }
            if (result != 0) result else a.id.compareTo(b.id)
        }
    )
}

object NoteText {
    fun append(existing: String, addition: String): String {
        val clean = addition.trim()
        if (clean.isEmpty()) return existing
        return if (existing.isEmpty()) clean else existing + "\n\n" + clean
    }

    /** Первая непустая строка — единственный заголовок заметки. Отдельного поля в UX нет. */
    fun title(text: String): String = text
        .lineSequence()
        .map(String::trim)
        .firstOrNull { it.isNotEmpty() }
        ?.take(90)
        ?: "Новая заметка"

    fun preview(text: String): String {
        val lines = text.lineSequence().map(String::trim).filter { it.isNotEmpty() }.toList()
        return lines.drop(1).joinToString(" ").take(180)
    }
}

/** Локальная календарная логика задач. Никакого серверного планировщика здесь нет. */
object TaskSchedule {
    private const val MINUTE = 60_000L

    fun defaultDue(now: Long = Clock.System.now().toEpochMilliseconds(), zone: TimeZone = TimeZone.currentSystemDefault()): Long {
        val instant = Instant.fromEpochMilliseconds(now)
        return instant.plus(1, DateTimeUnit.HOUR).toEpochMilliseconds()
    }

    fun formatDate(epochMillis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val local = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone)
        return "${local.day.toString().padStart(2, '0')}.${local.monthNumber.toString().padStart(2, '0')}.${local.year}"
    }

    fun formatTime(epochMillis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val local = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone)
        return "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
    }

    fun parse(date: String, time: String, zone: TimeZone = TimeZone.currentSystemDefault()): Long? = runCatching {
        val d = date.trim().split('.')
        val t = time.trim().split(':')
        require(d.size == 3 && t.size == 2)
        LocalDateTime(
            LocalDate(d[2].toInt(), d[1].toInt(), d[0].toInt()),
            LocalTime(t[0].toInt(), t[1].toInt()),
        ).toInstant(zone).toEpochMilliseconds()
    }.getOrNull()

    /** После пропущенного срока срок задачи становится ближайшим следующим днём в то же локальное время. */
    fun rollDeadline(dueAt: Long, now: Long, zone: TimeZone): Long {
        if (dueAt <= 0) return now
        var candidate = Instant.fromEpochMilliseconds(dueAt)
        while (candidate.toEpochMilliseconds() <= now) candidate = candidate.plus(1, DateTimeUnit.DAY, zone)
        return candidate.toEpochMilliseconds()
    }

    fun nextReminder(after: Long, repeat: ReminderRepeat, zone: TimeZone): Long {
        val instant = Instant.fromEpochMilliseconds(after)
        return when (repeat) {
            ReminderRepeat.TEN_MINUTES -> after + 10 * MINUTE
            ReminderRepeat.THIRTY_MINUTES -> after + 30 * MINUTE
            ReminderRepeat.HOURLY -> after + 60 * MINUTE
            ReminderRepeat.DAILY -> instant.plus(1, DateTimeUnit.DAY, zone).toEpochMilliseconds()
            ReminderRepeat.WEEKLY -> instant.plus(7, DateTimeUnit.DAY, zone).toEpochMilliseconds()
            ReminderRepeat.WEEKENDS -> nextMatchingDay(instant, zone, setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
            ReminderRepeat.WEEKDAYS -> nextMatchingDay(
                instant,
                zone,
                setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            )
        }
    }

    private fun nextMatchingDay(start: Instant, zone: TimeZone, allowed: Set<DayOfWeek>): Long {
        var candidate = start
        do candidate = candidate.plus(1, DateTimeUnit.DAY, zone)
        while (candidate.toLocalDateTime(zone).dayOfWeek !in allowed)
        return candidate.toEpochMilliseconds()
    }
}

object SnapshotQueries {
    fun inbox(captures: List<Capture>): List<Capture> = captures.filter { it.isInbox }.sortedByDescending { it.createdAt }
    fun notes(projectId: String, notes: List<Note>, mode: SortMode = SortMode.UPDATED): List<Note> = UserSort.notes(notes.filter { it.projectId == projectId }, mode)
    fun tasks(tasks: List<Task>, mode: SortMode = SortMode.UPDATED, archived: Boolean = false): List<Task> =
        UserSort.tasks(tasks.filter { it.completed == archived }, mode)
}
