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
    private var waitingForExternalInput = false
    private val waveform = mutableListOf<Float>()

    init {
        IosAudioSessionBridge.observeSystemEvents(
            IosAudioSystemObserver.RECORDER,
            ::handleSystemEvent,
        )
    }

    override suspend fun permission(): RecorderPermission = when (AVAudioSession.sharedInstance().recordPermission) {
        AVAudioSessionRecordPermissionGranted -> RecorderPermission.GRANTED
        AVAudioSessionRecordPermissionDenied -> RecorderPermission.DENIED
        AVAudioSessionRecordPermissionUndetermined -> RecorderPermission.NOT_DETERMINED
        else -> RecorderPermission.UNAVAILABLE
    }

    override fun sessionState(): RecorderSessionState = state

    // Pending включает обычные pending-файлы и orphan final-файлы, если процесс умер
    // после move, но до сохранения Capture. Уже сохранённый Capture с тем же audio id
    // никогда не выдаётся вторым pending.
    override suspend fun pendingRecordings(): List<PendingRecording> {
        val knownAudio = repository.snapshot().captures.mapNotNull { it.audioFileName }.toSet()
        val knownFinalIds = knownAudio
            .filter { it.startsWith(IosPaths.audio) }
            .map { it.substringAfterLast('/') }
            .toSet()
        val ordinary = pendingFileNames()
            .filter { it != state.activeSessionId && it !in knownFinalIds }
        val orphanFinal = IosPaths.list(IosPaths.audio)
            .filter { it.endsWith(".m4a", ignoreCase = true) }
            .filter { id -> IosPaths.child(IosPaths.audio, id) !in knownAudio }
        return (ordinary + orphanFinal)
            .distinct()
            .sorted()
            .map { PendingRecording(id = it) }
    }

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
        check(!systemInterruptionActive) { "recordingUnavailableDuringInterruption" }
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
        waitingForExternalInput = false
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
        waitingForExternalInput = false
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
        waitingForExternalInput = false

        val finalPath = IosPaths.child(IosPaths.audio, source.substringAfterLast('/'))
        return try {
            IosPaths.move(source, finalPath)
            val capture = repository.createAudioCapture(finalPath, duration, waveform.toList())
            waveform.clear()
            state = RecorderSessionState()
            scope.launch { repository.reprocess(capture.id) }
            capture
        } catch (error: Throwable) {
            // Если metadata Capture не сохранилась, не теряем единственный recoverable source.
            if (IosPaths.exists(finalPath) && !IosPaths.exists(source)) {
                runCatching { IosPaths.move(finalPath, source) }
            }
            state = RecorderSessionState(
                issue = RecorderIssue(
                    RecorderIssueKind.IO_FAILURE,
                    recoverable = IosPaths.exists(source) || IosPaths.exists(finalPath),
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
        waitingForExternalInput = false
        waveform.clear()
        IosPaths.remove(source)
        if (IosPaths.exists(source)) {
            state = RecorderSessionState(
                issue = RecorderIssue(RecorderIssueKind.IO_FAILURE, recoverable = true),
            )
            error("recordingDeleteFailed")
        }
        state = RecorderSessionState()
    }

    override suspend fun recoverPending(pendingId: String): Capture {
        check(state.phase == RecorderPhase.IDLE)
        val finalPath = IosPaths.child(IosPaths.audio, pendingId)
        val pendingPath = exactPendingPath(pendingId)

        // Повтор одного recovery не создаёт второй Capture.
        repository.snapshot().captures.firstOrNull { it.audioFileName == finalPath }?.let { existing ->
            pendingPath?.let(IosPaths::remove) // только точный duplicate того же session id
            return existing
        }

        val orphanFinal = finalPath.takeIf(IosPaths::exists)
        val source = orphanFinal ?: pendingPath ?: error("No pending audio")
        val metadata = IosAudioRecovery.inspect(source)
        var moved = false

        if (source != finalPath) {
            IosPaths.move(source, finalPath)
            moved = true
        }

        return try {
            val capture = repository.createAudioCapture(
                path = finalPath,
                durationSeconds = metadata.durationSeconds,
                waveform = metadata.waveform,
            )
            // Если после старого/прерванного recovery одновременно осталась pending-копия,
            // удаляем только файл с тем же exact id после подтверждённого Capture.
            pendingPath?.takeIf { IosPaths.exists(it) }?.let(IosPaths::remove)
            scope.launch { repository.reprocess(capture.id) }
            capture
        } catch (error: Throwable) {
            if (moved && IosPaths.exists(finalPath) && pendingPath != null && !IosPaths.exists(pendingPath)) {
                runCatching { IosPaths.move(finalPath, pendingPath) }
            }
            throw error
        }
    }

    override suspend fun discardPending(pendingId: String) {
        val pending = exactPendingPath(pendingId)
        val finalPath = IosPaths.child(IosPaths.audio, pendingId)
        val known = repository.snapshot().captures.any { it.audioFileName == finalPath }

        pending?.let(IosPaths::remove)
        if (!known && IosPaths.exists(finalPath)) IosPaths.remove(finalPath)
        check(pending == null || !IosPaths.exists(pending)) { "recordingDeleteFailed" }
        check(known || !IosPaths.exists(finalPath)) { "recordingDeleteFailed" }
    }

    private fun handleSystemEvent(event: IosAudioSystemEvent) {
        val decision = IosRecorderLifecycle.reduce(
            current = IosRecorderLifecycleContext(
                state = state,
                systemInterruptionActive = systemInterruptionActive,
                waitingForExternalInput = waitingForExternalInput,
            ),
            event = event,
            recorderActuallyRecording = recorder?.recording == true,
            recorderPresent = recorder != null,
        )
        if (decision.pauseRecorder) recorder?.pause()
        state = decision.context.state
        systemInterruptionActive = decision.context.systemInterruptionActive
        waitingForExternalInput = decision.context.waitingForExternalInput
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
