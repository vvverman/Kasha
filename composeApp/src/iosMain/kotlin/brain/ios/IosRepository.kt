package brain.ios

import brain.ai.BuiltInAi
import brain.domain.CaptureAiResult
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
    private val storageDirectory: String get() = storageRoot ?: IosPaths.root
    private val stateFile: String get() = IosPaths.child(storageDirectory, "state.json")
    private val preferencesFile: String get() = IosPaths.child(storageDirectory, "preferences.json")
    private val stateMarker: String get() = IosPaths.child(storageDirectory, ".state.initialized")
    private val preferencesMarker: String get() = IosPaths.child(storageDirectory, ".preferences.initialized")

    private data class LoadedState(var data: BrainData, var preferences: Preferences)
    private val loadedState by lazy { readState() }
    private var data: BrainData
        get() = loadedState.data
        set(value) {
            val state = loadedState
            if (value == state.data) return
            IosPaths.write(stateFile, json.encodeToString(value))
            markKnown(stateMarker)
            state.data = value
        }
    private var prefs: Preferences
        get() = loadedState.preferences
        set(value) {
            val state = loadedState
            if (value == state.preferences) return
            IosPaths.write(preferencesFile, json.encodeToString(value))
            markKnown(preferencesMarker)
            state.preferences = value
        }
    private val nativeModels by lazy { IosNativeModels() }
    val aiPackages: IosModelPackages by lazy {
        IosModelPackages(IosPaths.child(storageRoot ?: IosPaths.root, "models"), selected = { prefs.ai })
    }
    private val models by lazy { IosLocalModels(aiPackages, nativeModels) }
    private val intelligence by lazy { IosRoutedIntelligence(localIntelligence, cloudAi, models) { prefs } }
    val aiExecution: AiExecutionCapabilityGateway = object : AiExecutionCapabilityGateway {
        override suspend fun roles(selection: AiSelection) = intelligence.capabilities(selection, language())
    }
    private val workflow by lazy { CaptureWorkflow(intelligence) }

    private fun id(): String = NSUUID().UUIDString.lowercase()
    private fun now(): Long = Clock.System.now().toEpochMilliseconds()
    private fun language(): String = Languages.resolve(prefs.language, systemLanguage)

    override suspend fun snapshot(): AppSnapshot {
        val externalRoles = AiRole.entries.filter { role ->
            AiCatalog.cloudProviderId(prefs.ai.engineId(role)) != null
        }
        val externalStt = AiRole.SPEECH_TO_TEXT in externalRoles
        val externalText = externalRoles.any { it == AiRole.TEXT || it == AiRole.ROUTING }
        val localSpeechReady = if (prefs.ai.speechToText == BuiltInAi.APPLE_SPEECH)
            localIntelligence.supportsOnDevice(language())
        else models.capability(AiRole.SPEECH_TO_TEXT, prefs.ai.speechToText, language()).executable
        return AppSnapshot(
            projects = data.projects,
            notes = data.notes,
            captures = data.captures,
            tasks = data.tasks,
            runtime = RuntimeStatus(
                whisperConfigured = externalStt || localSpeechReady,
                llmConfigured = externalText || models.capability(AiRole.TEXT, prefs.ai.text, language()).executable,
                localOnly = externalRoles.isEmpty(),
                simulated = false,
                message = when {
                    externalRoles.isNotEmpty() -> "iOS · внешний AI включён для ${externalRoles.joinToString()}"
                    localSpeechReady -> "iOS · распознавание речи строго на устройстве"
                    else -> "iOS · выбранное локальное распознавание недоступно"
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
            val transcribed = updateCapture(id) {
                it.copy(
                    title = NoteText.title(if (it.draftEdited) it.preparedText else text),
                    transcript = text,
                    preparedText = if (it.draftEdited) it.preparedText else text,
                    status = CaptureStatus.POLISHING,
                    message = "",
                    simulated = false,
                    audioFinalized = true,
                )
            }
            val finished = workflow.finish(transcribed, data.projects, language())
            updateCapture(id) { CaptureAiResult.apply(transcribed, it, finished) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            fail(id, CaptureStatus.FAILED, "Обработка прервана; запись сохранена")
            throw cancelled
        } catch (e: Throwable) {
            val unavailable = e.message in setOf("aiUnavailable", "runtimeUnavailable", "modelNotInstalled") ||
                e.message?.contains("onDeviceSpeechUnavailable") == true || e.message?.contains("speechPermissionDenied") == true
            fail(
                id,
                if (unavailable) CaptureStatus.NEEDS_MODEL else CaptureStatus.FAILED,
                e.message ?: "Не удалось распознать речь",
            )
        }
    }

    override suspend fun tidy(id: String): Capture {
        val before = capture(id)
        val changed = workflow.tidy(before, language())
        return updateCapture(id) { CaptureAiResult.apply(before, it, changed) }
    }

    override suspend fun rank(id: String): Capture {
        val before = capture(id)
        val changed = workflow.rank(before, data.projects, language())
        return updateCapture(id) { CaptureAiResult.apply(before, it, changed) }
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
        val storedData = readPersistent(stateFile, stateMarker, "state")
        val storedPreferences = readPersistent(preferencesFile, preferencesMarker, "preferences")
        val legacyData = if (storedData == null) defaults.stringForKey("kasha.test.brain.v1") else null
        val legacyPreferences = if (storedPreferences == null) defaults.stringForKey("kasha.test.preferences.v1") else null
        val data = (storedData ?: legacyData)?.let { json.decodeFromString<BrainData>(it) } ?: BrainData()
        val preferences = (storedPreferences ?: legacyPreferences)
            ?.let { json.decodeFromString<Preferences>(it).validated() }
            ?: Preferences()
        // Никаких recovery/migration записей до успешного чтения и разбора ОБОИХ документов.
        if (storedData != null) markKnown(stateMarker)
        if (storedPreferences != null) markKnown(preferencesMarker)
        if (legacyData != null) { IosPaths.write(stateFile, json.encodeToString(data)); markKnown(stateMarker) }
        if (legacyPreferences != null) {
            IosPaths.write(preferencesFile, json.encodeToString(preferences)); markKnown(preferencesMarker)
        }
        return LoadedState(data, preferences)
    }

    private fun readPersistent(path: String, marker: String, name: String): String? {
        val value = IosPaths.read(path)
        if (value == null) {
            check(!IosPaths.exists(marker)) {
                "Локальный файл $name отсутствует; существующие данные оставлены для восстановления"
            }
        }
        return value
    }

    private fun markKnown(marker: String) {
        if (!IosPaths.exists(marker)) IosPaths.write(marker, "1")
    }
}
