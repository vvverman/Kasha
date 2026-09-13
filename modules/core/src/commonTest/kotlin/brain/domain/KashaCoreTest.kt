package brain.domain

import brain.model.*
import brain.studio.Intelligence
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.*

class KashaCoreTest {
    @Test
    fun manualProjectOrderSurvivesOtherSortModes() {
        var data = BrainData()
            .addProject("a", 100, ProjectDraft("Бета"))
            .addProject("b", 200, ProjectDraft("Альфа"))
            .addProject("c", 300, ProjectDraft("Гамма"))
        data = data.orderProjects(listOf("c", "a", "b"))

        assertEquals(listOf("c", "a", "b"), UserSort.projects(data.projects, SortMode.MANUAL).map { it.id })
        assertEquals(listOf("b", "a", "c"), UserSort.projects(data.projects, SortMode.ALPHABETICAL).map { it.id })
        assertEquals(listOf("c", "a", "b"), UserSort.projects(data.projects, SortMode.MANUAL).map { it.id })
    }

    @Test
    fun noteTitleIsAlwaysFirstNonEmptyLine() {
        assertEquals("Первая строка", NoteText.title("\n Первая строка \nвторая"))
        assertEquals("вторая", NoteText.preview("Первая строка\nвторая"))
    }

    @Test
    fun notesAndTasksUseSameFourSortModes() {
        val notes = listOf(
            Note("n1", "p", "legacy", "Бета\nтекст", 100, 300, manualOrder = 1),
            Note("n2", "p", "legacy", "Альфа\nтекст", 300, 100, manualOrder = 0),
        )
        assertEquals(listOf("n2", "n1"), UserSort.notes(notes, SortMode.ALPHABETICAL).map { it.id })
        assertEquals(listOf("n2", "n1"), UserSort.notes(notes, SortMode.CREATED).map { it.id })
        assertEquals(listOf("n1", "n2"), UserSort.notes(notes, SortMode.UPDATED).map { it.id })
        assertEquals(listOf("n2", "n1"), UserSort.notes(notes, SortMode.MANUAL).map { it.id })

        val tasks = listOf(
            Task("t1", "p", "Бета", 100, 300, 1),
            Task("t2", "p", "Альфа", 300, 100, 0),
        )
        assertEquals(listOf("t2", "t1"), UserSort.tasks(tasks, SortMode.ALPHABETICAL).map { it.id })
        assertEquals(listOf("t2", "t1"), UserSort.tasks(tasks, SortMode.CREATED).map { it.id })
        assertEquals(listOf("t1", "t2"), UserSort.tasks(tasks, SortMode.UPDATED).map { it.id })
        assertEquals(listOf("t2", "t1"), UserSort.tasks(tasks, SortMode.MANUAL).map { it.id })
    }

    @Test
    fun voiceCaptureCanBecomeScheduledTaskOnlyOnce() {
        val project = Project("p", "Проект", createdAt = 1, updatedAt = 1)
        val capture = Capture(
            id = "c",
            createdAt = 2,
            title = "Мысль",
            transcript = "Сделать локальную задачу",
            preparedText = "Сделать локальную задачу",
            status = CaptureStatus.READY,
        )
        val start = BrainData(projects = listOf(project), captures = listOf(capture))
        val (after, task) = start.distributeTask(
            "c",
            TaskDistributionRequest(dueAt = 100_000, reminderRepeat = ReminderRepeat.THIRTY_MINUTES),
            "t",
            3,
        )

        assertEquals("Сделать локальную задачу", task.text)
        assertNull(task.projectId)
        assertEquals(100_000, task.dueAt)
        assertEquals(100_000, task.nextReminderAt)
        assertEquals("t", after.captures.single().taskId)
        assertFalse(after.captures.single().isInbox)
        assertFails { after.distribute("c", DistributionRequest("p"), "n", 4) }
    }

    @Test
    fun dueTaskRemindsAndMovesDeadlineToNextDay() {
        val due = 1_700_000_000_000L
        val task = Task(
            id = "t",
            text = "Позвонить",
            createdAt = due - 10_000,
            updatedAt = due - 10_000,
            dueAt = due,
            nextReminderAt = due,
            reminderRepeat = ReminderRepeat.HOURLY,
        )
        val (after, reminders) = BrainData(tasks = listOf(task)).claimDueReminders(due, TimeZone.UTC.id)
        assertEquals(listOf("t"), reminders.map { it.id })
        assertEquals(due + 24 * 60 * 60 * 1000, after.tasks.single().dueAt)
        assertEquals(due + 60 * 60 * 1000, after.tasks.single().nextReminderAt)
    }

    @Test
    fun completingTaskMovesItToArchiveAndStopsReminders() {
        val task = Task("t", text = "Сделать", createdAt = 1, updatedAt = 1, dueAt = 10, nextReminderAt = 10)
        val after = BrainData(tasks = listOf(task)).completeTask("t", 20)
        assertTrue(after.tasks.single().completed)
        assertEquals(0, after.tasks.single().nextReminderAt)
        assertEquals(emptyList(), SnapshotQueries.tasks(after.tasks, archived = false))
        assertEquals(listOf("t"), SnapshotQueries.tasks(after.tasks, archived = true).map { it.id })
    }

    @Test
    fun editingNoteRecomputesTitleFromBody() {
        val note = Note("n", "p", "Старое", "Старое\nтело", 1, 1)
        val data = BrainData(notes = listOf(note)).updateNote("n", NoteUpdate(body = "Новое название\nНовый текст"), 2)
        assertEquals("Новое название", data.notes.single().title)
    }

    @Test
    fun captureAiWorkflowIsPlatformIndependent() = runTest {
        val intelligence = object : Intelligence {
            override val simulated = false
            override suspend fun transcribe(file: String, language: String, example: String) = "транскрипт"
            override suspend fun title(text: String, language: String) = "Этот title больше не используется"
            override suspend fun tidy(text: String, language: String) = "Сырой текст, аккуратно оформленный."
            override suspend fun rank(text: String, projects: List<Project>, language: String) = projects.associate { it.id to 4 }
        }
        val workflow = CaptureWorkflow(intelligence)
        val projects = listOf(Project("p", "Kasha", createdAt = 1, updatedAt = 1))
        val capture = Capture(
            id = "c",
            createdAt = 2,
            transcript = "сырой текст",
            preparedText = "сырой текст",
            status = CaptureStatus.COMPACTING,
        )

        val finished = workflow.finish(capture, projects, "ru")
        assertEquals(CaptureStatus.READY, finished.status)
        assertEquals("сырой текст", finished.title)
        assertEquals(mapOf("p" to 4), finished.relevance)
        assertTrue(finished.rankingApplied)

        val tidied = workflow.tidy(finished, "ru")
        assertEquals("Сырой текст, аккуратно оформленный.", tidied.preparedText)
        assertEquals("Сырой текст, аккуратно оформленный.", tidied.title)
        assertTrue(tidied.draftEdited)
        assertTrue(tidied.llmApplied)
        assertFalse(tidied.rankingApplied)
        assertEquals(emptyMap(), tidied.relevance)

        val reranked = workflow.rank(tidied, projects, "ru")
        assertEquals(mapOf("p" to 4), reranked.relevance)
        assertTrue(reranked.rankingApplied)
    }

    @Test
    fun invalidRelevanceStillRejected() = runTest {
        val projects = listOf(Project("p", "Kasha", createdAt = 1, updatedAt = 1))
        val capture = Capture(
            id = "c",
            createdAt = 2,
            transcript = "Проверить сохранение локальной заметки",
            preparedText = "Проверить сохранение локальной заметки",
            status = CaptureStatus.COMPACTING,
        )
        val invalidRank = object : Intelligence {
            override val simulated = false
            override suspend fun transcribe(file: String, language: String, example: String) = ""
            override suspend fun title(text: String, language: String) = "не используется"
            override suspend fun tidy(text: String, language: String) = text
            override suspend fun rank(text: String, projects: List<Project>, language: String) = mapOf("p" to 9)
        }
        assertFails { CaptureWorkflow(invalidRank).finish(capture, projects, "ru") }
    }

    @Test
    fun destructiveAiEditIsRejectedInsideCore() = runTest {
        val intelligence = object : Intelligence {
            override val simulated = false
            override suspend fun transcribe(file: String, language: String, example: String) = ""
            override suspend fun title(text: String, language: String) = ""
            override suspend fun tidy(text: String, language: String) = "Совсем другой текст 99"
            override suspend fun rank(text: String, projects: List<Project>, language: String) = emptyMap<String, Int>()
        }
        val original = "Нельзя удалить 42 важных пункта проекта"
        val capture = Capture(
            id = "c",
            createdAt = 1,
            title = "Проект",
            transcript = original,
            preparedText = original,
            status = CaptureStatus.READY,
        )

        assertFails { CaptureWorkflow(intelligence).tidy(capture, "ru") }
    }
}
