package ru.vrmn.kasha.android

import android.content.Context
import brain.domain.BrainData
import brain.domain.NoteText
import brain.model.*
import brain.studio.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import kotlin.time.Clock

/** Android shell хранит данные и вызывает только общие domain rules из kashaCore. */
internal class AndroidRepository(
    context: Context,
    private val intelligence: AndroidIntelligence,
) : StudioRepository {
    override val simulated: Boolean = false

    private val root = File(context.filesDir, "kasha").apply { mkdirs() }
    private val audioDir = File(root, "audio").apply { mkdirs() }
    private val pendingDir = File(root, "pending").apply { mkdirs() }
    private val dataFile = File(root, "state.json")
    private val prefsFile = File(root, "preferences.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private var data: BrainData = read(dataFile) ?: BrainData()
    private var prefs: Preferences = read(prefsFile)?.validated() ?: Preferences()

    private inline fun <reified T> read(file: File): T? = if (!file.isFile) null else
        runCatching { json.decodeFromString<T>(file.readText()) }.getOrNull()

    private fun id(): String = UUID.randomUUID().toString()
    private fun now(): Long = Clock.System.now().toEpochMilliseconds()
    private fun language(): String = Languages.resolve(prefs.language, "ru-RU")

    override suspend fun snapshot(): AppSnapshot = AppSnapshot(
        projects = data.projects,
        notes = data.notes,
        captures = data.captures,
        tasks = data.tasks,
        runtime = RuntimeStatus(
            whisperConfigured = intelligence.supportsOnDevice(),
            llmConfigured = false,
            localOnly = true,
            simulated = false,
            message = if (intelligence.supportsOnDevice())
                "Android · системное распознавание строго на устройстве"
            else "Android · системный on-device STT недоступен; можно подключить другой AI-движок",
        ),
    )

    override suspend fun preferences(): Preferences = prefs
    override suspend fun savePreferences(value: Preferences) { prefs = value.validated(); persistPreferences() }

    override suspend fun createProject(draft: ProjectDraft): Project {
        val value = id(); data = data.addProject(value, now(), draft); persistData(); return data.projects.first { it.id == value }
    }
    override suspend fun updateProject(id: String, update: ProjectUpdate): Project {
        data = data.updateProject(id, update, now()); persistData(); return data.projects.first { it.id == id }
    }
    override suspend fun pinProject(id: String, pinned: Boolean): Project {
        data = data.pinProject(id, pinned); persistData(); return data.projects.first { it.id == id }
    }
    override suspend fun orderPins(ids: List<String>) { data = data.orderPins(ids); persistData() }
    override suspend fun orderProjects(ids: List<String>) { data = data.orderProjects(ids); persistData() }

    override suspend fun updateCaptureDraft(id: String, update: CaptureDraftUpdate): Capture {
        data = data.updateDraft(id, update); persistData(); return capture(id)
    }
    override suspend fun distribute(id: String, request: DistributionRequest): Note {
        val result = data.distribute(id, request, this.id(), now()); data = result.first; persistData(); return result.second
    }
    override suspend fun distributeTask(id: String, request: TaskDistributionRequest): Task {
        val result = data.distributeTask(id, request, this.id(), now()); data = result.first; persistData(); return result.second
    }
    override suspend fun updateNote(id: String, update: NoteUpdate): Note {
        data = data.updateNote(id, update, now()); persistData(); return data.notes.first { it.id == id }
    }
    override suspend fun pinNote(id: String, pinned: Boolean): Note {
        data = data.pinNote(id, pinned); persistData(); return data.notes.first { it.id == id }
    }
    override suspend fun orderNotes(projectId: String, ids: List<String>) { data = data.orderNotes(projectId, ids); persistData() }

    override suspend fun updateTask(id: String, update: TaskUpdate): Task {
        data = data.updateTask(id, update, now()); persistData(); return data.tasks.first { it.id == id }
    }
    override suspend fun rescheduleTask(id: String, update: TaskScheduleUpdate): Task {
        data = data.rescheduleTask(id, update, now()); persistData(); return data.tasks.first { it.id == id }
    }
    override suspend fun completeTask(id: String): Task {
        data = data.completeTask(id, now()); persistData(); return data.tasks.first { it.id == id }
    }
    override suspend fun deleteTask(id: String) { data = data.deleteTask(id); persistData() }
    override suspend fun orderTasks(ids: List<String>) { data = data.orderTasks(ids); persistData() }
    override suspend fun claimTaskReminders(now: Long, zoneId: String): List<Task> {
        val result = data.claimDueReminders(now, zoneId); data = result.first
        if (result.second.isNotEmpty()) persistData()
        return result.second
    }

    override suspend fun reprocess(id: String): Capture {
        val source = capture(id)
        val path = source.audioFileName?.takeIf { File(it).isFile }
            ?: return fail(id, CaptureStatus.FAILED, "Аудиофайл записи не найден")
        updateCapture(id) { it.copy(status = CaptureStatus.TRANSCRIBING, message = "") }
        return try {
            val text = intelligence.transcribe(path, language(), prefs.demoExample)
            updateCapture(id) {
                it.copy(
                    title = NoteText.title(text), transcript = text, preparedText = text,
                    status = CaptureStatus.READY, message = "", simulated = false, audioFinalized = true,
                )
            }
        } catch (e: Throwable) {
            val unavailable = e.message?.contains("onDeviceSpeechUnavailable") == true
            fail(id, if (unavailable) CaptureStatus.NEEDS_MODEL else CaptureStatus.FAILED, e.message ?: "Не удалось распознать речь")
        }
    }

    override suspend fun tidy(id: String): Capture {
        val current = capture(id); val text = intelligence.tidy(current.textToSave, language())
        return updateCapture(id) {
            it.copy(
                title = NoteText.title(text), preparedText = text, draftEdited = true,
                llmApplied = false, rankingApplied = false, relevance = emptyMap(),
                status = CaptureStatus.READY, simulated = false,
            )
        }
    }
    override suspend fun rank(id: String): Capture {
        val current = capture(id); val scores = intelligence.rank(current.textToSave, data.projects, language())
        return updateCapture(id) { it.copy(title = NoteText.title(it.textToSave), relevance = scores, rankingApplied = true, simulated = false) }
    }
    override suspend fun discard(id: String) {
        val current = capture(id)
        current.audioFileName?.let { File(it).delete() }
        current.compactAudioFileName?.let { File(it).delete() }
        data = data.copy(captures = data.captures.filterNot { it.id == id }); persistData()
    }
    override suspend fun createDemo(): Capture = error("Demo mode is disabled in Android shell")

    fun newPendingAudioFile(): File = File(pendingDir, "${id()}.pcm")
    fun pendingAudioFile(): File? = pendingDir.listFiles()?.filter(File::isFile)?.sortedBy(File::lastModified)?.firstOrNull()
    fun finalizePending(file: File): File {
        val target = File(audioDir, file.name)
        check(file.renameTo(target) || runCatching { file.copyTo(target, overwrite = true); file.delete(); true }.getOrDefault(false))
        return target
    }

    fun createAudioCapture(file: File, durationSeconds: Double, waveform: List<Float>): Capture {
        val capture = Capture(
            id = id(), createdAt = now(), status = CaptureStatus.QUEUED,
            audioFileName = file.absolutePath, durationSeconds = durationSeconds,
            waveform = waveform.takeLast(256), simulated = false, audioFinalized = true,
        )
        data = data.addCapture(capture); persistData(); return capture
    }
    fun audioFile(captureId: String): File? = data.captures.firstOrNull { it.id == captureId }
        ?.audioFileName?.let(::File)?.takeIf(File::isFile)

    private fun capture(id: String): Capture = data.captures.firstOrNull { it.id == id } ?: error("Запись не найдена")
    private fun updateCapture(id: String, transform: (Capture) -> Capture): Capture {
        data = data.updateCapture(id, transform); persistData(); return capture(id)
    }
    private fun fail(id: String, status: CaptureStatus, message: String): Capture =
        updateCapture(id) { it.copy(status = status, message = message, audioFinalized = true) }

    private fun persistData() = atomicWrite(dataFile, json.encodeToString(data))
    private fun persistPreferences() = atomicWrite(prefsFile, json.encodeToString(prefs))
    private fun atomicWrite(file: File, text: String) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) { file.writeText(text); temp.delete() }
    }
}
