package ru.vrmn.kasha.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import brain.domain.RecorderGateway
import brain.model.Capture
import brain.studio.SignalLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

internal class AndroidRecorder(
    private val context: Context,
    private val repository: AndroidRepository,
    private val scope: CoroutineScope,
) : RecorderGateway, AutoCloseable {
    @Volatile private var currentPhase = "idle"
    @Volatile private var signal = 0f
    @Volatile private var running = false
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private var pending: File? = null
    private val waveform = mutableListOf<Float>()

    override suspend fun hasConsent(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override suspend fun hasPending(): Boolean = currentPhase == "idle" && repository.pendingAudioFile() != null
    override fun phase(): String = currentPhase
    override fun level(): Float = if (currentPhase == "recording") signal else 0f

    override suspend fun start() = withContext(Dispatchers.IO) {
        check(currentPhase == "idle") { "recordingAlreadyStarted" }
        check(repository.pendingAudioFile() == null) { "pendingRecordingExists" }
        check(hasConsent()) { "microphonePermissionDenied" }

        val min = AudioRecord.getMinBufferSize(
            AndroidIntelligence.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(min > 0) { "audioFailed" }
        val created = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            AndroidIntelligence.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min * 2, 8192),
        )
        check(created.state == AudioRecord.STATE_INITIALIZED) { "audioFailed" }

        val file = repository.newPendingAudioFile()
        val output = FileOutputStream(file)
        recorder = created
        pending = file
        waveform.clear()
        running = true
        currentPhase = "recording"
        created.startRecording()

        worker = thread(name = "Kasha-Android-Microphone", isDaemon = true) {
            val buffer = ByteArray(maxOf(min, 4096))
            try {
                output.use { stream ->
                    while (running) {
                        if (currentPhase != "recording") {
                            Thread.sleep(20)
                            continue
                        }
                        val count = created.read(buffer, 0, buffer.size)
                        if (count > 0 && currentPhase == "recording") {
                            stream.write(buffer, 0, count)
                            signal = SignalLevel.pcm16(buffer, count)
                            synchronized(waveform) {
                                waveform += signal
                                if (waveform.size > 512) waveform.removeAt(0)
                            }
                        }
                    }
                    stream.flush()
                }
            } catch (_: Throwable) {
            } finally {
                signal = 0f
            }
        }
    }

    override suspend fun pause() = withContext(Dispatchers.IO) {
        check(currentPhase == "recording")
        currentPhase = "paused"
        signal = 0f
        runCatching { recorder?.stop() }
    }

    override suspend fun resume() = withContext(Dispatchers.IO) {
        check(currentPhase == "paused")
        recorder?.startRecording()
        currentPhase = "recording"
    }

    override suspend fun stopAndUpload(): Capture = withContext(Dispatchers.IO) {
        stopHardware()
        recoverPending()
    }

    override suspend fun recoverPending(): Capture = withContext(Dispatchers.IO) {
        check(currentPhase == "idle")
        val source = repository.pendingAudioFile() ?: error("No pending audio")
        check(source.length() >= 2L) { "audioFailed" }
        val final = repository.finalizePending(source)
        val duration = final.length().toDouble() / (AndroidIntelligence.SAMPLE_RATE * 2.0)
        val levels = synchronized(waveform) { waveform.toList() }
        val capture = repository.createAudioCapture(final, duration, levels)
        synchronized(waveform) { waveform.clear() }
        scope.launch { repository.reprocess(capture.id) }
        capture
    }

    private fun stopHardware() {
        running = false
        runCatching { recorder?.stop() }
        worker?.join(2500)
        recorder?.release()
        recorder = null
        worker = null
        pending = null
        signal = 0f
        currentPhase = "idle"
    }

    override fun close() = stopHardware()
}
