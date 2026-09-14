package brain.studio

import brain.domain.PlaybackSessionGateway
import brain.domain.RecorderPhase
import brain.domain.RecorderSessionGateway
import brain.model.Note

/**
 * Общие actions поверх platform gateways. Здесь нет знаний об ОС или файловой системе.
 * Команды контента делегируются API Core; транспорт переносится следующим срезом.
 */
internal suspend fun StudioState.cancelActiveRecording(): Boolean {
    val gateway = recorder as? RecorderSessionGateway ?: return false
    val session = gateway.sessionState()
    val sessionId = session.activeSessionId ?: return false
    if (session.phase == RecorderPhase.IDLE) return true
    gateway.cancelActive(sessionId)
    return gateway.sessionState().phase == RecorderPhase.IDLE
}

internal suspend fun StudioState.seekPlayback(seconds: Double): Boolean {
    val gateway = audio as? PlaybackSessionGateway ?: return false
    if (recording) return false
    val before = gateway.playbackState()
    val target = before.seekTarget(seconds) ?: return false
    val after = gateway.seekTo(target)
    if (after.sourceId != before.sourceId) return false
    if (target < before.durationSeconds && after.phase != before.phase) return false
    return true
}

internal suspend fun StudioState.moveNotePin(note: Note, delta: Int): Boolean =
    moveNotePinInCore(note, delta)
