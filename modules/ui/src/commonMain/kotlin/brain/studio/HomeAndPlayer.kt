package brain.studio

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import brain.model.CaptureStatus
import kotlinx.coroutines.launch

@Composable
internal fun HomeScreen(s: StudioState) {
    val scope = rememberCoroutineScope(); val c = MaterialTheme.colorScheme
    when {
        s.recording -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(32.dp)); Text(clock(s.elapsed), style = MaterialTheme.typography.displayLarge)
            Text(s.tr(if (s.recordPhase == "paused") "paused" else "recording"), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            Wave(s.liveWave, Modifier.fillMaxWidth().weight(1f).padding(vertical = 36.dp))
        }
        s.working || (s.busy && s.current != null) -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            ProcessingRing(Modifier.size(84.dp)); Spacer(Modifier.height(26.dp))
            Text(s.tr(when (s.current?.status) { CaptureStatus.TRANSCRIBING -> "transcribing"; CaptureStatus.COMPACTING -> "compacting"; else -> "preparing" }),
                style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            if (s.repository.simulated) Text(s.tr("demoNotice"), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, textAlign = TextAlign.Center)
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
                if (s.current?.simulated == true) Text(s.tr("demoNotice"), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                if (s.current?.status == CaptureStatus.FAILED) {
                    Text(s.tr("processingFailed"), style = MaterialTheme.typography.bodySmall, color = c.error)
                    Spacer(Modifier.height(10.dp))
                    Action(s.tr("retry"), { scope.launch { s.retry() } }, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(14.dp))
            }
            Spacer(Modifier.height(10.dp))
            Action(s.tr("tidy"), { scope.launch { s.tidy() } }, glyph = Glyph.MAGIC, enabled = s.text.isNotBlank() && !s.busy, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconAction(s.tr("cancelNote"), Glyph.DELETE, { s.confirmDelete = true }); Spacer(Modifier.width(10.dp))
                Action(
                    KashaCopy.text(s.language, "sendToNotes") ?: s.tr("send"),
                    { scope.launch { s.sendToNotes() } },
                    primary = true,
                    glyph = Glyph.SEND,
                    modifier = Modifier.weight(1f),
                    enabled = s.text.isNotBlank() && !s.busy && s.current?.audioFinalized == true,
                )
            }
            Spacer(Modifier.height(10.dp))
            Action(
                KashaCopy.text(s.language, "sendToTasks") ?: "В задачи",
                { scope.launch { s.sendToTasks() } },
                glyph = Glyph.TASKS,
                enabled = s.text.isNotBlank() && !s.busy && s.current?.audioFinalized == true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        else -> Column(Modifier.fillMaxSize().padding(top = 10.dp, bottom = 10.dp)) {
            KashaBrandSlot(Modifier.fillMaxWidth().height(164.dp))
            Spacer(Modifier.height(8.dp))
            Text(s.tr("emptyTitle"), style = MaterialTheme.typography.displayMedium, letterSpacing = (-1.2).sp)
            Spacer(Modifier.height(12.dp))
            Text(s.tr("emptyBody"), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, modifier = Modifier.widthIn(max = 270.dp))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                KashaCaptureMark(Modifier.width(210.dp).height(232.dp))
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

@Composable
internal fun GlobalPlayer(s: StudioState) {
    val scope = rememberCoroutineScope(); val c = MaterialTheme.colorScheme; val loaded = s.loadedAudio
    KashaPanel(Modifier.fillMaxWidth().semantics { contentDescription = "global-player" }, padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                s.controlBusy -> ProcessingRing(Modifier.size(46.dp))
                s.recordPhase == "recording" -> IconAction(s.tr("pause"), Glyph.PAUSE, { scope.launch { s.pauseRecording() } })
                s.recordPhase == "paused" -> IconAction(s.tr("resume"), Glyph.RECORD, { scope.launch { s.resumeRecording() } })
                s.playback.phase == "playing" -> IconAction(s.tr("pause"), Glyph.PAUSE, { scope.launch { s.pausePlayback() } }, true)
                s.playback.phase == "paused" -> IconAction(s.tr("resume"), Glyph.PLAY, { scope.launch { s.resumePlayback() } }, true)
                loaded?.audioFinalized == true -> IconAction(s.tr("play"), Glyph.PLAY, { scope.launch { s.play() } }, true)
                s.current == null && !s.pending -> IconAction(s.tr("record"), Glyph.RECORD, { scope.launch { s.startRecording() } }, true)
                s.working -> ProcessingRing(Modifier.size(46.dp))
                else -> Spacer(Modifier.size(12.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (s.recording) {
                    Wave(s.liveWave, Modifier.fillMaxWidth().height(23.dp)); Text(clock(s.elapsed), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                } else if (loaded?.audioFinalized == true) {
                    Text(loaded.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Wave(loaded.waveform, Modifier.fillMaxWidth().height(22.dp), if (s.playback.duration > 0) (s.playback.position / s.playback.duration).toFloat() else 0f)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(clock(s.playback.position.toLong()), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                        Text(clock(loaded.durationSeconds.toLong()), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                    }
                } else Wave(emptyList(), Modifier.fillMaxWidth().height(24.dp))
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
