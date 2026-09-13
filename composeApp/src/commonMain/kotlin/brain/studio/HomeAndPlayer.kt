package brain.studio

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import brain.model.CaptureStatus
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
    when {
        s.recording -> RecordingHome(s)

        s.working || (s.busy && s.current != null) -> BoxWithConstraints(Modifier.fillMaxSize()) {
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
                    s.tr(
                        when (s.current?.status) {
                            CaptureStatus.TRANSCRIBING -> "transcribing"
                            CaptureStatus.COMPACTING -> "compacting"
                            else -> "preparing"
                        }
                    ),
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

        s.current != null -> Column(Modifier.fillMaxSize().padding(top = 12.dp, bottom = 12.dp)) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
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
                s.tr("tidy"),
                { scope.launch { s.tidy() } },
                glyph = Glyph.TEXT_PROCESSING,
                enabled = s.text.isNotBlank() && !s.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            ResultActions(s)
        }

        else -> BoxWithConstraints(Modifier.fillMaxSize()) {
            val compactHeight = maxHeight < 460.dp
            Column(
                Modifier.fillMaxSize().padding(top = 10.dp, bottom = 10.dp),
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
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (!compactHeight) KashaRecordingOrb(KashaOrbState.IDLE)
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
        val showOrb = height >= 460.dp
        val compactWave = height < 620.dp
        Column(
            Modifier.fillMaxSize().padding(vertical = if (height >= 620.dp) 24.dp else 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(clock(s.elapsed), style = MaterialTheme.typography.displayLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                s.tr(if (s.recordPhase == "paused") "paused" else "recording"),
                style = MaterialTheme.typography.bodySmall,
                color = c.onSurfaceVariant,
            )
            Spacer(Modifier.height(if (height >= 620.dp) 28.dp else 16.dp))
            if (showOrb) {
                KashaRecordingOrb(
                    state = if (s.recordPhase == "paused") KashaOrbState.PAUSED else KashaOrbState.RECORDING,
                    peaks = s.liveWave,
                    level = s.liveWave.lastOrNull() ?: 0f,
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
    val canSave = s.text.isNotBlank() && !s.busy && s.current?.audioFinalized == true
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val horizontal = maxWidth >= 520.dp
        if (horizontal) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconAction(s.tr("cancelNote"), Glyph.DELETE, { s.confirmDelete = true })
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconAction(s.tr("cancelNote"), Glyph.DELETE, { s.confirmDelete = true })
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

@Composable
internal fun GlobalPlayer(s: StudioState) {
    val scope = rememberCoroutineScope(); val c = MaterialTheme.colorScheme; val loaded = s.loadedAudio
    KashaPanel(Modifier.fillMaxWidth(), padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                s.controlBusy -> KashaProcessingRing(Modifier.size(46.dp))
                s.recordPhase == "recording" -> IconAction(s.tr("pause"), Glyph.PAUSE, { scope.launch { s.pauseRecording() } })
                s.recordPhase == "paused" -> IconAction(s.tr("resume"), Glyph.RECORD, { scope.launch { s.resumeRecording() } })
                s.playback.phase == "playing" -> IconAction(s.tr("pause"), Glyph.PAUSE, { scope.launch { s.pausePlayback() } }, true)
                s.playback.phase == "paused" -> IconAction(s.tr("resume"), Glyph.PLAY, { scope.launch { s.resumePlayback() } }, true)
                loaded?.audioFinalized == true -> IconAction(s.tr("play"), Glyph.PLAY, { scope.launch { s.play() } }, true)
                s.current == null && !s.pending -> IconAction(s.tr("record"), Glyph.RECORD, { scope.launch { s.startRecording() } }, true)
                s.working -> KashaProcessingRing(Modifier.size(46.dp))
                else -> Spacer(Modifier.size(12.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (s.recording) {
                    KashaWaveform(s.liveWave, Modifier.fillMaxWidth().height(23.dp))
                    Text(clock(s.elapsed), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                } else if (loaded?.audioFinalized == true) {
                    Text(loaded.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    KashaWaveform(
                        loaded.waveform,
                        Modifier.fillMaxWidth().height(22.dp),
                        if (s.playback.duration > 0) (s.playback.position / s.playback.duration).toFloat() else 0f,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(clock(s.playback.position.toLong()), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                        Text(clock(loaded.durationSeconds.toLong()), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                    }
                } else {
                    KashaWaveform(emptyList(), Modifier.fillMaxWidth().height(24.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            when {
                s.recording && !s.controlBusy -> Action(s.tr("submitRecording"), { scope.launch { s.stopRecording() } }, primary = true, glyph = Glyph.SEND)
                s.playback.phase != "idle" -> IconAction(s.tr("stop"), Glyph.STOP, s::stopPlayback)
                s.current == null && loaded != null && !s.pending -> IconAction(s.tr("record"), Glyph.RECORD, { scope.launch { s.startRecording() } })
            }
        }
    }
}
