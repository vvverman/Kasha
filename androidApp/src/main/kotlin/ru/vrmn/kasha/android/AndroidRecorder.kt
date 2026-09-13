package ru.vrmn.kasha.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import brain.domain.RecorderGateway
import brain.model.Capture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Тонкий Android recorder adapter. Он управляет только Android MediaRecorder и
 * app-private pending-файлом; capture/state/business rules остаются в Core/repository.
 *
 * Runtime permission здесь не запрашивается: platform adapter лишь сообщает факт
 * разрешения и отказывает честно. UX запроса permission — общий capture-flow (#25).
 */
internal class AndroidRecorder(
    context: Context,
    private val repository: AndroidStudioRepository,
    private val scope: CoroutineScope,
) : RecorderGateway, AutoCloseable {
    private val appContext = context.applicationContext
    private val control = Mutex()

    @Volatile private var currentPhase = PHASE_IDLE
    @Volatile private var recorder: MediaRecorder? = null
    private var pendingFile: File? = null
    private var recordedMillis = 0L
    private var mark: TimeMark? = null
    private val waveform = mutableListOf<Float>()

    override suspend fun hasConsent(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override suspend fun hasPending(): Boolean =
        currentPhase == PHASE_IDLE && repository.pendingFiles().isNotEmpty()

    override fun phase(): String = currentPhase

    override fun level(): Float {
        if (currentPhase != PHASE_RECORDING) return 0f
        val amplitude = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
        val normalized = sqrt((amplitude.coerceIn(0, MAX_AMPLITUDE) / MAX_AMPLITUDE.toFloat())).coerceIn(0f, 1f)
        synchronized(waveform) {
            waveform += normalized
            if (waveform.size > MAX_LIVE_SAMPLES) waveform.removeAt(0)
        }
        return normalized
    }

    override suspend fun start() = control.withLock {
        withContext(Dispatchers.IO) {
            check(currentPhase == PHASE_IDLE) { "recordingAlreadyStarted" }
            check(repository.pendingFiles().isEmpty()) { "pendingRecordingExists" }
            check(hasConsent()) { "microphonePermissionDenied" }

            val file = repository.newPendingFile()
            val created = createMediaRecorder()
            try {
                RecordingForegroundService.start(appContext)
                val prefs = repository.preferences()
                created.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioChannels(1)
                    setAudioSamplingRate(SAMPLE_RATE)
                    setAudioEncodingBitRate(prefs.bitrate * 1_000)
                    setOutputFile(file.absolutePath)
                    prepare()
                    start()
                }
                recorder = created
                pendingFile = file
                recordedMillis = 0L
                mark = TimeSource.Monotonic.markNow()
                synchronized(waveform) { waveform.clear() }
                currentPhase = PHASE_RECORDING
            } catch (error: Throwable) {
                runCatching { RecordingForegroundService.stop(appContext) }
                runCatching { created.reset() }
                created.release()
                file.delete()
                throw IllegalStateException("audioStartFailed", error)
            }
        }
    }

    override suspend fun pause() = control.withLock {
        withContext(Dispatchers.IO) {
            check(currentPhase == PHASE_RECORDING) { "recordingNotActive" }
            val active = recorder ?: error("recordingNotStarted")
            try {
                active.pause()
                recordedMillis += mark?.elapsedNow()?.inWholeMilliseconds ?: 0L
                mark = null
                currentPhase = PHASE_PAUSED
                runCatching { RecordingForegroundService.paused(appContext) }
            } catch (error: Throwable) {
                throw IllegalStateException("audioPauseFailed", error)
            }
        }
    }

    override suspend fun resume() = control.withLock {
        withContext(Dispatchers.IO) {
            check(currentPhase == PHASE_PAUSED) { "recordingNotPaused" }
            val active = recorder ?: error("recordingNotStarted")
            try {
                active.resume()
                mark = TimeSource.Monotonic.markNow()
                currentPhase = PHASE_RECORDING
                runCatching { RecordingForegroundService.recording(appContext) }
            } catch (error: Throwable) {
                throw IllegalStateException("audioResumeFailed", error)
            }
        }
    }

    override suspend fun stopAndUpload(): Capture = control.withLock {
        withContext(Dispatchers.IO) {
            check(currentPhase == PHASE_RECORDING || currentPhase == PHASE_PAUSED) { "recordingNotStarted" }
            val active = recorder ?: error("recordingNotStarted")
            val source = pendingFile ?: error("recordingNotStarted")

            if (currentPhase == PHASE_RECORDING) {
                recordedMillis += mark?.elapsedNow()?.inWholeMilliseconds ?: 0L
            }
            mark = null
            currentPhase = PHASE_FINALIZING

            try {
                active.stop()
            } catch (error: Throwable) {
                runCatching { RecordingForegroundService.stop(appContext) }
                releaseHardware()
                if (source.length() <= 0L) source.delete()
                throw IllegalStateException("audioFinalizeFailed", error)
            }
            runCatching { RecordingForegroundService.stop(appContext) }
            releaseHardware()

            val duration = (recordedMillis / 1_000.0).coerceAtLeast(0.0)
            val levels = synchronized(waveform) { waveform.toList() }
            val capture = repository.acceptPending(source, duration, levels)
            recordedMillis = 0L
            synchronized(waveform) { waveform.clear() }
            scope.launch { repository.reprocess(capture.id) }
            capture
        }
    }

    override suspend fun recoverPending(): Capture = control.withLock {
        withContext(Dispatchers.IO) {
            check(currentPhase == PHASE_IDLE) { "recordingActive" }
            val source = repository.pendingFiles().minByOrNull { it.lastModified() }
                ?: error("No pending audio")
            val duration = mediaDurationSeconds(source)
            check(duration > 0.0) { "pendingAudioUnreadable" }
            val capture = repository.acceptPending(source, duration, emptyList())
            scope.launch { repository.reprocess(capture.id) }
            capture
        }
    }

    override fun close() {
        runCatching {
            recorder?.let { active ->
                if (currentPhase == PHASE_RECORDING || currentPhase == PHASE_PAUSED) active.stop()
            }
        }
        runCatching { RecordingForegroundService.stop(appContext) }
        releaseHardware()
    }

    private fun releaseHardware() {
        runCatching { recorder?.reset() }
        recorder?.release()
        recorder = null
        pendingFile = null
        mark = null
        currentPhase = PHASE_IDLE
    }

    @Suppress("DEPRECATION")
    private fun createMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(appContext) else MediaRecorder()

    private fun mediaDurationSeconds(file: File): Double {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.div(1_000.0)
                ?: 0.0
        } catch (_: Throwable) {
            0.0
        } finally {
            retriever.release()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val MAX_AMPLITUDE = 32_767
        const val MAX_LIVE_SAMPLES = 512
        const val PHASE_IDLE = "idle"
        const val PHASE_RECORDING = "recording"
        const val PHASE_PAUSED = "paused"
        const val PHASE_FINALIZING = "finalizing"
    }
}
