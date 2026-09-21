package brain.studio

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import brain.model.CaptureStatus
import brain.model.CaptureTextVariant
import kotlinx.coroutines.launch

@Composable
internal fun HomeScreen(s: StudioState) {
    Box(Modifier.fillMaxSize()) {
        KashaProceduralBackground(Modifier.matchParentSize(), home = true)
        HomeStateContent(s)
    }
}

@Composable
private fun HomeStateContent(s: StudioState) {
    val scope = rememberCoroutineScope()
    val c = MaterialTheme.colorScheme
    val application by s.application.state.collectAsState()
    val progressKey = captureProgressKey(application)
    when {
        progressKey != null -> BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxHeight < 460.dp
            Column(
                Modifier.fillMaxSize().padding(vertical = if (compact) 12.dp else 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!compact) {
                    KashaRecordingOrb(KashaOrbState.PROCESSING)
                    Spacer(Modifier.height(24.dp))
                } else {
                    KashaProcessingRing(Modifier.size(56.dp))
                    Spacer(Modifier.height(16.dp))
                }
                Text(
                    KashaCopy.text(s.language, progressKey) ?: s.tr(progressKey),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                if (s.repository.simulated) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        s.tr("demoNotice"),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        s.recording -> RecordingHome(s)

        s.current != null -> Column(Modifier.fillMaxSize().padding(top = 12.dp, bottom = 12.dp)) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (s.current?.status == CaptureStatus.NEEDS_MODEL) {
                    Text(KashaCopy.text(s.language, "captureNeedsModel").orEmpty(),
                        style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Action(s.tr("settings"), { s.navigate(Tab.SETTINGS) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Action(s.tr("retry"), { scope.launch { s.retry() } }, enabled = !s.busy, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(16.dp))
                }
                KashaTextTabs(
                    items = listOf(
                        KashaCopy.text(s.language, "transcription") ?: "Транскрибация",
                        KashaCopy.text(s.language, "normalization") ?: "Нормализация",
                    ),
                    selectedIndex = if (s.captureTextVariant == CaptureTextVariant.TRANSCRIPTION) 0 else 1,
                    onSelect = { index ->
                        val variant = if (index == 0) CaptureTextVariant.TRANSCRIPTION else CaptureTextVariant.NORMALIZATION
                        scope.launch { s.selectCaptureTextVariant(variant) }
                    },
                )
                Spacer(Modifier.height(14.dp))
                KashaNoteText(
                    value = s.text,
                    onValueChange = s::editText,
                    placeholder = KashaCopy.text(s.language, "noteText") ?: s.tr("body"),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(20.dp))
                if (s.current?.simulated == true) {
                    Text(s.tr("demoNotice"), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
                if (s.current?.status == CaptureStatus.FAILED) {
                    Text(s.tr("processingFailed"), style = MaterialTheme.typography.bodySmall, color = c.error)
                    Spacer(Modifier.height(10.dp))
                    Action(s.tr("retry"), { scope.launch { s.retry() } }, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(14.dp))
            }
            Spacer(Modifier.height(10.dp))
            Action(
                KashaCopy.text(s.language, "reprocess") ?: "Обработать заново",
                { scope.launch { s.reprocessCurrentText() } },
                glyph = Glyph.TEXT_PROCESSING,
                enabled = s.text.isNotBlank() && !s.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            ResultActions(s)
        }

        else -> BoxWithConstraints(Modifier.fillMaxSize()) {
            val compactHeight = maxHeight < 460.dp || LocalDensity.current.fontScale > 1.3f
            Column(
                Modifier.fillMaxSize().then(if (compactHeight) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(top = 10.dp, bottom = 10.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(s.tr("emptyTitle"), style = MaterialTheme.typography.displayMedium, letterSpacing = (-1.2).sp)
                Spacer(Modifier.height(12.dp))
                Text(
                    s.tr("emptyBody"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = 320.dp),
                )
                Box(Modifier.then(if (!compactHeight) Modifier.weight(1f) else Modifier.padding(vertical = 16.dp))
                    .fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (!compactHeight) KashaRecordingOrb(
                        KashaOrbState.IDLE,
                        Modifier.semantics { contentDescription = s.tr("record") }
                            .clickable(enabled = !s.pending && !s.controlBusy, role = Role.Button) {
                                scope.launch { s.startRecording() }
                            },
                    )
                    else if (!s.pending) Action(s.tr("record"), { scope.launch { s.startRecording() } },
                        primary = true, enabled = !s.controlBusy, modifier = Modifier.fillMaxWidth())
                }
                if (s.pending) {
                    Text(s.tr("recoveryNotice"), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Action(s.tr("recover"), { scope.launch { s.recover() } }, primary = true, modifier = Modifier.fillMaxWidth())
                } else if (s.repository.simulated) {
                    QuietAction(s.tr("demoButton"), { scope.launch { s.demo() } })
                }
            }
        }
    }
}

@Composable
private fun RecordingHome(s: StudioState) {
    val c = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val height = maxHeight
        val showOrb = height >= 460.dp && LocalDensity.current.fontScale <= 1.3f
        val compactWave = height < 620.dp
        val activelyRecording = s.recordPhase == "recording"
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = if (height >= 620.dp) 24.dp else 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(clock(s.elapsed), style = MaterialTheme.typography.displayLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                recordingStatusKey(s.recordPhase).let { KashaCopy.text(s.language, it) ?: s.tr(it) },
                style = MaterialTheme.typography.bodySmall,
                color = c.onSurfaceVariant,
            )
            Spacer(Modifier.height(if (height >= 620.dp) 28.dp else 16.dp))
            if (showOrb) {
                KashaRecordingOrb(
                    state = if (activelyRecording) KashaOrbState.RECORDING else KashaOrbState.PAUSED,
                    peaks = s.liveWave,
                    level = if (activelyRecording) s.liveWave.lastOrNull() ?: 0f else 0f,
                )
            } else {
                KashaWaveform(
                    peaks = s.liveWave,
                    modifier = Modifier.fillMaxWidth().height(if (compactWave) 72.dp else 96.dp),
                )
            }
        }
    }
}

@Composable
private fun ResultActions(s: StudioState) {
    val scope = rememberCoroutineScope()
    val captureId = s.current?.id
    val canSave = s.text.isNotBlank() && !s.busy && s.current?.audioFinalized == true
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val horizontal = actionsFit(maxWidth,
            listOf(KashaCopy.text(s.language, "sendToNotes") ?: s.tr("send"), KashaCopy.text(s.language, "sendToTasks") ?: "В задачи"),
            glyph = true, leading = 48.dp)
        if (horizontal) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconAction(s.tr("cancelNote"), Glyph.DELETE, { captureId?.let { s.requestCaptureDiscard(it) } })
                Action(
                    KashaCopy.text(s.language, "sendToNotes") ?: s.tr("send"),
                    { scope.launch { s.sendToNotes() } },
                    primary = true,
                    glyph = Glyph.SEND,
                    modifier = Modifier.weight(1f),
                    enabled = canSave,
                )
                Action(
                    KashaCopy.text(s.language, "sendToTasks") ?: "В задачи",
                    { scope.launch { s.sendToTasks() } },
                    glyph = Glyph.TASKS,
                    modifier = Modifier.weight(1f),
                    enabled = canSave,
                )
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconAction(s.tr("cancelNote"), Glyph.DELETE, { captureId?.let { s.requestCaptureDiscard(it) } })
                    Spacer(Modifier.width(10.dp))
                    Action(
                        KashaCopy.text(s.language, "sendToNotes") ?: s.tr("send"),
                        { scope.launch { s.sendToNotes() } },
                        primary = true,
                        glyph = Glyph.SEND,
                        modifier = Modifier.weight(1f),
                        enabled = canSave,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Action(
                    KashaCopy.text(s.language, "sendToTasks") ?: "В задачи",
                    { scope.launch { s.sendToTasks() } },
                    glyph = Glyph.TASKS,
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
internal fun GlobalPlayer(s: StudioState) {
    val scope = rememberCoroutineScope(); val c = MaterialTheme.colorScheme; val loaded = s.loadedAudio
    val captureHome = s.tab == Tab.HOME && s.taskScheduleTarget == null &&
        !s.choosingProject && s.editingProjectId == null
    val presentation = captureTransportPresentation(captureHome, s.recording, s.current != null,
        loaded != null || s.playback.phase != "idle", s.pending, s.controlBusy)
    if (presentation == CaptureTransportPresentation.HIDDEN) return
    if (s.recording) {
        // Один call-site сохраняет identity transport controls при переходе
        // между expanded Home и compact представлением других вкладок.
        RecordingControls(s, compact = presentation == CaptureTransportPresentation.COMPACT)
        return
    }
    KashaPanel(Modifier.fillMaxWidth(), padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                s.controlBusy -> KashaProcessingRing(Modifier.size(46.dp))
                s.playback.phase == "playing" -> IconAction(s.tr("pause"), Glyph.PAUSE, { scope.launch { s.pausePlayback() } }, true)
                s.playback.phase == "paused" -> IconAction(s.tr("resume"), Glyph.PLAY, { scope.launch { s.resumePlayback() } }, true)
                loaded?.audioFinalized == true -> IconAction(s.tr("play"), Glyph.PLAY, { scope.launch { s.play() } }, true)
                s.current == null && !s.pending -> IconAction(s.tr("record"), Glyph.RECORD, { scope.launch { s.startRecording() } }, true)
                s.working -> KashaProcessingRing(Modifier.size(46.dp))
                else -> Spacer(Modifier.size(12.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (loaded?.audioFinalized == true) {
                    Text(loaded.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val duration = s.playback.duration.takeIf { it > 0.0 } ?: loaded.durationSeconds
                    KashaWaveform(
                        loaded.waveform,
                        Modifier.fillMaxWidth().height(22.dp).pointerInput(loaded.id, duration) {
                            detectTapGestures { offset ->
                                if (duration > 0.0 && size.width > 0) {
                                    val target = duration * (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    scope.launch { s.seekPlayback(target) }
                                }
                            }
                        },
                        if (duration > 0) (s.playback.position / duration).toFloat() else 0f,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(clock(s.playback.position.toLong()), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                        Text(clock(duration.toLong()), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                    }
                } else {
                    Text(s.tr(if (s.pending) "recoveryNotice" else "preparing"),
                        style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(10.dp))
            when {
                s.playback.phase != "idle" -> IconAction(s.tr("stop"), Glyph.STOP, s::stopPlayback)
                s.current == null && loaded != null && !s.pending -> IconAction(s.tr("record"), Glyph.RECORD, { scope.launch { s.startRecording() } })
            }
        }
    }
}

/** Подпись следует фактической фазе адаптера, а не двоичному recording/paused. */
internal fun recordingStatusKey(phase: String): String = when (phase) {
    "recording" -> "recording"
    "paused" -> "paused"
    "interrupted" -> "captureInterrupted"
    "finalizing" -> "captureFinalizing"
    else -> "audioFailed"
}

/** На Главной волна уже показана выше; в других разделах тот же transport становится compact. */
@Composable
private fun RecordingControls(s: StudioState, compact: Boolean) {
    val scope = rememberCoroutineScope()
    val c = MaterialTheme.colorScheme
    val id = s.activeRecordingSessionId
    val enabled = !s.controlBusy && s.recordPhase != "finalizing"
    val pause = s.recordPhase == "recording"
    val primaryLabel = s.tr(if (pause) "pause" else "resume")
    val primaryAction: () -> Unit = {
        scope.launch { if (pause) s.pauseRecording() else s.resumeRecording() }
    }
    val cancel: () -> Unit = {
        id?.let { scope.launch { s.requestRecordingCancellation(it) } }
    }
    val finish: () -> Unit = { scope.launch { s.stopRecording() } }
    if (!enabled) {
        if (compact) {
            KashaPanel(Modifier.fillMaxWidth(), padding = 0.dp) {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center) {
                    KashaProcessingRing(Modifier.size(48.dp))
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center) {
                KashaProcessingRing(Modifier.size(48.dp))
            }
        }
        return
    }
    if (LocalDensity.current.fontScale > 1.3f) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Action(primaryLabel, primaryAction, primary = true,
                enabled = pause || s.recorderCanResume, modifier = Modifier.fillMaxWidth())
            Action(s.tr("submitRecording"), finish, modifier = Modifier.fillMaxWidth())
            Action(s.tr("delete"), cancel, enabled = id != null, modifier = Modifier.fillMaxWidth())
        }
        return
    }
    Box(Modifier.fillMaxWidth()) {
        if (compact) {
            KashaPanel(Modifier.fillMaxWidth(), padding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.size(46.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        KashaWaveform(s.liveWave, Modifier.fillMaxWidth().height(23.dp))
                        Text(clock(s.elapsed), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(10.dp))
                    IconAction(s.tr("delete"), Glyph.DELETE, {
                        if (id == null) s.error = "audioFailed" else cancel()
                    })
                    Spacer(Modifier.width(8.dp))
                    Action(s.tr("submitRecording"), finish, primary = true, glyph = Glyph.SEND)
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RecordingControl(s.tr("delete"), Glyph.DELETE, cancel, Modifier.weight(1f), enabled = id != null)
                Spacer(Modifier.weight(1f))
                RecordingControl(s.tr("submitRecording"), Glyph.STOP, finish, Modifier.weight(1f))
            }
        }
        RecordingPrimaryControl(
            label = primaryLabel,
            glyph = if (pause) Glyph.PAUSE else Glyph.RECORD,
            onClick = primaryAction,
            compact = compact,
            enabled = pause || s.recorderCanResume,
            modifier = if (compact) Modifier.padding(start = 12.dp).align(Alignment.CenterStart)
                else Modifier.padding(top = 8.dp).align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun RecordingPrimaryControl(
    label: String,
    glyph: Glyph,
    onClick: () -> Unit,
    compact: Boolean,
    enabled: Boolean,
    modifier: Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        KashaIconButton(
            label,
            glyph,
            onClick,
            Modifier.size(if (compact) 46.dp else 80.dp, if (compact) 46.dp else 56.dp),
            filled = true,
            enabled = enabled,
        )
        if (!compact) {
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun RecordingControl(label: String, glyph: Glyph, onClick: () -> Unit,
    modifier: Modifier, primary: Boolean = false, enabled: Boolean = true) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        KashaIconButton(label, glyph, onClick,
            Modifier.size(if (primary) 80.dp else 48.dp, 56.dp), filled = primary, enabled = enabled)
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
    }
}
