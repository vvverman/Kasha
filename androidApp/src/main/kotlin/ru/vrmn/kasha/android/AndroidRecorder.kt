package ru.vrmn.kasha.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.AudioRouting
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import brain.domain.PendingRecording
import brain.domain.RecorderIssue
import brain.domain.RecorderIssueKind
import brain.domain.RecorderPermission
import brain.domain.RecorderPhase
import brain.domain.RecorderSessionGateway
import brain.domain.RecorderSessionState
import brain.model.Capture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
 * Runtime permission здесь не запрашивается: adapter лишь сообщает фактический статус.
 * UX запроса разрешения и reconciliation принадлежат общему capture-flow.
 */
internal class AndroidRecorder(
    context: Context,
    private val repository: AndroidStudioRepository,
    private val scope: CoroutineScope,
) : RecorderSessionGateway, AutoCloseable {
    private val appContext = context.applicationContext
    private val control = Mutex()
    private val routeHandler = Handler(Looper.getMainLooper())

    @Volatile private var recorder: MediaRecorder? = null
    @Volatile private var latestLevel = 0f
    @Volatile private var session = RecorderSessionState()
    @Volatile private var routedDeviceId: Int? = null
    private var sampler: Job? = null
    private var pendingFile: File? = null
    private var activeSessionId: String? = null
    private var routingListener: AudioRouting.OnRoutingChangedListener? = null
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null
    private var recordedMillis = 0L
    private var mark: TimeMark? = null
    private val waveform = mutableListOf<Float>()

    override suspend fun permission(): RecorderPermission {
        val packageManager = appContext.packageManager
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
            return RecorderPermission.UNAVAILABLE
        }
        return if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            RecorderPermission.GRANTED
        } else {
            // Обычному приложению Android не даёт публичный API для чтения USER_SET/POLICY_FIXED.
            // DENIED/RESTRICTED уточняет permission-host после реального системного запроса.
            RecorderPermission.NOT_DETERMINED
        }
    }

    override fun sessionState(): RecorderSessionState = session

    override suspend fun pendingRecordings(): List<PendingRecording> = withContext(Dispatchers.IO) {
        val activeId = session.activeSessionId
        repository.pendingFiles()
            .filterNot { it.nameWithoutExtension == activeId }
            .map { file ->
                val duration = mediaDurationSeconds(file)
                PendingRecording(
                    id = file.nameWithoutExtension,
                    createdAt = file.lastModified().takeIf { it > 0L },
                    durationMillis = duration.takeIf { it > 0.0 }?.times(1_000.0)?.toLong(),
                )
            }
    }

    override fun level(): Float = if (session.phase == RecorderPhase.RECORDING) latestLevel else 0f

    override suspend fun start() = control.withLock {
        withContext(Dispatchers.IO) {
            check(session.phase == RecorderPhase.IDLE) { "recordingAlreadyStarted" }
            check(repository.pendingFiles().isEmpty()) { "pendingRecordingExists" }
            check(permission() == RecorderPermission.GRANTED) { "microphonePermissionDenied" }

            val file = repository.newPendingFile()
            val sessionId = file.nameWithoutExtension
            val created = createMediaRecorder()
            recorder = created
            activeSessionId = sessionId
            pendingFile = file
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
                    setOnErrorListener { source, _, _ -> handleRecorderError(source) }
                    prepare()
                }
                registerRecordingCallback(created)
                created.start()
                routedDeviceId = created.routedDevice?.id
                registerRoutingListener(created)
                recordedMillis = 0L
                mark = TimeSource.Monotonic.markNow()
                latestLevel = 0f
                synchronized(waveform) { waveform.clear() }
                session = RecorderSessionState(RecorderPhase.RECORDING, sessionId)
                startSampler(created)
            } catch (error: Throwable) {
                runCatching { RecordingForegroundService.stop(appContext) }
                sampler?.cancel()
                sampler = null
                file.delete()
                releaseSession(RecorderIssue(RecorderIssueKind.IO_FAILURE, recoverable = false))
                throw IllegalStateException("audioStartFailed", error)
            }
        }
    }

    override suspend fun pause() = control.withLock {
        withContext(Dispatchers.IO) {
            check(session.phase == RecorderPhase.RECORDING) { "recordingNotActive" }
            val active = recorder ?: error("recordingNotStarted")
            val sessionId = activeSessionId ?: error("recordingSessionMissing")
            try {
                active.pause()
                recordedMillis += mark?.elapsedNow()?.inWholeMilliseconds ?: 0L
                mark = null
                latestLevel = 0f
                session = RecorderSessionState(RecorderPhase.PAUSED, sessionId)
                runCatching { RecordingForegroundService.paused(appContext) }
                Unit
            } catch (error: Throwable) {
                throw IllegalStateException("audioPauseFailed", error)
            }
        }
    }

    override suspend fun resume() = control.withLock {
        withContext(Dispatchers.IO) {
            check(
                session.phase == RecorderPhase.PAUSED ||
                    (session.phase == RecorderPhase.INTERRUPTED && session.issue?.recoverable == true)
            ) { "recordingNotResumable" }
            val active = recorder ?: error("recordingNotStarted")
            val sessionId = activeSessionId ?: error("recordingSessionMissing")
            try {
                active.resume()
                if (isRecorderSilenced(active)) {
                    runCatching { active.pause() }
                    latestLevel = 0f
                    mark = null
                    session = RecorderSessionState(
                        phase = RecorderPhase.INTERRUPTED,
                        activeSessionId = sessionId,
                        issue = RecorderIssue(RecorderIssueKind.INTERRUPTION, recoverable = false),
                    )
                    runCatching { RecordingForegroundService.paused(appContext) }
                    return@withContext
                }
                val newDeviceId = active.routedDevice?.id
                val previousDeviceId = routedDeviceId
                if (previousDeviceId != null && newDeviceId != previousDeviceId) {
                    runCatching { active.pause() }
                    latestLevel = 0f
                    mark = null
                    routedDeviceId = newDeviceId
                    session = RecorderSessionState(
                        phase = RecorderPhase.INTERRUPTED,
                        activeSessionId = sessionId,
                        issue = RecorderIssue(
                            RecorderIssueKind.INPUT_UNAVAILABLE,
                            recoverable = false,
                        ),
                    )
                    runCatching { RecordingForegroundService.paused(appContext) }
                    return@withContext
                }
                routedDeviceId = newDeviceId ?: previousDeviceId
                mark = TimeSource.Monotonic.markNow()
                latestLevel = 0f
                session = RecorderSessionState(RecorderPhase.RECORDING, sessionId)
                runCatching { RecordingForegroundService.recording(appContext) }
                Unit
            } catch (error: Throwable) {
                throw IllegalStateException("audioResumeFailed", error)
            }
        }
    }

    override suspend fun stopAndUpload(): Capture = control.withLock {
        withContext(Dispatchers.IO) {
            check(
                session.phase == RecorderPhase.RECORDING ||
                    session.phase == RecorderPhase.PAUSED ||
                    session.phase == RecorderPhase.INTERRUPTED
            ) { "recordingNotStarted" }
            val active = recorder ?: error("recordingNotStarted")
            val source = pendingFile ?: error("recordingNotStarted")
            val sessionId = activeSessionId ?: error("recordingSessionMissing")

            if (session.phase == RecorderPhase.RECORDING) {
                recordedMillis += mark?.elapsedNow()?.inWholeMilliseconds ?: 0L
            }
            mark = null
            latestLevel = 0f
            session = RecorderSessionState(RecorderPhase.FINALIZING, sessionId)
            stopSampler()

            try {
                active.stop()
            } catch (error: Throwable) {
                runCatching { RecordingForegroundService.stop(appContext) }
                releaseSession(RecorderIssue(RecorderIssueKind.IO_FAILURE, recoverable = source.length() > 0L))
                if (source.length() <= 0L) source.delete()
                throw IllegalStateException("audioFinalizeFailed", error)
            }
            runCatching { RecordingForegroundService.stop(appContext) }
            releaseSession()

            val duration = (recordedMillis / 1_000.0).coerceAtLeast(0.0)
            val levels = synchronized(waveform) { reduceWaveform(waveform.toList(), MAX_STORED_SAMPLES) }
            val capture = repository.acceptPending(source, duration, levels)
            recordedMillis = 0L
            synchronized(waveform) { waveform.clear() }
            scope.launch { repository.reprocess(capture.id) }
            capture
        }
    }

    override suspend fun cancelActive(sessionId: String) = control.withLock {
        withContext(Dispatchers.IO) {
            check(session.activeSessionId == sessionId && session.phase != RecorderPhase.IDLE) {
                "recorderSessionMismatch"
            }
            val source = pendingFile ?: error("recordingSourceMissing")
            check(source.nameWithoutExtension == sessionId) { "recorderSessionMismatch" }

            stopSampler()
            runCatching {
                recorder?.let { active ->
                    if (session.phase != RecorderPhase.FINALIZING) active.stop()
                }
            }
            runCatching { RecordingForegroundService.stop(appContext) }
            releaseSession()
            recordedMillis = 0L
            synchronized(waveform) { waveform.clear() }
            check(!source.exists() || source.delete()) { "recordingCancelFailed" }
        }
    }

    override suspend fun recoverPending(pendingId: String): Capture = control.withLock {
        withContext(Dispatchers.IO) {
            check(session.phase == RecorderPhase.IDLE) { "recordingActive" }
            val source = repository.pendingFiles().singleOrNull { it.nameWithoutExtension == pendingId }
                ?: error("Pending recording not found")
            val analysis = AndroidPendingAudioAnalyzer.analyze(source)
            val duration = analysis?.durationSeconds?.takeIf { it > 0.0 } ?: mediaDurationSeconds(source)
            check(duration > 0.0) { "pendingAudioUnreadable" }
            val capture = repository.acceptPending(source, duration, analysis?.waveform.orEmpty())
            scope.launch { repository.reprocess(capture.id) }
            capture
        }
    }

    override suspend fun discardPending(pendingId: String) = control.withLock {
        withContext(Dispatchers.IO) {
            check(session.activeSessionId != pendingId) { "recordingActive" }
            val source = repository.pendingFiles().singleOrNull { it.nameWithoutExtension == pendingId }
                ?: return@withContext
            check(source.delete()) { "pendingDiscardFailed" }
        }
    }

    override fun close() {
        sampler?.cancel()
        sampler = null
        runCatching {
            recorder?.let { active ->
                if (session.phase != RecorderPhase.IDLE && session.phase != RecorderPhase.FINALIZING) active.stop()
            }
        }
        runCatching { RecordingForegroundService.stop(appContext) }
        releaseSession()
    }

    private fun registerRecordingCallback(active: MediaRecorder) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                val config = runCatching { active.activeRecordingConfiguration }.getOrNull()
                handleRecordingConfiguration(active, config)
            }
        }
        recordingCallback = callback
        active.registerAudioRecordingCallback(appContext.mainExecutor, callback)
    }

    private fun registerRoutingListener(active: MediaRecorder) {
        val listener = AudioRouting.OnRoutingChangedListener { routing ->
            handleRouteChanged(active, routing.routedDevice?.id)
        }
        routingListener = listener
        active.addOnRoutingChangedListener(listener, routeHandler)
    }

    private fun handleRouteChanged(source: MediaRecorder, newDeviceId: Int?) {
        scope.launch {
            control.withLock {
                if (recorder !== source || session.phase == RecorderPhase.IDLE) return@withLock
                val previous = routedDeviceId
                if (session.phase == RecorderPhase.INTERRUPTED) {
                    if (newDeviceId != previous) {
                        routedDeviceId = newDeviceId
                        val currentIssue = session.issue ?: return@withLock
                        val sessionId = activeSessionId ?: return@withLock
                        when (currentIssue.kind) {
                            RecorderIssueKind.INPUT_UNAVAILABLE -> {
                                session = RecorderSessionState(
                                    phase = RecorderPhase.INTERRUPTED,
                                    activeSessionId = sessionId,
                                    issue = RecorderIssue(
                                        RecorderIssueKind.INPUT_UNAVAILABLE,
                                        recoverable = newDeviceId != null,
                                    ),
                                )
                            }
                            RecorderIssueKind.INTERRUPTION -> {
                                session = RecorderSessionState(
                                    phase = RecorderPhase.INTERRUPTED,
                                    activeSessionId = sessionId,
                                    issue = RecorderIssue(
                                        RecorderIssueKind.INTERRUPTION,
                                        recoverable = newDeviceId != null && !isRecorderSilenced(source),
                                    ),
                                )
                            }
                            else -> Unit
                        }
                    }
                    return@withLock
                }
                if (previous == null) {
                    routedDeviceId = newDeviceId
                    return@withLock
                }
                if (newDeviceId == previous) return@withLock
                routedDeviceId = newDeviceId
                if (session.phase == RecorderPhase.RECORDING || session.phase == RecorderPhase.PAUSED) {
                    interruptActive(
                        source = source,
                        kind = RecorderIssueKind.INPUT_UNAVAILABLE,
                        recoverable = false,
                    )
                }
            }
        }
    }

    private fun handleRecordingConfiguration(source: MediaRecorder, config: AudioRecordingConfiguration?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || config == null) return
        val deviceId = config.audioDevice?.id
        if (deviceId != null) handleRouteChanged(source, deviceId)
        val silenced = config.isClientSilenced

        scope.launch {
            control.withLock {
                if (recorder !== source || session.phase == RecorderPhase.IDLE) return@withLock
                if (silenced) {
                    if (session.phase == RecorderPhase.RECORDING || session.phase == RecorderPhase.PAUSED) {
                        interruptActive(
                            source = source,
                            kind = RecorderIssueKind.INTERRUPTION,
                            recoverable = false,
                        )
                    }
                } else if (
                    session.phase == RecorderPhase.INTERRUPTED &&
                    session.issue?.kind == RecorderIssueKind.INTERRUPTION
                ) {
                    val sessionId = activeSessionId ?: return@withLock
                    session = RecorderSessionState(
                        phase = RecorderPhase.INTERRUPTED,
                        activeSessionId = sessionId,
                        issue = RecorderIssue(
                            RecorderIssueKind.INTERRUPTION,
                            recoverable = routedDeviceId != null,
                        ),
                    )
                }
            }
        }
    }

    private fun handleRecorderError(source: MediaRecorder) {
        scope.launch {
            control.withLock {
                if (recorder !== source || session.phase == RecorderPhase.IDLE) return@withLock
                interruptActive(
                    source = source,
                    kind = RecorderIssueKind.IO_FAILURE,
                    recoverable = false,
                )
            }
        }
    }

    private fun interruptActive(source: MediaRecorder, kind: RecorderIssueKind, recoverable: Boolean) {
        val sessionId = activeSessionId ?: return
        when (session.phase) {
            RecorderPhase.RECORDING -> {
                recordedMillis += mark?.elapsedNow()?.inWholeMilliseconds ?: 0L
                val paused = runCatching { source.pause() }.isSuccess
                mark = null
                latestLevel = 0f
                if (!paused) {
                    sampler?.cancel()
                    sampler = null
                    runCatching { source.stop() }
                    runCatching { RecordingForegroundService.stop(appContext) }
                    releaseSession(RecorderIssue(RecorderIssueKind.SESSION_LOST, recoverable = false))
                    return
                }
                session = RecorderSessionState(
                    phase = RecorderPhase.INTERRUPTED,
                    activeSessionId = sessionId,
                    issue = RecorderIssue(kind, recoverable = recoverable),
                )
                runCatching { RecordingForegroundService.paused(appContext) }
            }
            RecorderPhase.PAUSED -> {
                mark = null
                latestLevel = 0f
                session = RecorderSessionState(
                    phase = RecorderPhase.INTERRUPTED,
                    activeSessionId = sessionId,
                    issue = RecorderIssue(kind, recoverable = recoverable),
                )
                runCatching { RecordingForegroundService.paused(appContext) }
            }
            RecorderPhase.INTERRUPTED -> {
                session = RecorderSessionState(
                    phase = RecorderPhase.INTERRUPTED,
                    activeSessionId = sessionId,
                    issue = RecorderIssue(kind, recoverable = recoverable && session.issue?.recoverable != false),
                )
            }
            RecorderPhase.IDLE,
            RecorderPhase.FINALIZING -> Unit
        }
    }

    private fun isRecorderSilenced(active: MediaRecorder): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching { active.activeRecordingConfiguration?.isClientSilenced == true }.getOrDefault(false)
    }

    private fun startSampler(active: MediaRecorder) {
        sampler?.cancel()
        sampler = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(SAMPLE_INTERVAL_MS)
                if (session.phase != RecorderPhase.RECORDING || recorder !== active) {
                    latestLevel = 0f
                    continue
                }
                val amplitude = runCatching { active.maxAmplitude }.getOrDefault(0)
                val level = sqrt((amplitude.coerceIn(0, MAX_AMPLITUDE) / MAX_AMPLITUDE.toFloat())).coerceIn(0f, 1f)
                latestLevel = level
                synchronized(waveform) { waveform += level }
            }
        }
    }

    private suspend fun stopSampler() {
        val active = sampler ?: return
        sampler = null
        active.cancelAndJoin()
    }

    private fun releaseSession(issue: RecorderIssue? = null) {
        val active = recorder
        routingListener?.let { listener -> runCatching { active?.removeOnRoutingChangedListener(listener) } }
        routingListener = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            recordingCallback?.let { callback -> runCatching { active?.unregisterAudioRecordingCallback(callback) } }
        }
        recordingCallback = null
        runCatching { active?.reset() }
        active?.release()
        recorder = null
        pendingFile = null
        activeSessionId = null
        routedDeviceId = null
        mark = null
        latestLevel = 0f
        session = RecorderSessionState(phase = RecorderPhase.IDLE, issue = issue)
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
        const val SAMPLE_INTERVAL_MS = 65L
        const val MAX_STORED_SAMPLES = 512
    }
}

/** Сохраняет пики по всей временной шкале, а не последние N samples. */
internal fun reduceWaveform(samples: List<Float>, limit: Int): List<Float> {
    require(limit > 0)
    if (samples.size <= limit) return samples.map { it.coerceIn(0f, 1f) }
    return List(limit) { index ->
        val start = index * samples.size / limit
        val end = ((index + 1) * samples.size / limit).coerceAtMost(samples.size)
        samples.subList(start, end).maxOrNull()?.coerceIn(0f, 1f) ?: 0f
    }
}
