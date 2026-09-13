package brain.model

import kotlinx.serialization.Serializable

@Serializable enum class SortMode { ALPHABETICAL, CREATED, UPDATED, MANUAL }

@Serializable data class Project(
    val id: String,
    val title: String,
    val description: String = "",
    val instruction: String = "",
    val pinned: Boolean = false,
    val pinOrder: Int = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val manualOrder: Int = 0,
)

@Serializable data class Note(
    val id: String,
    val projectId: String,
    val title: String,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val pinOrder: Int = 0,
    val manualOrder: Int = 0,
)

@Serializable enum class ReminderRepeat {
    TEN_MINUTES,
    THIRTY_MINUTES,
    HOURLY,
    DAILY,
    WEEKLY,
    WEEKENDS,
    WEEKDAYS,
}

@Serializable data class Task(
    val id: String,
    val projectId: String? = null,
    val text: String,
    val createdAt: Long,
    val updatedAt: Long,
    val manualOrder: Int = 0,
    val dueAt: Long = 0,
    val reminderRepeat: ReminderRepeat = ReminderRepeat.HOURLY,
    val nextReminderAt: Long = dueAt,
    val completedAt: Long? = null,
) {
    val completed: Boolean get() = completedAt != null
}

@Serializable enum class CaptureStatus {
    RECORDING, QUEUED, TRANSCRIBING, COMPACTING, POLISHING, READY, NEEDS_MODEL, FAILED;
    val isWorking: Boolean get() = this in setOf(RECORDING, QUEUED, TRANSCRIBING, COMPACTING, POLISHING)
}

@Serializable data class Capture(
    val id: String,
    val createdAt: Long,
    val title: String = "Новая запись",
    val transcript: String = "",
    val preparedText: String = "",
    val status: CaptureStatus = CaptureStatus.QUEUED,
    val message: String = "",
    val audioFileName: String? = null,
    val noteId: String? = null,
    val taskId: String? = null,
    val appendedAt: Long? = null,
    val relevance: Map<String, Int> = emptyMap(),
    val draftEdited: Boolean = false,
    val llmApplied: Boolean = false,
    val rankingApplied: Boolean = false,
    val durationSeconds: Double = 0.0,
    val compactAudioFileName: String? = null,
    val compactDurationSeconds: Double = 0.0,
    val pieces: List<TranscriptPiece> = emptyList(),
    val spans: List<AudioSpan> = emptyList(),
    val simulated: Boolean = false,
    val waveform: List<Float> = emptyList(),
    val savedSpeed: Double = 1.0,
    val inputSha256: String = "",
    val audioFinalized: Boolean = false,
) {
    val textToSave: String get() = if (draftEdited) preparedText else preparedText.ifBlank { transcript }
    val isInbox: Boolean get() = noteId == null && taskId == null
}

@Serializable data class RuntimeStatus(
    val whisperConfigured: Boolean = false,
    val llmConfigured: Boolean = false,
    val localOnly: Boolean = true,
    val message: String = "",
    val simulated: Boolean = false,
)

@Serializable data class AppSnapshot(
    val projects: List<Project> = emptyList(),
    val notes: List<Note> = emptyList(),
    val captures: List<Capture> = emptyList(),
    val runtime: RuntimeStatus = RuntimeStatus(),
    val tasks: List<Task> = emptyList(),
)

@Serializable data class ProjectDraft(val title: String, val description: String = "", val instruction: String = "")
@Serializable data class ProjectUpdate(val title: String, val description: String = "", val instruction: String = "")
@Serializable data class PinRequest(val pinned: Boolean)
@Serializable data class PinOrderRequest(val ids: List<String>)
@Serializable data class OrderRequest(val ids: List<String>)
@Serializable data class CaptureDraftUpdate(val title: String = "", val text: String)
@Serializable data class NoteUpdate(val title: String = "", val body: String)
@Serializable data class TaskUpdate(val text: String)
@Serializable data class TaskScheduleUpdate(val dueAt: Long, val reminderRepeat: ReminderRepeat)
@Serializable data class DistributionRequest(val projectId: String, val noteId: String? = null, val title: String? = null)
@Serializable data class TaskDistributionRequest(
    val projectId: String? = null,
    val dueAt: Long = Long.MAX_VALUE,
    val reminderRepeat: ReminderRepeat = ReminderRepeat.HOURLY,
)
@Serializable data class TranscriptPiece(val start: Double, val end: Double, val text: String)
@Serializable data class AudioSpan(val originalStart: Double, val duration: Double, val compactStart: Double)
@Serializable data class ApiError(val error: String)
