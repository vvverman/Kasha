package brain.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackContractTest {
    @Test
    fun activePlaybackRequiresSourceAndValidTelemetry() {
        assertFails { PlaybackSessionState(PlaybackPhase.PLAYING) }
        assertFails { PlaybackSessionState(PlaybackPhase.PAUSED, " ") }
        assertFails { PlaybackSessionState(positionSeconds = -1.0) }
        assertFails { PlaybackSessionState(positionSeconds = Double.NaN) }
        assertFails { PlaybackSessionState(durationSeconds = Double.POSITIVE_INFINITY) }
        assertFails { PlaybackSessionState(positionSeconds = 11.0, durationSeconds = 10.0) }
        assertFails { PlaybackSessionState(level = 1.1f) }

        val state = PlaybackSessionState(
            phase = PlaybackPhase.PLAYING,
            sourceId = "capture-1",
            positionSeconds = 4.0,
            durationSeconds = 10.0,
            level = 0.5f,
        )
        assertTrue(state.occupied)
        assertTrue(state.seekable)
    }

    @Test
    fun seekTargetIsClampedAndRequiresStableActiveSource() {
        val playing = PlaybackSessionState(
            phase = PlaybackPhase.PLAYING,
            sourceId = "capture-1",
            positionSeconds = 4.0,
            durationSeconds = 10.0,
        )
        assertEquals(0.0, playing.seekTarget(-5.0))
        assertEquals(6.0, playing.seekTarget(6.0))
        assertEquals(10.0, playing.seekTarget(20.0))
        assertNull(playing.seekTarget(Double.NaN))

        assertNull(PlaybackSessionState(sourceId = "capture-1", durationSeconds = 10.0).seekTarget(3.0))
        assertNull(
            PlaybackSessionState(
                phase = PlaybackPhase.PAUSED,
                sourceId = "capture-1",
                durationSeconds = 0.0,
            ).seekTarget(0.0)
        )
    }

    @Test
    fun pausedPlaybackStillBlocksRecorder() {
        val paused = PlaybackSessionState(
            phase = PlaybackPhase.PAUSED,
            sourceId = "capture-1",
            positionSeconds = 2.0,
            durationSeconds = 10.0,
        )
        val snapshot = TransportSnapshot(RecorderPhase.IDLE, paused)
        assertFalse(snapshot.canStartRecording)
        assertTrue(snapshot.canStartPlayback)
        assertFalse(snapshot.hasConflict)
    }

    @Test
    fun activeRecorderBlocksPlaybackAndConflictIsExplicit() {
        val idlePlayback = PlaybackSessionState()
        val recorderOnly = TransportSnapshot(RecorderPhase.RECORDING, idlePlayback)
        assertFalse(recorderOnly.canStartRecording)
        assertFalse(recorderOnly.canStartPlayback)
        assertFalse(recorderOnly.hasConflict)

        val conflict = TransportSnapshot(
            RecorderPhase.RECORDING,
            PlaybackSessionState(
                phase = PlaybackPhase.PLAYING,
                sourceId = "capture-1",
                durationSeconds = 10.0,
            ),
        )
        assertTrue(conflict.hasConflict)
        assertFalse(conflict.canStartRecording)
        assertFalse(conflict.canStartPlayback)
    }

    @Test
    fun legacyPhaseMappingNeverTreatsUnknownAsIdle() {
        assertEquals(PlaybackPhase.IDLE, PlaybackPhase.fromLegacy("idle"))
        assertEquals(PlaybackPhase.LOADING, PlaybackPhase.fromLegacy("loading"))
        assertEquals(PlaybackPhase.PLAYING, PlaybackPhase.fromLegacy("playing"))
        assertEquals(PlaybackPhase.PAUSED, PlaybackPhase.fromLegacy("paused"))
        assertEquals(PlaybackPhase.UNKNOWN, PlaybackPhase.fromLegacy("buffering-v2"))
        assertTrue(PlaybackSessionState(PlaybackPhase.UNKNOWN).occupied)
    }
}
