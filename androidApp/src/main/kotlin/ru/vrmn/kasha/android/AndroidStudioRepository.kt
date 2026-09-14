package ru.vrmn.kasha.android

import brain.domain.BrainData
import brain.domain.CaptureWorkflow
import brain.domain.NoteText
import brain.domain.migrated
import brain.model.*
import brain.studio.Intelligence
import brain.studio.Languages
import brain.studio.Preferences
import brain.studio.StudioRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import kotlin.time.Clock

/**
 * Тонкий Android repository: app-private persistence/filesystem + вызовы Core rules.
 * Предметные правила проектов, заметок, задач, append и сортировок остаются в BrainData.
 */
internal class AndroidStudioRepository(
    root: File,
    private val intelligence: Intelligence,
    private val systemLanguage: String,
    private val runtimeStatus: () -> RuntimeStatus = {
        RuntimeStatus(
            whisperConfigured = false,
            llmConfigured = false,
            localOnly = true,
            simulated = intelligence.simulated,
            message = "Android · локальная AI-обработка не подключена",
        )
    },
) : StudioRepository {
    override val simulated: Boolean get() = intelligence.simulated

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }
    private val storage = AndroidStorage(root)
    private val mutex = Mutex()
    private val workflow = CaptureWorkflow(intelligence)

    private var data: BrainData
    private var prefs: Preferences

    init {
        val loaded = storage.read(storage.stateFile)
            ?.let { runCatching { json.decodeFromString<BrainData>(it) }.getOrNull() }
            ?: BrainData()
        val migrated = loaded.migrated()
        storage.reconcile(migrated.captures)
        data = migrated.copy(captures = migrated.captures.map { capture ->
            if (capture.status.isWorking) {
                capture.copy(status = CaptureStatus.FAILED, message = "Обработка прервана; запись сохранена")
            } else capture
        })
        if (data != loaded) persistData()

        prefs = storage.read(storage.preferencesFile)
            ?.let { runCatching { json.decodeFromString<Preferences>(it).validated() }.getOrNull() }
            ?: Preferences()
    }

    override suspend fun snapshot(): AppSnapshot = mutex.withLock {
        AppSnapshot(
            projects = data.projects,
            notes = data.notes,
            captures = data.captures,
            runtime = runtimeStatus(),
            tasks = data.tasks,
        )
    }

    override suspend fun preferences(): Preferences = mutex.withLock { prefs }

    override suspend fun savePreferences(value: Preferences) = mutex.withLock {
        prefs = value.validated()
        storage.write(storage.preferencesFile, json.encodeToString(prefs))
    }

    override suspend fun createProject(draft: ProjectDraft): Project = mutex.withLock {
        val id = id()
        commit(data.addProject(id, now(), draft))
        data.projects.first { it.id == id }
    }

    override suspend fun updateProject(id: String, update: ProjectUpdate): Project = mutex.withLock {
        commit(data.updateProject(id, update, now()))
        data.projects.first { it.id == id }
    }

    override suspend fun pinProject(id: String, pinned: Boolean): Project = mutex.withLock {
        commit(data.pinProject(id, pinned))
        data.projects.first { it.id == id }
    }

    override suspend fun orderPins(ids: List<String>) = mutex.withLock { commit(data.orderPins(ids)) }
    override suspend fun orderProjects(ids: List<String>) = mutex.withLock { commit(data.orderProjects(ids)) }

    override suspend fun updateCaptureDraft(id: String, update: CaptureDraftUpdate): Capture = mutex.withLock {
        commit(data.updateDraft(id, update))
        captureLocked(id)
    }

    override suspend fun distribute(id: String, request: DistributionRequest): Note = mutex.withLock {
        val result = data.distribute(id, request, this.id(), now())
        commit(result.first)
        result.second
    }

    override suspend fun distributeTask(id: String, request: TaskDistributionRequest): Task = mutex.withLock {
        val result = data.distributeTask(id, request, this.id(), now())
        commit(result.first)
        result.second
    }

    override suspend fun updateNote(id: String, update: NoteUpdate): Note = mutex.withLock {
        commit(data.updateNote(id, update, now()))
        data.notes.first { it.id == id }
    }

    override suspend fun pinNote(id: String, pinned: Boolean): Note = mutex.withLock {
        commit(data.pinNote(id, pinned))
        data.notes.first { it.id == id }
    }

    override suspend fun orderNotes(projectId: String, ids: List<String>) = mutex.withLock {
        commit(data.orderNotes(projectId, ids))
    }

    override suspend fun updateTask(id: String, update: TaskUpdate): Task = mutex.withLock {
        commit(data.updateTask(id, update, now()))
        data.tasks.first { it.id == id }
    }

    override suspend fun rescheduleTask(id: String, update: TaskScheduleUpdate): Task = mutex.withLock {
        commit(data.rescheduleTask(id, update, now()))
        data.tasks.first { it.id == id }
    }

    override suspend fun completeTask(id: String): Task = mutex.withLock {
        commit(data.completeTask(id, now()))
        data.tasks.first { it.id == id }
    }

    override suspend fun deleteTask(id: String) = mutex.withLock { commit(data.deleteTask(id)) }
    override suspend fun orderTasks(ids: List<String>) = mutex.withLock { commit(data.orderTasks(ids)) }

    override suspend fun claimTaskReminders(now: Long, zoneId: String): List<Task> = mutex.withLock {
        val result = data.claimDueReminders(now, zoneId)
        commit(result.first)
        result.second
    }

    override suspend fun reprocess(id: String): Capture {
        val start = mutex.withLock {
            val current = captureLocked(id)
            val file = storage.resolveAudio(current)
            val config = prefs
            val updated = current.copy(status = CaptureStatus.TRANSCRIBING, message = "")
            commit(data.updateCapture(id) { updated })
            Triple(updated, file, config)
        }

        return try {
            val language = Languages.resolve(start.third.language, systemLanguage)
            val text = intelligence.transcribe(start.second.absolutePath, language, start.third.demoExample).trim()
            require(text.isNotBlank()) { "emptyTranscription" }
            val transcribed = mutex.withLock {
                val current = captureLocked(id)
                val updated = current.copy(
                    title = NoteText.title(text),
                    transcript = text,
                    preparedText = if (current.draftEdited) current.preparedText else text,
                    status = CaptureStatus.POLISHING,
                    message = "",
                    simulated = intelligence.simulated,
                    audioFinalized = true,
                )
                commit(data.updateCapture(id) { updated })
                updated
            }
            val projects = mutex.withLock { data.projects }
            val finished = workflow.finish(transcribed, projects, language)
            mutex.withLock {
                commit(data.updateCapture(id) { finished })
                captureLocked(id)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val needsModel = error.message == "androidAiNotConfigured"
            fail(id, if (needsModel) CaptureStatus.NEEDS_MODEL else CaptureStatus.FAILED, error.message ?: "Не удалось обработать запись")
        }
    }

    override suspend fun tidy(id: String): Capture {
        val input = mutex.withLock { captureLocked(id) to Languages.resolve(prefs.language, systemLanguage) }
        val result = workflow.tidy(input.first, input.second)
        return mutex.withLock {
            commit(data.updateCapture(id) { result })
            captureLocked(id)
        }
    }

    override suspend fun rank(id: String): Capture {
        val input = mutex.withLock {
            Triple(captureLocked(id), data.projects, Languages.resolve(prefs.language, systemLanguage))
        }
        val result = workflow.rank(input.first, input.second, input.third)
        return mutex.withLock {
            commit(data.updateCapture(id) { result })
            captureLocked(id)
        }
    }

    override suspend fun discard(id: String) = mutex.withLock {
        val current = captureLocked(id)
        require(current.isInbox && !current.status.isWorking)
        val trash = storage.stageDeleteCaptureAudio(current.id)
        try {
            commit(data.copy(captures = data.captures.filterNot { it.id == id }))
            storage.finishStagedDelete(trash)
        } catch (error: Throwable) {
            storage.restoreStagedAudio(current.id, trash)
            throw error
        }
    }

    override suspend fun createDemo(): Capture = error("Demo mode is disabled in production Android")

    fun newPendingFile(): File = storage.newPendingFile()
    fun pendingFiles(): List<File> = storage.pendingFiles()

    suspend fun acceptPending(source: File, durationSeconds: Double, waveform: List<Float>): Capture = mutex.withLock {
        require(data.captures.none { it.isInbox }) { "Сначала сохраните или удалите текущую запись" }
        require(durationSeconds.isFinite() && durationSeconds >= 0.0)
        val captureId = storage.pendingId(source)
        val target = storage.acceptPending(source, captureId)
        try {
            val capture = Capture(
                id = captureId,
                createdAt = now(),
                status = CaptureStatus.QUEUED,
                audioFileName = storage.relative(target),
                durationSeconds = durationSeconds,
                waveform = waveform.map { it.coerceIn(0f, 1f) },
                simulated = false,
                inputSha256 = storage.sha256(target),
                audioFinalized = true,
            )
            commit(data.addCapture(capture))
            capture
        } catch (error: Throwable) {
            storage.reconcile(data.captures)
            throw error
        }
    }

    suspend fun audioFile(captureId: String): File = mutex.withLock { storage.resolveAudio(captureLocked(captureId)) }

    private suspend fun fail(id: String, status: CaptureStatus, message: String): Capture = mutex.withLock {
        val updated = captureLocked(id).copy(status = status, message = message, audioFinalized = true)
        commit(data.updateCapture(id) { updated })
        updated
    }

    private fun captureLocked(id: String): Capture = data.captures.firstOrNull { it.id == id } ?: error("Запись не найдена")

    private fun commit(next: BrainData) {
        if (next == data) return
        storage.write(storage.stateFile, json.encodeToString(next))
        data = next
    }

    private fun persistData() = storage.write(storage.stateFile, json.encodeToString(data))
    private fun id(): String = UUID.randomUUID().toString()
    private fun now(): Long = Clock.System.now().toEpochMilliseconds()
}
