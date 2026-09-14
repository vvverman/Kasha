package brain.domain

import kotlinx.serialization.Serializable

/** Фактическая фаза playback, подтверждённая platform audio adapter. */
@Serializable
enum class PlaybackPhase {
    IDLE,
    LOADING,
    PLAYING,
    PAUSED,
    UNKNOWN;

    val legacyValue: String
        get() = when (this) {
            IDLE -> "idle"
            LOADING -> "loading"
            PLAYING -> "playing"
            PAUSED -> "paused"
            UNKNOWN -> "unknown"
        }

    companion object {
        fun fromLegacy(value: String): PlaybackPhase = when (value.lowercase()) {
            "idle" -> IDLE
            "loading" -> LOADING
            "playing" -> PLAYING
            "paused" -> PAUSED
            else -> UNKNOWN
        }
    }
}

/**
 * Платформонезависимый снимок одного playback transport.
 * sourceId — id capture/source, а не путь к файлу или platform handle.
 */
@Serializable
data class PlaybackSessionState(
    val phase: PlaybackPhase = PlaybackPhase.IDLE,
    val sourceId: String? = null,
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val level: Float = 0f,
) {
    init {
        require(sourceId == null || sourceId.isNotBlank()) { "Playback source id must not be blank" }
        require(positionSeconds.isFinite() && positionSeconds >= 0.0) { "Playback position must be finite and non-negative" }
        require(durationSeconds.isFinite() && durationSeconds >= 0.0) { "Playback duration must be finite and non-negative" }
        require(level.isFinite() && level in 0f..1f) { "Playback level must be finite and normalized" }
        if (phase in setOf(PlaybackPhase.LOADING, PlaybackPhase.PLAYING, PlaybackPhase.PAUSED)) {
            require(!sourceId.isNullOrBlank()) { "Active playback phase requires a source id" }
        }
        if (durationSeconds > 0.0) {
            require(positionSeconds <= durationSeconds) { "Playback position must not exceed duration" }
        }
    }

    val occupied: Boolean get() = phase != PlaybackPhase.IDLE

    val seekable: Boolean
        get() = phase in setOf(PlaybackPhase.PLAYING, PlaybackPhase.PAUSED) &&
            !sourceId.isNullOrBlank() && durationSeconds > 0.0

    fun seekTarget(seconds: Double): Double? =
        seconds.takeIf { seekable && it.isFinite() }?.coerceIn(0.0, durationSeconds)
}

/** Общие правила взаимоисключения recorder/playback. */
data class TransportSnapshot(
    val recorderPhase: RecorderPhase,
    val playback: PlaybackSessionState,
) {
    val hasConflict: Boolean
        get() = recorderPhase != RecorderPhase.IDLE && playback.occupied

    val canStartRecording: Boolean
        get() = recorderPhase == RecorderPhase.IDLE && !playback.occupied

    /** Явный выбор другого source во время playback допустим; recorder при этом обязан быть idle. */
    val canStartPlayback: Boolean
        get() = recorderPhase == RecorderPhase.IDLE
}

/**
 * Typed capability поверх legacy AudioGateway.
 *
 * Инварианты реализации:
 * - playbackState() сообщает фактическую фазу, а не желаемое состояние UI;
 * - seekTo() адресует текущий source и сохраняет PLAYING/PAUSED для позиции внутри файла;
 * - paused playback остаётся занятым transport и не разрешает скрытый старт recorder;
 * - stop оставляет source загруженным на уровне продукта, но переводит platform playback в IDLE;
 * - platform-specific audio session, file handles и device routing остаются вне Core.
 */
interface PlaybackSessionGateway : AudioGateway {
    fun playbackState(): PlaybackSessionState
    suspend fun seekTo(positionSeconds: Double): PlaybackSessionState
}
