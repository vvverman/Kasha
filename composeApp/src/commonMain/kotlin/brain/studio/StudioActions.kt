package brain.studio

import brain.domain.RecorderPhase
import brain.domain.RecorderSessionGateway
import brain.model.Note

/**
 * Общие actions поверх platform gateways. Здесь нет знаний об ОС или файловой системе.
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
    val capture = loadedAudio ?: return false
    if (!capture.audioFinalized || recording) return false
    val duration = playback.duration.takeIf { it.isFinite() && it > 0.0 }
        ?: capture.durationSeconds.takeIf { it.isFinite() && it > 0.0 }
        ?: return false
    val target = seconds.coerceIn(0.0, duration)
    audio.playCapture(capture.id, false, target, playbackRate)
    return true
}

internal suspend fun StudioState.moveNotePin(note: Note, delta: Int): Boolean {
    if (!note.pinned || delta == 0) return false
    val projectId = note.projectId
    val ids = snapshot.notes
        .filter { it.projectId == projectId && it.pinned }
        .sortedWith(compareBy<Note> { it.pinOrder }.thenBy { it.createdAt }.thenBy { it.id })
        .map { it.id }
        .toMutableList()
    val old = ids.indexOf(note.id)
    val next = old + delta
    if (old < 0 || next !in ids.indices) return false
    ids.removeAt(old)
    ids.add(next, note.id)
    repository.orderNotePins(projectId, ids)
    refresh()
    return true
}
