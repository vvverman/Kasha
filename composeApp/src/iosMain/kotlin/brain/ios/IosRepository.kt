package brain.ios

import brain.ai.BuiltInAi
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
    private val systemLanguage: String = iosSystemLanguage(),
    private val storageRoot: String? = null,
) : StudioRepository {
    override val simulated: Boolean = false

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }
    private val defaults = NSUserDefaults.standardUserDefaults
    private val stateFile: String get() = storageRoot?.let { IosPaths.child(it, "state.json") } ?: IosPaths.stateFile
    private val preferencesFile: String get() = storageRoot?.let { IosPaths.child(it, "preferences.json") } ?: IosPaths.preferencesFile

    private data class LoadedState(var data: BrainData, var preferences: Preferences)
    private val loadedState by lazy { readState() }
    private var data: BrainData
        get() = loadedState.data
        set(value) {
            val state = loadedState
            if (value == state.data) return
            IosPaths.write(stateFile, json.encodeToString(value))
            state.data = value
        }
    private var prefs: Preferences
        get() = loadedState.preferences
        set(value) {
            val state = loadedState
            if (value == state.preferences) return
            IosPaths.write(preferencesFile, json.encodeToString(value))
            state.preferences = value
        }
    private val intelligence = IosRoutedIntelligence(localIntelligence, cloudAi) { prefs }
    val aiExecution: AiExecutionCapabilityGateway = object : AiExecutionCapabilityGateway {
        override suspend fun roles(selection: AiSelection) = intelligence.capabilities(selection, language())
    }
    private val workflow = CaptureWorkflow(intelligence)

    private fun id(): String = NSUUID().UUIDString.lowercase()
    private fun now(): Long = Clock.System.now().toEpochMilliseconds()
    private fun language(): String = Languages.resolve(prefs.language, systemLanguage)

    override suspend fun snapshot(): AppSnapshot {
        val externalRoles = AiRole.entries.filter { role ->
            AiCatalog.cloudProviderId(prefs.ai.engineId(role)) != null
        }
        val externalStt = AiRole.SPEECH_TO_TEXT in externalRoles
        val externalText = externalRoles.any { it == AiRole.TEXT || it == AiRole.ROUTING }
        val localSpeechReady = prefs.ai.speechToText == BuiltInAi.APPLE_SPEECH && localIntelligence.supportsOnDevice(language())
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
        prefs = validated
    }

    override suspend fun createProject(draft: ProjectDraft): Project {
        val projectId = id()
        data = data.addProject(projectId, now(), draft)
        return data.projects.first { it.id == projectId }
    }

    override suspend fun updateProject(id: String, update: ProjectUpdate): Project {
        data = data.updateProject(id, update, now())
        return data.projects.first { it.id == id }
    }

    override suspend fun pinProject(id: String, pinned: Boolean): Project {
        data = data.pinProject(id, pinned)
        return data.projects.first { it.id == id }
    }

    override suspend fun orderPins(ids: List<String>) {
        data = data.orderPins(ids)
    }

    override suspend fun orderProjects(ids: List<String>) {
        data = data.orderProjects(ids)
    }

    override suspend fun updateCaptureDraft(id: String, update: CaptureDraftUpdate): Capture {
        data = data.updateDraft(id, update)
        return capture(id)
    }

    override suspend fun distribute(id: String, request: DistributionRequest): Note {
        val result = data.distribute(id, request, this.id(), now())
        data = result.first
        return result.second
    }

    override suspend fun distributeTask(id: String, request: TaskDistributionRequest): Task {
        val result = data.distributeTask(id, request, this.id(), now())
        data = result.first
        return result.second
    }

    override suspend fun updateNote(id: String, update: NoteUpdate): Note {
        data = data.updateNote(id, update, now())
        return data.notes.first { it.id == id }
    }

    override suspend fun pinNote(id: String, pinned: Boolean): Note {
        data = data.pinNote(id, pinned)
        return data.notes.first { it.id == id }
    }

    override suspend fun orderNotePins(projectId: String, ids: List<String>) {
        data = data.orderNotePins(projectId, ids)
    }

    override suspend fun orderNotes(projectId: String, ids: List<String>) {
        data = data.orderNotes(projectId, ids)
    }

    override suspend fun updateTask(id: String, update: TaskUpdate): Task {
        data = data.updateTask(id, update, now())
        return data.tasks.first { it.id == id }
    }

    override suspend fun rescheduleTask(id: String, update: TaskScheduleUpdate): Task {
        data = data.rescheduleTask(id, update, now())
        return data.tasks.first { it.id == id }
    }

    override suspend fun completeTask(id: String): Task {
        data = data.completeTask(id, now())
        return data.tasks.first { it.id == id }
    }

    override suspend fun deleteTask(id: String) {
        data = data.deleteTask(id)
    }

    override suspend fun orderTasks(ids: List<String>) {
        data = data.orderTasks(ids)
    }

    override suspend fun claimTaskReminders(now: Long, zoneId: String): List<Task> {
        val result = data.claimDueReminders(now, zoneId)
        data = result.first
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
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Throwable) {
            val unavailable = e.message == "aiUnavailable" || e.message?.contains("onDeviceSpeechUnavailable") == true ||
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
        data = data.copy(captures = data.captures.filterNot { it.id == id })
        current.audioFileName?.let(IosPaths::remove)
        current.compactAudioFileName?.let(IosPaths::remove)
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
        return capture
    }

    fun audioPath(captureId: String): String? =
        data.captures.firstOrNull { it.id == captureId }?.audioFileName?.takeIf(IosPaths::exists)

    private fun capture(id: String): Capture =
        data.captures.firstOrNull { it.id == id } ?: error("Запись не найдена")

    private fun updateCapture(id: String, transform: (Capture) -> Capture): Capture {
        data = data.updateCapture(id, transform)
        return capture(id)
    }

    private fun fail(id: String, status: CaptureStatus, message: String): Capture =
        updateCapture(id) { it.copy(status = status, message = message, audioFinalized = true) }

    private fun readState(): LoadedState {
        val storedData = IosPaths.read(stateFile)
        val storedPreferences = IosPaths.read(preferencesFile)
        // Старое хранилище используется только при отсутствии нового файла, не при его ошибке.
        val legacyData = if (storedData == null) defaults.stringForKey("kasha.test.brain.v1") else null
        val legacyPreferences = if (storedPreferences == null) defaults.stringForKey("kasha.test.preferences.v1") else null
        val data = (storedData ?: legacyData)?.let { json.decodeFromString<BrainData>(it) } ?: BrainData()
        val preferences = (storedPreferences ?: legacyPreferences)
            ?.let { json.decodeFromString<Preferences>(it).validated() }
            ?: Preferences(ai = BuiltInAi.appleSelection())
        // Миграция разрешена только после успешного чтения и разбора обоих документов.
        if (legacyData != null) IosPaths.write(stateFile, json.encodeToString(data))
        if (legacyPreferences != null) IosPaths.write(preferencesFile, json.encodeToString(preferences))
        return LoadedState(data, preferences)
    }
}
