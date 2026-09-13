@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package brain.ios

import brain.domain.PendingRecording
import brain.domain.RecorderIssue
import brain.domain.RecorderIssueKind
import brain.domain.RecorderPermission
import brain.domain.RecorderPhase
import brain.domain.RecorderSessionGateway
import brain.domain.RecorderSessionState
import brain.model.Capture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.*
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import kotlin.coroutines.resume
import kotlin.math.pow

internal class IosRecorder(
    private val repository: IosRepository,
    private val scope: CoroutineScope,
) : RecorderSessionGateway {
    private var recorder: AVAudioRecorder? = null
    private var currentPath: String? = null
    private var state = RecorderSessionState()
    private var systemInterruptionActive = false
    private val waveform = mutableListOf<Float>()

    init {
        IosAudioSessionBridge.observeSystemEvents(::handleSystemEvent)
    }

    override suspend fun permission(): RecorderPermission = when (AVAudioSession.sharedInstance().recordPermission) {
        AVAudioSessionRecordPermissionGranted -> RecorderPermission.GRANTED
        AVAudioSessionRecordPermissionDenied -> RecorderPermission.DENIED
        AVAudioSessionRecordPermissionUndetermined -> RecorderPermission.NOT_DETERMINED
        else -> RecorderPermission.UNAVAILABLE
    }

    override fun sessionState(): RecorderSessionState = state

    override suspend fun pendingRecordings(): List<PendingRecording> = pendingFileNames()
        .filter { it != state.activeSessionId }
        .sorted()
        .map { PendingRecording(id = it) }

    override fun level(): Float {
        val active = recorder ?: return 0f
        if (state.phase != RecorderPhase.RECORDING) return 0f
        active.updateMeters()
        val db = active.averagePowerForChannel(0u)
        val normalized = 10.0.pow((db / 20.0).toDouble()).toFloat().coerceIn(0f, 1f)
        waveform += normalized
        if (waveform.size > 512) waveform.removeAt(0)
        return normalized
    }

    override suspend fun start() {
        check(state.phase == RecorderPhase.IDLE) { "recordingAlreadyStarted" }
        check(pendingRecordings().isEmpty()) { "pendingRecordingExists" }
        if (!requestMicrophonePermission()) {
            state = RecorderSessionState(
                issue = RecorderIssue(RecorderIssueKind.PERMISSION_DENIED, recoverable = true),
            )
            error("microphonePermissionDenied")
        }

        IosAudioSessionBridge.activateRecording()
        val sessionId = "${NSUUID().UUIDString.lowercase()}.m4a"
        val path = IosPaths.child(IosPaths.pending, sessionId)
        val settings = mapOf<Any?, Any>(
            AVFormatIDKey to kAudioFormatMPEG4AAC,
            AVSampleRateKey to 44_100.0,
            AVNumberOfChannelsKey to 1,
        )
        val created = AVAudioRecorder(NSURL.fileURLWithPath(path), settings, null)
        created.meteringEnabled = true
        if (!created.prepareToRecord() || !created.record()) {
            created.stop()
            IosAudioSessionBridge.deactivate()
            IosPaths.remove(path)
            state = RecorderSessionState(
                issue = RecorderIssue(RecorderIssueKind.IO_FAILURE, recoverable = false),
            )
            error("audioFailed")
        }

        recorder = created
        currentPath = path
        systemInterruptionActive = false
        waveform.clear()
        state = RecorderSessionState(RecorderPhase.RECORDING, sessionId)
    }

    override suspend fun pause() {
        check(state.phase == RecorderPhase.RECORDING)
        val sessionId = requireActiveSessionId()
        recorder?.pause()
        state = RecorderSessionState(RecorderPhase.PAUSED, sessionId)
    }

    override suspend fun resume() {
        check(!systemInterruptionActive) { "recordingCannotResumeDuringInterruption" }
        val sessionId = requireActiveSessionId()
        val resumable = when (state.phase) {
            RecorderPhase.PAUSED -> state.issue?.recoverable != false
            RecorderPhase.INTERRUPTED -> state.issue?.recoverable == true
            else -> false
        }
        check(resumable) { "recordingCannotResume" }

        IosAudioSessionBridge.activateRecording()
        if (recorder?.record() != true) {
            state = RecorderSessionState(
                phase = RecorderPhase.INTERRUPTED,
                activeSessionId = sessionId,
                issue = RecorderIssue(RecorderIssueKind.SESSION_LOST, recoverable = false),
            )
            error("audioFailed")
        }
        state = RecorderSessionState(RecorderPhase.RECORDING, sessionId)
    }

    override suspend fun stopAndUpload(): Capture {
        val active = recorder ?: error("recordingNotStarted")
        val source = currentPath ?: error("recordingNotStarted")
        val sessionId = requireActiveSessionId()
        val duration = active.currentTime
        state = RecorderSessionState(RecorderPhase.FINALIZING, sessionId)
        active.stop()
        IosAudioSessionBridge.deactivate()
        recorder = null
        currentPath = null
        systemInterruptionActive = false

        return try {
            val finalPath = IosPaths.child(IosPaths.audio, source.substringAfterLast('/'))
            IosPaths.move(source, finalPath)
            val capture = repository.createAudioCapture(finalPath, duration, waveform.toList())
            waveform.clear()
            state = RecorderSessionState()
            scope.launch { repository.reprocess(capture.id) }
            capture
        } catch (error: Throwable) {
            state = RecorderSessionState(
                issue = RecorderIssue(
                    RecorderIssueKind.IO_FAILURE,
                    recoverable = IosPaths.exists(source),
                ),
            )
            throw error
        }
    }

    override suspend fun cancelActive(sessionId: String) {
        check(state.activeSessionId == sessionId) { "recordingSessionMismatch" }
        val source = currentPath ?: error("recordingNotStarted")
        recorder?.stop()
        IosAudioSessionBridge.deactivate()
        recorder = null
        currentPath = null
        systemInterruptionActive = false
        waveform.clear()
        IosPaths.remove(source)
        state = RecorderSessionState()
    }

    override suspend fun recoverPending(pendingId: String): Capture {
        check(state.phase == RecorderPhase.IDLE)
        val source = exactPendingPath(pendingId) ?: error("No pending audio")
        val finalPath = IosPaths.child(IosPaths.audio, pendingId)
        IosPaths.move(source, finalPath)
        val capture = repository.createAudioCapture(finalPath, 0.0, emptyList())
        scope.launch { repository.reprocess(capture.id) }
        return capture
    }

    override suspend fun discardPending(pendingId: String) {
        val source = exactPendingPath(pendingId) ?: return
        IosPaths.remove(source)
    }

    private fun handleSystemEvent(event: IosAudioSystemEvent) {
        when (event) {
            is IosAudioSystemEvent.InterruptionBegan -> {
                systemInterruptionActive = true
                if (state.phase == RecorderPhase.RECORDING) {
                    state = RecorderSessionState(
                        phase = RecorderPhase.INTERRUPTED,
                        activeSessionId = requireActiveSessionId(),
                        issue = RecorderIssue(RecorderIssueKind.INTERRUPTION, recoverable = false),
                    )
                }
            }

            is IosAudioSystemEvent.InterruptionEnded -> {
                systemInterruptionActive = false
                if (state.phase == RecorderPhase.INTERRUPTED && state.issue?.kind == RecorderIssueKind.INTERRUPTION) {
                    state = state.copy(
                        issue = RecorderIssue(RecorderIssueKind.INTERRUPTION, recoverable = event.canResume),
                    )
                }
            }

            is IosAudioSystemEvent.RouteChanged -> {
                if (!systemInterruptionActive) handleRouteChanged(event)
            }

            is IosAudioSystemEvent.ApplicationDidBecomeActive -> {
                if (
                    !systemInterruptionActive &&
                    state.issue?.kind == RecorderIssueKind.INPUT_UNAVAILABLE &&
                    event.inputAvailable
                ) {
                    state = state.copy(
                        issue = RecorderIssue(RecorderIssueKind.INPUT_UNAVAILABLE, recoverable = true),
                    )
                }
            }
        }
    }

    private fun handleRouteChanged(event: IosAudioSystemEvent.RouteChanged) {
        val activePhase = state.phase == RecorderPhase.RECORDING
        val pausedPhase = state.phase == RecorderPhase.PAUSED
        if (!activePhase && !pausedPhase && state.phase != RecorderPhase.INTERRUPTED) return

        val externalInputLost =
            event.reason == "oldDeviceUnavailable" &&
                event.previousHadExternalInput &&
                !event.currentHasExternalInput
        val noInput = event.reason == "noSuitableRouteForCategory" || !event.inputAvailable

        if (externalInputLost || noInput) {
            val issue = RecorderIssue(
                RecorderIssueKind.INPUT_UNAVAILABLE,
                recoverable = event.inputAvailable,
            )
            state = if (activePhase) {
                RecorderSessionState(
                    phase = RecorderPhase.INTERRUPTED,
                    activeSessionId = requireActiveSessionId(),
                    issue = issue,
                )
            } else {
                state.copy(issue = issue)
            }
            return
        }

        if (
            event.reason == "newDeviceAvailable" &&
            state.issue?.kind == RecorderIssueKind.INPUT_UNAVAILABLE
        ) {
            state = state.copy(
                issue = RecorderIssue(
                    RecorderIssueKind.INPUT_UNAVAILABLE,
                    recoverable = event.inputAvailable,
                ),
            )
        }
    }

    private fun requireActiveSessionId(): String =
        state.activeSessionId ?: error("recordingNotStarted")

    private fun pendingFileNames(): List<String> = IosPaths.list(IosPaths.pending)
        .filter { it.endsWith(".m4a", ignoreCase = true) }

    private fun exactPendingPath(id: String): String? = pendingFileNames()
        .firstOrNull { it == id }
        ?.let { IosPaths.child(IosPaths.pending, it) }

    private suspend fun requestMicrophonePermission(): Boolean {
        val session = AVAudioSession.sharedInstance()
        if (session.recordPermission == AVAudioSessionRecordPermissionGranted) return true
        return suspendCancellableCoroutine { continuation ->
            session.requestRecordPermission { granted ->
                if (continuation.isActive) continuation.resume(granted)
            }
        }
    }
}
