@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package brain.ios

import brain.domain.RecorderGateway
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
) : RecorderGateway {
    private var recorder: AVAudioRecorder? = null
    private var currentPath: String? = null
    private var currentPhase = "idle"
    private val waveform = mutableListOf<Float>()

    override suspend fun hasConsent(): Boolean =
        AVAudioSession.sharedInstance().recordPermission == AVAudioSessionRecordPermissionGranted

    override suspend fun hasPending(): Boolean =
        currentPhase == "idle" && pendingPath() != null

    override fun phase(): String = currentPhase

    override fun level(): Float {
        val active = recorder ?: return 0f
        if (currentPhase != "recording") return 0f
        active.updateMeters()
        val db = active.averagePowerForChannel(0u)
        val normalized = 10.0.pow((db / 20.0).toDouble()).toFloat().coerceIn(0f, 1f)
        waveform += normalized
        if (waveform.size > 512) waveform.removeAt(0)
        return normalized
    }

    override suspend fun start() {
        check(currentPhase == "idle") { "recordingAlreadyStarted" }
        check(pendingPath() == null) { "pendingRecordingExists" }
        check(requestMicrophonePermission()) { "microphonePermissionDenied" }

        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayAndRecord, error = null)

        val path = IosPaths.child(IosPaths.pending, "${NSUUID().UUIDString.lowercase()}.m4a")
        val settings = mapOf<Any?, Any>(
            AVFormatIDKey to kAudioFormatMPEG4AAC,
            AVSampleRateKey to 44_100.0,
            AVNumberOfChannelsKey to 1,
        )
        val created = AVAudioRecorder(NSURL.fileURLWithPath(path), settings, null)
        created.meteringEnabled = true
        check(created.prepareToRecord()) { "audioFailed" }
        check(created.record()) { "audioFailed" }

        recorder = created
        currentPath = path
        waveform.clear()
        currentPhase = "recording"
    }

    override suspend fun pause() {
        check(currentPhase == "recording")
        recorder?.pause()
        currentPhase = "paused"
    }

    override suspend fun resume() {
        check(currentPhase == "paused")
        check(recorder?.record() == true) { "audioFailed" }
        currentPhase = "recording"
    }

    override suspend fun stopAndUpload(): Capture {
        val active = recorder ?: error("recordingNotStarted")
        val source = currentPath ?: error("recordingNotStarted")
        val duration = active.currentTime
        active.stop()
        recorder = null
        currentPath = null
        currentPhase = "idle"

        val finalPath = IosPaths.child(IosPaths.audio, source.substringAfterLast('/'))
        IosPaths.move(source, finalPath)
        val capture = repository.createAudioCapture(finalPath, duration, waveform.toList())
        waveform.clear()
        scope.launch { repository.reprocess(capture.id) }
        return capture
    }

    override suspend fun recoverPending(): Capture {
        check(currentPhase == "idle")
        val source = pendingPath() ?: error("No pending audio")
        val finalPath = IosPaths.child(IosPaths.audio, source.substringAfterLast('/'))
        IosPaths.move(source, finalPath)
        val capture = repository.createAudioCapture(finalPath, 0.0, emptyList())
        scope.launch { repository.reprocess(capture.id) }
        return capture
    }

    private fun pendingPath(): String? = IosPaths.list(IosPaths.pending)
        .firstOrNull { it.endsWith(".m4a", ignoreCase = true) }
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
