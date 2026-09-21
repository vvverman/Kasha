package brain.application

import brain.domain.*
import brain.studio.AudioTelemetry

/** Выполняемая команда не подменяет фактическую фазу, сообщённую адаптером. */
enum class TransportOperation { LAUNCH, START, PAUSE, RESUME, FINISH, RECOVER, CANCEL, PLAY, SEEK }
enum class PlaybackRequestResult { STARTED, NEEDS_RECORDING_FINISH }
enum class ApplicationPollFailure { TRANSPORT, CONTENT, REMINDERS }

/** Общие факты о транспорте. Системные пути, handle и визуальные состояния здесь не хранятся. */
data class KashaTransportState(
    val recorderPhase: RecorderPhase = RecorderPhase.IDLE,
    val activeSessionId: String? = null,
    val recorderIssue: RecorderIssue? = null,
    val pendingRecordings: List<PendingRecording> = emptyList(),
    val hasPending: Boolean = false,
    val elapsedMillis: Long = 0,
    val liveWave: List<Float> = List(80) { 0f },
    val playback: PlaybackSessionState = PlaybackSessionState(),
    val loadedAudioId: String? = null,
    val playbackRate: Double = 1.0,
    val operation: TransportOperation? = null,
    internal val revision: Long = 0,
    internal val recordedMillis: Long = 0,
    internal val runningSinceMillis: Long? = null,
) {
    val recording: Boolean get() = recorderPhase != RecorderPhase.IDLE
    val recorderCanResume: Boolean get() = when (recorderPhase) {
        RecorderPhase.PAUSED -> recorderIssue?.recoverable != false
        RecorderPhase.INTERRUPTED -> recorderIssue?.recoverable == true
        else -> false
    }
    val telemetry: AudioTelemetry get() = AudioTelemetry(
        playback.phase.legacyValue, playback.positionSeconds, playback.durationSeconds, playback.level,
    )
    val conflict: Boolean get() = TransportSnapshot(recorderPhase, playback).hasConflict
}
