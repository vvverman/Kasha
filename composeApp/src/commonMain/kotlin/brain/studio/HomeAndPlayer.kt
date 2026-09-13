package brain.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import brain.model.CaptureStatus
import kotlinx.coroutines.launch

@Composable
internal fun HomeScreen(s: StudioState) {
    val scope = rememberCoroutineScope()
    val c = KashaTheme.colors

    when {
        s.recording -> Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(20.dp))
            Text(
                clock(s.elapsed),
                style = MaterialTheme.typography.displayLarge,
                color = c.textPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                s.tr(if (s.recordPhase == "paused") "paused" else "recording"),
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
            )
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(KashaMetrics.captureRegion),
                contentAlignment = Alignment.Center,
            ) {
                KashaCaptureOrb(
                    Modifier.size(KashaMetrics.orbRecording),
                    recording = true,
                )
                Wave(
                    s.liveWave,
                    Modifier
                        .widthIn(max = KashaMetrics.waveformRecordingMaxWidth)
                        .fillMaxWidth()
                        .height(96.dp)
                        .padding(horizontal = 12.dp),
                )
            }
        }

        s.working || (s.busy && s.current != null) -> Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            KashaCaptureOrb(Modifier.size(168.dp))
            Spacer(Modifier.height(20.dp))
            Text(
                s.tr(
                    when (s.current?.status) {
                        CaptureStatus.TRANSCRIBING -> "transcribing"
                        CaptureStatus.COMPACTING -> "compacting"
                        else -> "preparing"
                    },
                ),
                style = MaterialTheme.typography.titleLarge,
                color = c.textPrimary,
                textAlign = TextAlign.Center,
            )
            if (s.repository.simulated) {
                Spacer(Modifier.height(12.dp))
                Text(
                    s.tr("demoNotice"),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }

        s.current != null -> Column(
            Modifier
                .fillMaxSize()
                .padding(top = 12.dp, bottom = 12.dp),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                KashaNoteText(
                    value = s.text,
                    onValueChange = s::editText,
                    placeholder = KashaCopy.text(s.language, "noteText") ?: s.tr("body"),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(20.dp))
                if (s.current?.simulated == true) {
                    Text(
                        s.tr("demoNotice"),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary,
                    )
                }
                if (s.current?.status == CaptureStatus.FAILED) {
                    Text(
                        s.tr("processingFailed"),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.error,
                    )
                    Spacer(Modifier.height(10.dp))
                    Action(
                        s.tr("retry"),
                        { scope.launch { s.retry() } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(14.dp))
            }

            Spacer(Modifier.height(10.dp))
            Action(
                s.tr("tidy"),
                { scope.launch { s.tidy() } },
                enabled = s.text.isNotBlank() && !s.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            ResultDestinationActions(s)
            Spacer(Modifier.height(8.dp))
            QuietAction(
                s.tr("cancelNote"),
                { s.confirmDelete = true },
                Modifier.align(Alignment.CenterHorizontally),
            )
        }

        else -> Column(
            Modifier
                .fillMaxSize()
                .padding(top = 12.dp, bottom = 12.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                s.tr("emptyTitle"),
                style = MaterialTheme.typography.displayMedium,
                color = c.textPrimary,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                s.tr("emptyBody"),
                style = MaterialTheme.typography.bodyMedium,
                color = c.textSecondary,
                modifier = Modifier.widthIn(max = 300.dp),
            )

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(KashaMetrics.orbIdle)
                            .semantics(mergeDescendants = true) {
                                contentDescription = s.tr("record")
                            }
                            .clickable(
                                enabled = !s.pending && !s.controlBusy,
                                role = Role.Button,
                            ) {
                                scope.launch { s.startRecording() }
                            },
                    ) {
                        KashaCaptureOrb(Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        s.tr("record"),
                        style = MaterialTheme.typography.labelLarge,
                        color = c.textPrimary,
                    )
                }
            }

            if (s.pending) {
                Text(
                    s.tr("recoveryNotice"),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
                Spacer(Modifier.height(8.dp))
                Action(
                    s.tr("recover"),
                    { scope.launch { s.recover() } },
                    primary = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (s.repository.simulated) {
                QuietAction(
                    s.tr("demoButton"),
                    { scope.launch { s.demo() } },
                    Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

@Composable
private fun ResultDestinationActions(s: StudioState) {
    val scope = rememberCoroutineScope()
    val fontScale = LocalDensity.current.fontScale
    val enabled = s.text.isNotBlank() && !s.busy && s.current?.audioFinalized == true
    val notesLabel = KashaCopy.text(s.language, "sendToNotes") ?: s.tr("send")
    val tasksLabel = KashaCopy.text(s.language, "sendToTasks") ?: "В задачи"

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val canUseRow = maxWidth >= 300.dp && fontScale <= 1.3f
        if (canUseRow) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Action(
                    notesLabel,
                    { scope.launch { s.sendToNotes() } },
                    primary = true,
                    glyph = Glyph.SEND,
                    modifier = Modifier.weight(1f).widthIn(min = 144.dp),
                    enabled = enabled,
                )
                Action(
                    tasksLabel,
                    { scope.launch { s.sendToTasks() } },
                    glyph = Glyph.TASKS,
                    modifier = Modifier.weight(1f).widthIn(min = 144.dp),
                    enabled = enabled,
                )
            }
        } else {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Action(
                    notesLabel,
                    { scope.launch { s.sendToNotes() } },
                    primary = true,
                    glyph = Glyph.SEND,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                )
                Action(
                    tasksLabel,
                    { scope.launch { s.sendToTasks() } },
                    glyph = Glyph.TASKS,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                )
            }
        }
    }
}

@Composable
internal fun GlobalPlayer(s: StudioState) {
    val scope = rememberCoroutineScope()
    val c = KashaTheme.colors
    val loaded = s.loadedAudio

    // На idle без аудио transport отсутствует: главным действием остаётся orb.
    if (!s.recording && loaded == null && !s.working) return

    KashaPanel(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Управление аудио" },
        padding = 12.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                s.controlBusy -> ProcessingRing(Modifier.size(46.dp))
                s.recordPhase == "recording" -> IconAction(
                    s.tr("pause"),
                    Glyph.PAUSE,
                    { scope.launch { s.pauseRecording() } },
                    true,
                )
                s.recordPhase == "paused" -> IconAction(
                    s.tr("resume"),
                    Glyph.RECORD,
                    { scope.launch { s.resumeRecording() } },
                    true,
                )
                s.playback.phase == "playing" -> IconAction(
                    s.tr("pause"),
                    Glyph.PAUSE,
                    { scope.launch { s.pausePlayback() } },
                    true,
                )
                s.playback.phase == "paused" -> IconAction(
                    s.tr("resume"),
                    Glyph.PLAY,
                    { scope.launch { s.resumePlayback() } },
                    true,
                )
                loaded?.audioFinalized == true -> IconAction(
                    s.tr("play"),
                    Glyph.PLAY,
                    { scope.launch { s.play() } },
                    true,
                )
                s.working -> ProcessingRing(Modifier.size(46.dp))
                else -> Spacer(Modifier.size(12.dp))
            }

            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (s.recording) {
                    Wave(
                        s.liveWave,
                        Modifier
                            .fillMaxWidth()
                            .height(KashaMetrics.waveformMiniMaxHeight),
                    )
                    Text(
                        clock(s.elapsed),
                        style = MaterialTheme.typography.labelSmall,
                        color = c.textSecondary,
                    )
                } else if (loaded?.audioFinalized == true) {
                    Text(
                        loaded.title,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Wave(
                        loaded.waveform,
                        Modifier
                            .fillMaxWidth()
                            .height(KashaMetrics.waveformMiniMaxHeight),
                        if (s.playback.duration > 0) {
                            (s.playback.position / s.playback.duration).toFloat()
                        } else {
                            0f
                        },
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            clock(s.playback.position.toLong()),
                            style = MaterialTheme.typography.labelSmall,
                            color = c.textSecondary,
                        )
                        Text(
                            clock(loaded.durationSeconds.toLong()),
                            style = MaterialTheme.typography.labelSmall,
                            color = c.textSecondary,
                        )
                    }
                }
            }

            Spacer(Modifier.width(10.dp))
            when {
                s.recording && !s.controlBusy -> Action(
                    s.tr("submitRecording"),
                    { scope.launch { s.stopRecording() } },
                    primary = true,
                    glyph = Glyph.SEND,
                )
                s.playback.phase != "idle" -> IconAction(
                    s.tr("stop"),
                    Glyph.STOP,
                    s::stopPlayback,
                )
            }
        }
    }
}
