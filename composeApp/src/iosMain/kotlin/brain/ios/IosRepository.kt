package brain.ios

import brain.domain.BrainData
import brain.domain.NoteText
import brain.model.*
import brain.studio.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import kotlin.time.Clock

/**
 * Тонкий iOS repository: persistence + вызов Core domain rules.
 * Вся бизнес-логика проектов/заметок/задач остаётся в BrainData.
 */
internal class IosRepository(
    private val intelligence: IosOnDeviceIntelligence,
) : StudioRepository {
    override val simulated: Boolean = false

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }
    private val defaults = NSUserDefaults.standardUserDefaults

    private var data: BrainData = readData()
    private var prefs: Preferences = readPreferences()

    private fun id(): String = NSUUID().UUIDString.lowercase()
    private fun now(): Long = Clock.System.now().toEpochMilliseconds()
    private fun language(): String = Languages.resolve(prefs.language, "ru-RU")

    override suspend fun snapshot(): AppSnapshot = AppSnapshot(
        projects = data.projects,
        notes = data.notes,
        captures = data.captures,
        tasks = data.tasks,
        runtime = RuntimeStatus(
            whisperConfigured = intelligence.supportsOnDevice(language()),
            llmConfigured = false,
            localOnly = true,
            simulated = false,
            message = if (intelligence.supportsOnDevice(language())) {
                "iOS · распознавание речи строго на устройстве"
            } else {
                "iOS · для выбранного языка нет системного on-device STT"
            },
        ),
    )

    override suspend fun preferences(): Preferences = prefs

    override suspend fun savePreferences(value: Preferences) {
        prefs = value.validated()
        persistPreferences()
    }

    override suspend fun createProject(draft: ProjectDraft): Project {
        val projectId = id()
        data = data.addProject(projectId, now(), draft)
        persistData()
        return data.projects.first { it.id == projectId }
    }

    override suspend fun updateProject(id: String, update: ProjectUpdate): Project {
        data = data.updateProject(id, update, now())
        persistData()
        return data.projects.first { it.id == id }
    }

    override suspend fun pinProject(id: String, pinned: Boolean): Project {
        data = data.pinProject(id, pinned)
        persistData()
        return data.projects.first { it.id == id }
    }

    override suspend fun orderPins(ids: List<String>) {
        data = data.orderPins(ids)
        persistData()
    }

    override suspend fun orderProjects(ids: List<String>) {
        data = data.orderProjects(ids)
        persistData()
    }

    override suspend fun updateCaptureDraft(id: String, update: CaptureDraftUpdate): Capture {
        data = data.updateDraft(id, update)
        persistData()
        return capture(id)
    }

    override suspend fun distribute(id: String, request: DistributionRequest): Note {
        val result = data.distribute(id, request, this.id(), now())
        data = result.first
        persistData()
        return result.second
    }

    override suspend fun distributeTask(id: String, request: TaskDistributionRequest): Task {
        val result = data.distributeTask(id, request, this.id(), now())
        data = result.first
        persistData()
        return result.second
    }

    override suspend fun updateNote(id: String, update: NoteUpdate): Note {
        data = data.updateNote(id, update, now())
        persistData()
        return data.notes.first { it.id == id }
    }

    override suspend fun pinNote(id: String, pinned: Boolean): Note {
        data = data.pinNote(id, pinned)
        persistData()
        return data.notes.first { it.id == id }
    }

    override suspend fun orderNotes(projectId: String, ids: List<String>) {
        data = data.orderNotes(projectId, ids)
        persistData()
    }

    override suspend fun updateTask(id: String, update: TaskUpdate): Task {
        data = data.updateTask(id, update, now())
        persistData()
        return data.tasks.first { it.id == id }
    }

    override suspend fun rescheduleTask(id: String, update: TaskScheduleUpdate): Task {
        data = data.rescheduleTask(id, update, now())
        persistData()
        return data.tasks.first { it.id == id }
    }

    override suspend fun completeTask(id: String): Task {
        data = data.completeTask(id, now())
        persistData()
        return data.tasks.first { it.id == id }
    }

    override suspend fun deleteTask(id: String) {
        data = data.deleteTask(id)
        persistData()
    }

    override suspend fun orderTasks(ids: List<String>) {
        data = data.orderTasks(ids)
        persistData()
    }

    override suspend fun claimTaskReminders(now: Long, zoneId: String): List<Task> {
        val result = data.claimDueReminders(now, zoneId)
        data = result.first
        if (result.second.isNotEmpty()) persistData()
        return result.second
    }

    override suspend fun reprocess(id: String): Capture {
        val source = capture(id)
        val path = source.audioFileName?.takeIf(IosPaths::exists)
            ?: return fail(id, CaptureStatus.FAILED, "Аудиофайл записи не найден")

        updateCapture(id) { it.copy(status = CaptureStatus.TRANSCRIBING, message = "") }
        return try {
            val text = intelligence.transcribe(path, language(), prefs.demoExample)
            updateCapture(id) {
                it.copy(
                    title = NoteText.title(text),
                    transcript = text,
                    preparedText = text,
                    status = CaptureStatus.READY,
                    message = "",
                    simulated = false,
                    audioFinalized = true,
                )
            }
        } catch (e: Throwable) {
            val unavailable = e.message?.contains("onDeviceSpeechUnavailable") == true ||
                e.message?.contains("speechPermissionDenied") == true
            fail(
                id,
                if (unavailable) CaptureStatus.NEEDS_MODEL else CaptureStatus.FAILED,
                e.message ?: "Не удалось распознать речь",
            )
        }
    }

    override suspend fun tidy(id: String): Capture {
        val current = capture(id)
        val text = intelligence.tidy(current.textToSave, language())
        return updateCapture(id) {
            it.copy(
                title = NoteText.title(text),
                preparedText = text,
                draftEdited = true,
                llmApplied = false,
                rankingApplied = false,
                relevance = emptyMap(),
                status = CaptureStatus.READY,
                simulated = false,
            )
        }
    }

    override suspend fun rank(id: String): Capture {
        val current = capture(id)
        val scores = intelligence.rank(current.textToSave, data.projects, language())
        return updateCapture(id) {
            it.copy(
                title = NoteText.title(it.textToSave),
                relevance = scores,
                rankingApplied = true,
                simulated = false,
            )
        }
    }

    override suspend fun discard(id: String) {
        val current = capture(id)
        current.audioFileName?.let(IosPaths::remove)
        current.compactAudioFileName?.let(IosPaths::remove)
        data = data.copy(captures = data.captures.filterNot { it.id == id })
        persistData()
    }

    override suspend fun createDemo(): Capture = error("Demo mode is disabled in production iOS")

    fun createAudioCapture(path: String, durationSeconds: Double, waveform: List<Float>): Capture {
        val capture = Capture(
            id = id(),
            createdAt = now(),
            status = CaptureStatus.QUEUED,
            audioFileName = path,
            durationSeconds = durationSeconds,
            waveform = waveform.takeLast(256),
            simulated = false,
            audioFinalized = true,
        )
        data = data.addCapture(capture)
        persistData()
        return capture
    }

    fun audioPath(captureId: String): String? =
        data.captures.firstOrNull { it.id == captureId }?.audioFileName?.takeIf(IosPaths::exists)

    private fun capture(id: String): Capture =
        data.captures.firstOrNull { it.id == id } ?: error("Запись не найдена")

    private fun updateCapture(id: String, transform: (Capture) -> Capture): Capture {
        data = data.updateCapture(id, transform)
        persistData()
        return capture(id)
    }

    private fun fail(id: String, status: CaptureStatus, message: String): Capture =
        updateCapture(id) { it.copy(status = status, message = message, audioFinalized = true) }

    private fun persistData() = IosPaths.write(IosPaths.stateFile, json.encodeToString(data))
    private fun persistPreferences() = IosPaths.write(IosPaths.preferencesFile, json.encodeToString(prefs))

    private fun readData(): BrainData {
        IosPaths.read(IosPaths.stateFile)?.let { stored ->
            runCatching { json.decodeFromString<BrainData>(stored) }.getOrNull()?.let { return it }
        }
        // Однократная миграция старой SideStore/test-сборки.
        defaults.stringForKey("kasha.test.brain.v1")?.let { stored ->
            runCatching { json.decodeFromString<BrainData>(stored) }.getOrNull()?.let {
                IosPaths.write(IosPaths.stateFile, json.encodeToString(it))
                return it
            }
        }
        return BrainData()
    }

    private fun readPreferences(): Preferences {
        IosPaths.read(IosPaths.preferencesFile)?.let { stored ->
            runCatching { json.decodeFromString<Preferences>(stored).validated() }.getOrNull()?.let { return it }
        }
        defaults.stringForKey("kasha.test.preferences.v1")?.let { stored ->
            runCatching { json.decodeFromString<Preferences>(stored).validated() }.getOrNull()?.let {
                IosPaths.write(IosPaths.preferencesFile, json.encodeToString(it))
                return it
            }
        }
        return Preferences()
    }
}
