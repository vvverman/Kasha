package brain.runtime

import brain.domain.BrainData
import brain.domain.migrated
import brain.domain.orderNotePins
import brain.model.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.security.MessageDigest
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.time.Clock

class FileBrainStore(
    val root: Path,
    private val runtimeStatus: () -> RuntimeStatus,
    private val singleCurrent: Boolean = false,
    deferInitialization: Boolean = false,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val stateFile = root.resolve("brain.json")
    private val stateMarker = root.resolve(".brain.initialized")
    private val audioRoot = root.resolve("audio")
    private class LoadedState(var data: BrainData)
    // Неуспешная инициализация lazy не кешируется. Повтор Core перечитывает те же файлы.
    private val loadedState by lazy { loadState() }
    private var state: BrainData
        get() = loadedState.data
        set(value) { loadedState.data = value }

    init { if (!deferInitialization) loadedState }

    private fun loadState(): LoadedState {
        root.createDirectories(); audioRoot.createDirectories()
        require(!Files.isSymbolicLink(stateFile) && !Files.isSymbolicLink(audioRoot))

        // An interrupted atomic write can contain a newer user snapshot than the published file.
        // Never migrate/reconcile while such evidence exists, even when the old index is readable.
        check(!hasInterruptedAtomicWrite(root)) { "Незавершённая запись состояния сохранена для восстановления" }

        val saved = readLocalText(stateFile)
        if (saved == null) {
            // A marker is written only after a successful state commit/read. Its presence proves
            // this is not a first launch even if there is currently no audio beside the index.
            val hasAudio = Files.list(audioRoot).use { it.findAny().isPresent }
            check(!Files.exists(stateMarker, LinkOption.NOFOLLOW_LINKS) && !hasAudio) {
                "Индекс данных отсутствует; сохранённые файлы оставлены для восстановления"
            }
        }
        val loaded = saved?.let { json.decodeFromString<BrainData>(it) } ?: BrainData()
        // Both files must be readable before migrations or destructive journal reconciliation.
        // This gate also covers DesktopServices, which constructs the same filesystem store.
        PreferenceStore(root).readForStartup()
        val migrated = loaded.migrated()
        val recovered = migrated.copy(captures = migrated.captures.map {
            if (it.status.isWorking) it.copy(status = CaptureStatus.FAILED, message = "Обработка прервана; запись сохранена") else it
        })
        if (recovered != loaded) atomicWrite(stateFile, json.encodeToString(recovered))
        if (saved != null || recovered != loaded) markInitialized(stateMarker)

        Files.list(audioRoot).use { dirs -> dirs.filter { it.fileName.toString().startsWith(".deleted-") }.forEach { dir ->
            require(!Files.isSymbolicLink(dir))
            val id = dir.fileName.toString().removePrefix(".deleted-")
            if (recovered.captures.any { it.id == id }) Files.move(dir, audioRoot.resolve(id)) else dir.toFile().deleteRecursively()
        } }
        // Reopening is not proof that saved.m4a was durably published. Never delete a
        // surviving original here; only successful finalizeAudio or explicit discard may do so.
        return LoadedState(recovered)
    }

    suspend fun snapshot(): AppSnapshot = mutex.withLock {
        AppSnapshot(state.projects, state.notes, state.captures, runtimeStatus(), state.tasks)
    }

    suspend fun createProject(draft: ProjectDraft): Project = mutex.withLock {
        val id = UUID.randomUUID().toString(); commit(state.addProject(id, now(), draft)); state.projects.first { it.id == id }
    }

    suspend fun updateProject(id: String, update: ProjectUpdate): Project = mutex.withLock {
        commit(state.updateProject(id, update, now())); state.projects.first { it.id == id }
    }

    suspend fun pinProject(id: String, pinned: Boolean): Project = mutex.withLock {
        commit(state.pinProject(id, pinned)); state.projects.first { it.id == id }
    }

    suspend fun orderPins(ids: List<String>): List<Project> = mutex.withLock { commit(state.orderPins(ids)); state.projects }
    suspend fun orderProjects(ids: List<String>): List<Project> = mutex.withLock { commit(state.orderProjects(ids)); state.projects }

    suspend fun updateNote(id: String, update: NoteUpdate): Note = mutex.withLock {
        commit(state.updateNote(id, update, now())); state.notes.first { it.id == id }
    }

    suspend fun pinNote(id: String, pinned: Boolean): Note = mutex.withLock {
        commit(state.pinNote(id, pinned)); state.notes.first { it.id == id }
    }

    suspend fun orderNotePins(projectId: String, ids: List<String>): List<Note> = mutex.withLock {
        commit(state.orderNotePins(projectId, ids)); state.notes.filter { it.projectId == projectId }
    }

    suspend fun orderNotes(projectId: String, ids: List<String>): List<Note> = mutex.withLock {
        commit(state.orderNotes(projectId, ids)); state.notes.filter { it.projectId == projectId }
    }

    suspend fun updateTask(id: String, update: TaskUpdate): Task = mutex.withLock {
        commit(state.updateTask(id, update, now())); state.tasks.first { it.id == id }
    }

    suspend fun rescheduleTask(id: String, update: TaskScheduleUpdate): Task = mutex.withLock {
        commit(state.rescheduleTask(id, update, now())); state.tasks.first { it.id == id }
    }

    suspend fun completeTask(id: String): Task = mutex.withLock {
        commit(state.completeTask(id, now())); state.tasks.first { it.id == id }
    }

    suspend fun deleteTask(id: String) = mutex.withLock { commit(state.deleteTask(id)) }

    suspend fun orderTasks(ids: List<String>): List<Task> = mutex.withLock { commit(state.orderTasks(ids)); state.tasks }

    suspend fun claimTaskReminders(now: Long, zoneId: String): List<Task> = mutex.withLock {
        val (next, due) = state.claimDueReminders(now, zoneId)
        commit(next)
        due
    }

    suspend fun createCapture(fileName: String, bytes: ByteArray, requestedId: String? = null): Capture = mutex.withLock {
        require(bytes.isNotEmpty() && bytes.size <= 64 * 1024 * 1024) { "Допустим аудиофайл до 64 МБ" }
        val id = requestedId?.also { require(UUID.fromString(it).toString() == it.lowercase()) } ?: UUID.randomUUID().toString()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        state.captures.firstOrNull { it.id == id }?.let { saved ->
            require(if (saved.inputSha256.isNotEmpty()) saved.inputSha256 == hash else Files.readAllBytes(resolveAudio(saved)).contentEquals(bytes))
            return@withLock saved
        }
        require(!singleCurrent || state.captures.none { it.isInbox }) { "Сначала сохраните или удалите текущую запись" }
        val ext = fileName.substringAfterLast('.', "webm").lowercase()
        require(ext in setOf("webm", "m4a", "mp4", "ogg", "wav", "caf"))
        val dir = audioRoot.resolve(id); require(!dir.exists() && !Files.isSymbolicLink(dir)); Files.createDirectory(dir)
        val target = dir.resolve("original.$ext")
        try {
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW)
            val capture = Capture(id = id, createdAt = now(), audioFileName = relative(target), inputSha256 = hash)
            commit(state.addCapture(capture)); capture
        } catch (e: Exception) { Files.deleteIfExists(target); Files.deleteIfExists(dir); throw e }
    }

    suspend fun capture(id: String): Capture? = mutex.withLock { state.captures.firstOrNull { it.id == id } }
    suspend fun updateCapture(id: String, transform: (Capture) -> Capture): Capture = mutex.withLock {
        commit(state.updateCapture(id, transform)); state.captures.first { it.id == id }
    }
    suspend fun updateDraft(id: String, update: CaptureDraftUpdate): Capture = mutex.withLock {
        commit(state.updateDraft(id, update)); state.captures.first { it.id == id }
    }

    suspend fun distribute(id: String, request: DistributionRequest): Note = mutex.withLock {
        val (next, note) = state.distribute(id, request, UUID.randomUUID().toString(), now()); commit(next); note
    }

    suspend fun distributeTask(id: String, request: TaskDistributionRequest): Task = mutex.withLock {
        val (next, task) = state.distributeTask(id, request, UUID.randomUUID().toString(), now()); commit(next); task
    }

    suspend fun finalizeAudio(id: String, file: Path, seconds: Double, peaks: List<Float>, speed: Double): Capture = mutex.withLock {
        val old = state.captures.first { it.id == id }; require(old.isInbox)
        val original = resolveAudio(old)
        require(file.toAbsolutePath().normalize().parent == original.parent && file.fileName.toString() == "saved.m4a")
        require(!Files.isSymbolicLink(file) && Files.size(file) > 0 && seconds.isFinite() && seconds > 0)
        val changed = old.copy(
            audioFileName = relative(file), compactAudioFileName = null, compactDurationSeconds = 0.0,
            durationSeconds = seconds, waveform = peaks, savedSpeed = speed, audioFinalized = true,
            spans = emptyList(), pieces = emptyList(),
        )
        commit(state.copy(captures = state.captures.map { if (it.id == id) changed else it }))
        if (original != file.toAbsolutePath()) runCatching { Files.deleteIfExists(original) }
        changed
    }

    suspend fun discard(id: String) = mutex.withLock {
        val old = state.captures.firstOrNull { it.id == id } ?: return@withLock
        require(old.isInbox && !old.status.isWorking)
        val dir = audioRoot.resolve(id); val trash = audioRoot.resolve(".deleted-$id")
        require(!Files.isSymbolicLink(dir) && !Files.exists(trash)); Files.move(dir, trash)
        try { commit(state.copy(captures = state.captures.filterNot { it.id == id })) }
        catch (e: Exception) { Files.move(trash, dir); throw e }
        trash.toFile().deleteRecursively()
    }

    fun resolveAudio(capture: Capture, compact: Boolean = false): Path {
        val rel = (if (compact) capture.compactAudioFileName else capture.audioFileName) ?: error("Аудио ещё не готово")
        val candidate = root.resolve(rel).normalize().toAbsolutePath()
        val allowed = audioRoot.toAbsolutePath().normalize().resolve(capture.id)
        require(candidate.parent == allowed && !Files.isSymbolicLink(allowed) && !Files.isSymbolicLink(candidate))
        require(Files.isRegularFile(candidate) && candidate.toRealPath().startsWith(audioRoot.toRealPath()))
        return candidate
    }

    private fun relative(path: Path) = root.toAbsolutePath().relativize(path.toAbsolutePath()).toString().replace('\\', '/')

    private fun commit(next: BrainData) {
        if (next == state) return
        atomicWrite(stateFile, json.encodeToString(next))
        markInitialized(stateMarker)
        state = next
    }

    private fun now() = Clock.System.now().toEpochMilliseconds()
}

/** Only a confirmed missing file is optional; access/read errors must remain retryable errors. */
internal fun readLocalText(file: Path): String? {
    require(!Files.isSymbolicLink(file)) { "Хранилище не должно быть символической ссылкой" }
    return try { Files.readString(file) }
    catch (error: NoSuchFileException) {
        if (!Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) throw error
        null
    }
}

internal fun atomicWrite(file: Path, text: String) {
    val temp = Files.createTempFile(file.parent, ".save-", ".tmp")
    try {
        FileChannel.open(temp, StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true)
        }
        try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING) }
    } finally { Files.deleteIfExists(temp) }
}

/** Durable evidence that a storage document has existed successfully at least once. */
internal fun markInitialized(marker: Path) {
    if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
        require(Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) { "Некорректный маркер локального хранилища" }
        return
    }
    try {
        FileChannel.open(marker, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(byteArrayOf(1))
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    } catch (_: FileAlreadyExistsException) {
        require(Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) { "Некорректный маркер локального хранилища" }
    }
}

internal fun hasInterruptedAtomicWrite(root: Path): Boolean = Files.list(root).use { files ->
    files.anyMatch { it.fileName.toString().let { name -> name.startsWith(".save-") && name.endsWith(".tmp") } }
}
