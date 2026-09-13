package brain.domain

import brain.model.Capture

/**
 * Общий снимок capture/recovery-состояния без platform-specific путей и handle.
 * Он намеренно допускает current + pending: после сбоя это не повод удалять данные,
 * а сигнал для последовательного восстановления.
 */
data class CaptureRecoverySnapshot(
    val currentCaptureId: String?,
    val permission: RecorderPermission,
    val recorder: RecorderSessionState,
    val pending: List<PendingRecording>,
) {
    init {
        require(currentCaptureId == null || currentCaptureId.isNotBlank()) { "Current capture id must not be blank" }
        require(pending.map { it.id }.distinct().size == pending.size) { "Pending recording ids must be unique" }
    }

    val canAttemptNewRecording: Boolean
        get() = currentCaptureId == null &&
            recorder.phase == RecorderPhase.IDLE &&
            pending.isEmpty() &&
            permission in setOf(RecorderPermission.GRANTED, RecorderPermission.NOT_DETERMINED)

    /** Автовосстановление допустимо только для одного однозначного pending и без current. */
    val automaticRecoveryCandidate: PendingRecording?
        get() = pending.singleOrNull()?.takeIf {
            currentCaptureId == null && recorder.phase == RecorderPhase.IDLE
        }

    /** Current + pending сохраняются оба; восстановление выполняется последовательно. */
    val requiresSequentialRecovery: Boolean
        get() = currentCaptureId != null && pending.isNotEmpty()

    /** Активный recorder поверх current/pending — конфликт, новые транспортные действия блокируются. */
    val hasTransportConflict: Boolean
        get() = recorder.phase != RecorderPhase.IDLE && (currentCaptureId != null || pending.isNotEmpty())

    fun pending(id: String): PendingRecording? = pending.firstOrNull { it.id == id }
}

/**
 * Core-координатор destructive/recovery действий для RecorderSessionGateway.
 * Все действия адресуются opaque id и повторно сверяют фактическое состояние адаптера.
 */
class CaptureRecoveryCoordinator(
    private val recorder: RecorderSessionGateway,
) {
    suspend fun snapshot(currentCaptureId: String?): CaptureRecoverySnapshot = CaptureRecoverySnapshot(
        currentCaptureId = currentCaptureId,
        permission = recorder.permission(),
        recorder = recorder.sessionState(),
        pending = recorder.pendingRecordings(),
    )

    /**
     * Отменяет только ожидаемую активную сессию. По контракту адаптера это destructive cancel:
     * Capture/STT/AI при этом не создаются и не запускаются.
     */
    suspend fun cancelActive(expectedSessionId: String) {
        require(expectedSessionId.isNotBlank()) { "Recorder session id must not be blank" }
        val before = recorder.sessionState()
        require(before.activeSessionId == expectedSessionId) { "Recorder session changed" }
        require(before.phase != RecorderPhase.IDLE) { "Recorder session is not active" }

        recorder.cancelActive(expectedSessionId)

        val after = recorder.sessionState()
        require(after.phase == RecorderPhase.IDLE && after.activeSessionId == null) {
            "Recorder did not confirm cancellation"
        }
    }

    /** Recovery не создаёт второй inbox capture поверх уже существующего current. */
    suspend fun recoverPending(currentCaptureId: String?, pendingId: String): Capture {
        require(currentCaptureId == null) { "Current capture must be resolved before recovery" }
        val before = snapshot(currentCaptureId)
        require(before.recorder.phase == RecorderPhase.IDLE) { "Recorder must be idle before recovery" }
        require(before.pending(pendingId) != null) { "Pending recording changed" }
        return recorder.recoverPending(pendingId)
    }

    /** Удаляет только явно выбранный pending; остальные источники обязаны сохраниться. */
    suspend fun discardPending(pendingId: String) {
        require(pendingId.isNotBlank()) { "Pending recording id must not be blank" }
        val before = recorder.pendingRecordings()
        require(before.any { it.id == pendingId }) { "Pending recording changed" }

        recorder.discardPending(pendingId)

        val after = recorder.pendingRecordings()
        require(after.none { it.id == pendingId }) { "Pending recording was not deleted" }
        val untouched = before.filterNot { it.id == pendingId }.map { it.id }.toSet()
        require(untouched.all { id -> after.any { it.id == id } }) { "Another pending recording was deleted" }
    }

    /** Используется только для безопасного launch-flow: ambiguous/current случаи возвращают null. */
    suspend fun recoverAutomatically(currentCaptureId: String?): Capture? {
        val candidate = snapshot(currentCaptureId).automaticRecoveryCandidate ?: return null
        return recoverPending(currentCaptureId, candidate.id)
    }
}
