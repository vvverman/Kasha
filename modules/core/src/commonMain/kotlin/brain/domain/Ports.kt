package brain.domain

import brain.model.*
import brain.studio.AudioTelemetry

/**
 * Единственная граница между Kasha Core и платформой.
 * Платформенные приложения реализуют диск/БД, микрофон, проигрывание и локальный ИИ,
 * но бизнес-правила, модели и сортировки остаются в Core.
 */
interface BrainRepository {
    suspend fun snapshot(): AppSnapshot
    suspend fun createProject(draft: ProjectDraft): Project
    suspend fun updateProject(id: String, update: ProjectUpdate): Project
    suspend fun pinProject(id: String, pinned: Boolean): Project
    suspend fun orderPins(ids: List<String>)
    suspend fun orderProjects(ids: List<String>)
    suspend fun updateCaptureDraft(id: String, update: CaptureDraftUpdate): Capture
    suspend fun distribute(id: String, request: DistributionRequest): Note
    suspend fun distributeTask(id: String, request: TaskDistributionRequest): Task
    suspend fun updateNote(id: String, update: NoteUpdate): Note
    suspend fun pinNote(id: String, pinned: Boolean): Note = error("Закрепление заметок не поддержано этим адаптером")
    suspend fun orderNotes(projectId: String, ids: List<String>)
    suspend fun updateTask(id: String, update: TaskUpdate): Task
    suspend fun rescheduleTask(id: String, update: TaskScheduleUpdate): Task = error("Изменение срока задачи не поддержано этим адаптером")
    suspend fun completeTask(id: String): Task = error("Архив задач не поддержан этим адаптером")
    suspend fun deleteTask(id: String): Unit = error("Удаление задач не поддержано этим адаптером")
    suspend fun orderTasks(ids: List<String>)
    suspend fun claimTaskReminders(now: Long, zoneId: String): List<Task> = emptyList()
    suspend fun reprocess(id: String): Capture
}

interface RecorderGateway {
    suspend fun hasConsent(): Boolean
    suspend fun hasPending(): Boolean
    fun phase(): String
    fun level(): Float = 0f
    suspend fun start()
    suspend fun pause()
    suspend fun resume()
    suspend fun stopAndUpload(): Capture
    suspend fun recoverPending(): Capture
}

interface AudioGateway {
    suspend fun playCapture(captureId: String, compact: Boolean = false, fromSeconds: Double = 0.0, rate: Double = 1.0)
    suspend fun pause() {}
    suspend fun resume() {}
    fun telemetry(): AudioTelemetry = AudioTelemetry()
    fun stop()
}
