package brain.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import brain.domain.TaskSchedule
import brain.model.ReminderRepeat
import brain.model.SortMode
import brain.model.Task
import kotlinx.coroutines.launch
import kotlin.time.Clock

private fun taskText(s: StudioState, key: String) = KashaCopy.text(s.language, key) ?: key

private fun repeatText(s: StudioState, repeat: ReminderRepeat): String = taskText(s, when (repeat) {
    ReminderRepeat.TEN_MINUTES -> "every10m"
    ReminderRepeat.THIRTY_MINUTES -> "every30m"
    ReminderRepeat.HOURLY -> "hourly"
    ReminderRepeat.DAILY -> "daily"
    ReminderRepeat.WEEKLY -> "weekly"
    ReminderRepeat.WEEKENDS -> "weekends"
    ReminderRepeat.WEEKDAYS -> "weekdays"
})

private fun taskTitle(task: Task): String = task.text.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() }?.take(90) ?: "—"

@Composable
internal fun TasksScreen(s: StudioState) {
    val task = s.snapshot.tasks.firstOrNull { it.id == s.selectedTaskId }
    if (task != null) {
        TaskDetailScreen(s, task)
        return
    }

    val scope = rememberCoroutineScope()
    val labels = mapOf(
        SortMode.ALPHABETICAL to taskText(s, "sortAlphabetical"),
        SortMode.CREATED to taskText(s, "sortCreated"),
        SortMode.UPDATED to taskText(s, "sortUpdated"),
        SortMode.MANUAL to taskText(s, "sortManual"),
    )

    Column(Modifier.fillMaxSize()) {
        Heading(if (s.taskArchive) taskText(s, "completedTasks") else taskText(s, "tasks")) {
            IconAction(
                taskText(s, if (s.taskArchive) "activeTasks" else "archive"),
                Glyph.ARCHIVE,
                { s.taskArchive = !s.taskArchive; s.selectedTaskId = null },
                filled = s.taskArchive,
            )
        }
        KashaSortBar(s.preferences.taskSort, labels, { scope.launch { s.setTaskSort(it) } })
        Spacer(Modifier.height(14.dp))

        val tasks = s.tasks()
        if (tasks.isEmpty()) {
            Text(
                taskText(s, "noTasks"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            KashaReorderableList(
                items = tasks,
                key = { it.id },
                manual = !s.taskArchive && s.preferences.taskSort == SortMode.MANUAL,
                onManualOrder = { ids -> scope.launch { s.reorderTasks(ids) } },
                modifier = Modifier.fillMaxSize(),
                spacing = 10.dp,
            ) { item, dragging ->
                KashaListCard(
                    onClick = { s.openTask(item.id) },
                    modifier = Modifier.graphicsLayer { alpha = if (dragging) .72f else 1f },
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(taskTitle(item), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (item.dueAt > 0) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "${taskText(s, "dueLabel")}: ${TaskSchedule.formatDate(item.dueAt)} · ${TaskSchedule.formatTime(item.dueAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    KashaIcon(Glyph.NEXT, Modifier.size(17.dp), MaterialTheme.colorScheme.onSurfaceVariant, animated = dragging)
                }
            }
        }
    }
}

@Composable
private fun TaskDetailScreen(s: StudioState, task: Task) {
    val scope = rememberCoroutineScope()
    var text by remember(task.id, task.updatedAt) { mutableStateOf(task.text) }
    val changed = text != task.text

    Column(Modifier.fillMaxSize().padding(bottom = 12.dp)) {
        Heading(taskTitle(task), s::closeTask, s.tr("back"))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            KashaNoteText(
                value = text,
                onValueChange = { text = it },
                placeholder = taskText(s, "task"),
                readOnly = task.completed,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            if (task.dueAt > 0) {
                Text(
                    "${taskText(s, "dueLabel")}: ${TaskSchedule.formatDate(task.dueAt)} · ${TaskSchedule.formatTime(task.dueAt)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${taskText(s, "repeatReminder")}: ${repeatText(s, task.reminderRepeat)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (task.completed) {
                Spacer(Modifier.height(12.dp))
                Text(taskText(s, "completedLabel"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (changed && !task.completed) {
                Spacer(Modifier.height(18.dp))
                Action(s.tr("save"), { scope.launch { s.saveTask(task.id, text) } }, enabled = text.isNotBlank() && !s.busy, modifier = Modifier.fillMaxWidth())
            }
        }

        if (!task.completed) {
            Action(
                taskText(s, "completeTask"),
                {
                    scope.launch {
                        if (changed && text.isNotBlank()) s.saveTask(task.id, text)
                        s.completeTask(task.id)
                    }
                },
                primary = true,
                glyph = Glyph.CHECK,
                enabled = text.isNotBlank() && !s.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Action(
                taskText(s, "changeTime"),
                { s.editTaskSchedule(task.id) },
                glyph = Glyph.CLOCK,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
        }
        Action(
            taskText(s, "deleteTask"),
            { scope.launch { s.deleteTask(task.id) } },
            glyph = Glyph.DELETE,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun TaskScheduleScreen(s: StudioState) {
    val scope = rememberCoroutineScope()
    val target = s.taskScheduleTarget
    val existing = target?.takeUnless { it == "new" }?.let { id -> s.snapshot.tasks.firstOrNull { it.id == id } }
    val initialDue = existing?.dueAt?.takeIf { it > Clock.System.now().toEpochMilliseconds() } ?: TaskSchedule.defaultDue()
    var date by remember(target, initialDue) { mutableStateOf(TaskSchedule.formatDate(initialDue)) }
    var time by remember(target, initialDue) { mutableStateOf(TaskSchedule.formatTime(initialDue)) }
    var repeat by remember(target) { mutableStateOf(existing?.reminderRepeat ?: ReminderRepeat.HOURLY) }
    val dueAt = TaskSchedule.parse(date, time)
    val valid = dueAt != null && dueAt > Clock.System.now().toEpochMilliseconds()

    Column(Modifier.fillMaxSize().padding(bottom = 12.dp)) {
        Heading(taskText(s, "reminder"), s::cancelTaskSchedule, s.tr("back"))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KashaField(date, { date = it }, taskText(s, "dueDate"), Modifier.weight(1f))
                KashaField(time, { time = it }, taskText(s, "dueTime"), Modifier.width(112.dp))
            }
            if (!valid) {
                Spacer(Modifier.height(8.dp))
                Text(taskText(s, "invalidSchedule"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(24.dp))
            Text(taskText(s, "repeatReminder"), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(10.dp))
            ReminderRepeat.entries.forEach { option ->
                KashaListCard(onClick = { repeat = option }, modifier = Modifier.padding(bottom = 8.dp)) {
                    Text(repeatText(s, option), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    if (repeat == option) KashaIcon(Glyph.CHECK, Modifier.size(18.dp), animated = true)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Action(
            taskText(s, "saveReminder"),
            { dueAt?.let { scope.launch { s.saveTaskSchedule(it, repeat) } } },
            primary = true,
            glyph = Glyph.CLOCK,
            enabled = valid && !s.busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
