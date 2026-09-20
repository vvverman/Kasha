package brain.ios

import brain.domain.BrainData
import brain.domain.CaptureWorkflow
import brain.domain.NoteText
import brain.domain.orderNotePins
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
    private val localIntelligence: IosOnDeviceIntelligence,
    cloudAi: IosCloudAiGateway? = null,
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
    private val intelligence: Intelligence = cloudAi
        ?.let { IosRoutedIntelligence(localIntelligence, it) { prefs } }
        ?: localIntelligence
    private val workflow = CaptureWorkflow(intelligence)

    private fun id(): String = NSUUID().UUIDString.lowercase()
    private fun now(): Long = Clock.System.now().toEpochMilliseconds()
    private fun language(): String = Languages.resolve(prefs.language, "ru-RU")

    override suspend fun snapshot(): AppSnapshot {
        val externalRoles = AiRole.entries.filter { role ->
            AiCatalog.cloudProviderId(prefs.ai.engineId(role)) != null
        }
        val externalStt = AiRole.SPEECH_TO_TEXT in externalRoles
        val externalText = externalRoles.any { it == AiRole.TEXT || it == AiRole.ROUTING }
        val localSpeechReady = localIntelligence.supportsOnDevice(language())
        return AppSnapshot(
            projects = data.projects,
            notes = data.notes,
            captures = data.captures,
            tasks = data.tasks,
            runtime = RuntimeStatus(
                whisperConfigured = externalStt || localSpeechReady,
                llmConfigured = externalText,
                localOnly = externalRoles.isEmpty(),
                simulated = false,
                message = when {
                    externalRoles.isNotEmpty() -> "iOS · внешний AI включён для ${externalRoles.joinToString()}"
                    localSpeechReady -> "iOS · распознавание речи строго на устройстве"
                    else -> "iOS · для выбранного языка нет системного on-device STT"
                },
            ),
        )
    }

    override suspend fun preferences(): Preferences = prefs

    override suspend fun savePreferences(value: Preferences) {
        val validated = value.validated()
        AiCatalog.validateSelection(validated.ai)
        commitPreferences(validated)
    }

    override suspend fun createProject(draft: ProjectDraft): Project {
        val projectId = id()
        commitData(data.addProject(projectId, now(), draft))
        return data.projects.first { it.id == projectId }
    }

    override suspend fun updateProject(id: String, update: ProjectUpdate): Project {
        commitData(data.updateProject(id, update, now()))
        return data.projects.first { it.id == id }
    }

    override suspend fun pinProject(id: String, pinned: Boolean): Project {
        commitData(data.pinProject(id, pinned))
        return data.projects.first { it.id == id }
    }

    override suspend fun orderPins(ids: List<String>) {
        commitData(data.orderPins(ids))
    }

    override suspend fun orderProjects(ids: List<String>) {
        commitData(data.orderProjects(ids))
    }

    override suspend fun updateCaptureDraft(id: String, update: CaptureDraftUpdate): Capture {
        commitData(data.updateDraft(id, update))
        return capture(id)
    }

    override suspend fun distribute(id: String, request: DistributionRequest): Note {
        val result = data.distribute(id, request, this.id(), now())
        commitData(result.first)
        return result.second
    }

    override suspend fun distributeTask(id: String, request: TaskDistributionRequest): Task {
        val result = data.distributeTask(id, request, this.id(), now())
        commitData(result.first)
        return result.second
    }

    override suspend fun updateNote(id: String, update: NoteUpdate): Note {
        commitData(data.updateNote(id, update, now()))
        return data.notes.first { it.id == id }
    }

    override suspend fun pinNote(id: String, pinned: Boolean): Note {
        commitData(data.pinNote(id, pinned))
        return data.notes.first { it.id == id }
    }

    override suspend fun orderNotePins(projectId: String, ids: List<String>) {
        commitData(data.orderNotePins(projectId, ids))
    }

    override suspend fun orderNotes(projectId: String, ids: List<String>) {
        commitData(data.orderNotes(projectId, ids))
    }

    override suspend fun updateTask(id: String, update: TaskUpdate): Task {
        commitData(data.updateTask(id, update, now()))
        return data.tasks.first { it.id == id }
    }

    override suspend fun rescheduleTask(id: String, update: TaskScheduleUpdate): Task {
        commitData(data.rescheduleTask(id, update, now()))
        return data.tasks.first { it.id == id }
    }

    override suspend fun completeTask(id: String): Task {
        commitData(data.completeTask(id, now()))
        return data.tasks.first { it.id == id }
    }

    override suspend fun deleteTask(id: String) {
        commitData(data.deleteTask(id))
    }

    override suspend fun orderTasks(ids: List<String>) {
        commitData(data.orderTasks(ids))
    }

    override suspend fun claimTaskReminders(now: Long, zoneId: String): List<Task> {
        val result = data.claimDueReminders(now, zoneId)
        if (result.second.isNotEmpty()) commitData(result.first)
        return result.second
    }

    override suspend fun reprocess(id: String): Capture {
        val source = capture(id)
        val path = source.audioFileName?.takeIf(IosPaths::exists)
            ?: return fail(id, CaptureStatus.FAILED, "Аудиофайл записи не найден")

        updateCapture(id) { it.copy(status = CaptureStatus.TRANSCRIBING, message = "") }
        return try {
            val text = intelligence.transcribe(path, language(), prefs.demoExample)
            require(text.isNotBlank()) { "emptyTranscription" }
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
        val changed = workflow.tidy(capture(id), language())
        return updateCapture(id) { changed }
    }

    override suspend fun rank(id: String): Capture {
        val changed = workflow.rank(capture(id), data.projects, language())
        return updateCapture(id) { changed }
    }

    override suspend fun discard(id: String) {
        val current = capture(id)
        current.audioFileName?.let(IosPaths::remove)
        current.compactAudioFileName?.let(IosPaths::remove)
        commitData(data.copy(captures = data.captures.filterNot { it.id == id }))
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
        commitData(data.addCapture(capture))
        return capture
    }

    fun audioPath(captureId: String): String? =
        data.captures.firstOrNull { it.id == captureId }?.audioFileName?.takeIf(IosPaths::exists)

    private fun capture(id: String): Capture =
        data.captures.firstOrNull { it.id == id } ?: error("Запись не найдена")

    private fun updateCapture(id: String, transform: (Capture) -> Capture): Capture {
        commitData(data.updateCapture(id, transform))
        return capture(id)
    }

    private fun fail(id: String, status: CaptureStatus, message: String): Capture =
        updateCapture(id) { it.copy(status = status, message = message, audioFinalized = true) }

    private fun commitData(next: BrainData) {
        IosPaths.write(IosPaths.stateFile, json.encodeToString(next))
        data = next
    }

    private fun commitPreferences(next: Preferences) {
        IosPaths.write(IosPaths.preferencesFile, json.encodeToString(next))
        prefs = next
    }

    private fun <T> readRecoverable(
        path: String,
        label: String,
        decode: (String) -> T,
    ): T? {
        val backup = IosPaths.backup(path)
        if (!IosPaths.exists(path) && !IosPaths.exists(backup)) return null

        var primaryFailure: Throwable? = null
        if (IosPaths.exists(path)) {
            try {
                return decode(IosPaths.readRequired(path))
            } catch (error: Throwable) {
                primaryFailure = error
            }
        }

        if (IosPaths.exists(backup)) {
            try {
                val backupText = IosPaths.readRequired(backup)
                val recovered = decode(backupText)
                if (IosPaths.exists(path)) {
                    runCatching { IosPaths.readRequired(path) }.getOrNull()?.let {
                        runCatching { IosPaths.replace(IosPaths.corrupt(path), it) }
                    }
                }
                IosPaths.replace(path, backupText)
                return recovered
            } catch (_: Throwable) {
                // Обе сохранённые копии непригодны: ниже возвращаем явную ошибку.
            }
        }

        throw IllegalStateException("$label повреждены; пустое состояние не создано", primaryFailure)
    }

    private fun readData(): BrainData {
        readRecoverable(IosPaths.stateFile, "Локальные данные Kasha") {
            json.decodeFromString<BrainData>(it)
        }?.let { return it }

        defaults.stringForKey("kasha.test.brain.v1")?.let { stored ->
            runCatching { json.decodeFromString<BrainData>(stored) }.getOrNull()?.let {
                IosPaths.write(IosPaths.stateFile, json.encodeToString(it))
                return it
            }
        }
        return BrainData()
    }

    private fun readPreferences(): Preferences {
        readRecoverable(IosPaths.preferencesFile, "Настройки Kasha") {
            json.decodeFromString<Preferences>(it).validated()
        }?.let { return it }

        defaults.stringForKey("kasha.test.preferences.v1")?.let { stored ->
            runCatching { json.decodeFromString<Preferences>(stored).validated() }.getOrNull()?.let {
                IosPaths.write(IosPaths.preferencesFile, json.encodeToString(it))
                return it
            }
        }
        return Preferences()
    }

}
