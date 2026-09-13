package brain.domain

import brain.model.Capture
import kotlinx.serialization.Serializable

/** Платформонезависимый статус разрешения на захват аудио. */
@Serializable
enum class RecorderPermission {
    NOT_DETERMINED,
    GRANTED,
    DENIED,
    RESTRICTED,
    UNAVAILABLE,
}

/** Фактическая фаза одной recorder-сессии, подтверждённая адаптером. */
@Serializable
enum class RecorderPhase {
    IDLE,
    RECORDING,
    PAUSED,
    INTERRUPTED,
    FINALIZING;

    val legacyValue: String
        get() = when (this) {
            IDLE -> "idle"
            RECORDING -> "recording"
            PAUSED -> "paused"
            INTERRUPTED -> "interrupted"
            FINALIZING -> "finalizing"
        }
}

/** Причина, которую общий state может обработать без знания конкретной ОС. */
@Serializable
enum class RecorderIssueKind {
    PERMISSION_DENIED,
    INTERRUPTION,
    INPUT_UNAVAILABLE,
    STORAGE_UNAVAILABLE,
    IO_FAILURE,
    SESSION_LOST,
    UNKNOWN,
}

@Serializable
data class RecorderIssue(
    val kind: RecorderIssueKind,
    val recoverable: Boolean = false,
)

/**
 * Снимок фактического состояния recorder.
 * activeSessionId — opaque id адаптера: это не путь к файлу и не системный handle.
 */
@Serializable
data class RecorderSessionState(
    val phase: RecorderPhase = RecorderPhase.IDLE,
    val activeSessionId: String? = null,
    val issue: RecorderIssue? = null,
) {
    init {
        require(activeSessionId == null || activeSessionId.isNotBlank()) { "Recorder session id must not be blank" }
        if (phase == RecorderPhase.IDLE) {
            require(activeSessionId == null) { "Idle recorder phase must not have a session id" }
        } else {
            require(!activeSessionId.isNullOrBlank()) { "Active recorder phase requires a session id" }
        }
    }
}

/** Устойчивая незавершённая запись, которую можно адресовать без знания файловой системы. */
@Serializable
data class PendingRecording(
    val id: String,
    val createdAt: Long? = null,
    val durationMillis: Long? = null,
) {
    init {
        require(id.isNotBlank()) { "Pending recording id must not be blank" }
        require(createdAt == null || createdAt >= 0) { "Pending recording creation time must not be negative" }
        require(durationMillis == null || durationMillis >= 0) { "Pending recording duration must not be negative" }
    }
}

/**
 * Безопасное расширение RecorderGateway для identity-aware recovery/destructive действий.
 *
 * Инварианты реализации:
 * - cancelActive(sessionId) останавливает и удаляет только указанную активную сессию,
 *   не создаёт Capture и не запускает STT/AI;
 * - recoverPending(pendingId) принимает только указанный pending-источник;
 * - discardPending(pendingId) удаляет только указанный pending-источник;
 * - opaque id стабилен как минимум до recover/discard/cancel соответствующего источника.
 *
 * Старый RecorderGateway оставлен для обратной совместимости. Общий UI/state обязан
 * предлагать destructive identity-aware действия только при наличии этого capability.
 */
interface RecorderSessionGateway : RecorderGateway {
    suspend fun permission(): RecorderPermission
    fun sessionState(): RecorderSessionState
    suspend fun pendingRecordings(): List<PendingRecording>

    suspend fun cancelActive(sessionId: String)
    suspend fun recoverPending(pendingId: String): Capture
    suspend fun discardPending(pendingId: String)

    override suspend fun hasConsent(): Boolean = permission() == RecorderPermission.GRANTED

    override suspend fun hasPending(): Boolean = pendingRecordings().isNotEmpty()

    override fun phase(): String = sessionState().phase.legacyValue

    /** Legacy recovery остаётся безопасным только при ровно одном pending. */
    override suspend fun recoverPending(): Capture {
        val pending = pendingRecordings()
        require(pending.size == 1) { "Expected exactly one pending recording" }
        return recoverPending(pending.single().id)
    }
}
