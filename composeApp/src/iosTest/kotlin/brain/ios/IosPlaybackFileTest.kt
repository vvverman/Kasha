@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package brain.ios

import brain.domain.PlaybackPhase
import brain.domain.PlaybackSessionState
import brain.model.Capture
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import platform.Foundation.*
import platform.AVFAudio.*
import kotlin.math.sin
import kotlin.test.*
import kotlin.time.TimeSource

/** ТЗ 5.1: настоящий WAV и AVAudioPlayer в симуляторе, без AI и имитации плеера. */
class IosPlaybackFileTest {
    private fun fixture(test: suspend (IosAudio, IosRepository, String, Capture) -> Unit) = runBlocking {
        val root = IosPaths.directory(IosPaths.child(NSTemporaryDirectory(), "kasha-playback-${NSUUID().UUIDString}"))
        val path = IosPaths.child(root, "source.wav")
        val bytes = wav()
        bytes.usePinned { pinned ->
            check(NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong()).writeToFile(path, true))
        }
        val repository = IosRepository(IosOnDeviceIntelligence(), systemLanguage = "ru-RU", storageRoot = root)
        val capture = repository.createAudioCapture(path, 3.0, emptyList())
        val audio = IosAudio(repository)
        val original = iosModelSha256(path)
        // Native unit tests do not launch the Swift app/coordinator. Supply only its
        // AVAudioSession side of the existing notification bridge, not a fake player.
        val center = NSNotificationCenter.defaultCenter
        val session = AVAudioSession.sharedInstance()
        val activation = center.addObserverForName(IosAudioSessionBridge.ACTIVATE_PLAYBACK, null, null) {
            check(session.setCategory(AVAudioSessionCategoryPlayback, AVAudioSessionModeSpokenAudio, 0u, null)) {
                "Test host could not configure the playback audio session"
            }
            check(session.setActive(true, null)) { "Test host could not activate the playback audio session" }
        }
        val deactivation = center.addObserverForName(IosAudioSessionBridge.DEACTIVATE, null, null) {
            session.setActive(false, null)
        }
        try {
            test(audio, repository, root, capture)
            assertEquals(original, iosModelSha256(path), "Воспроизведение не меняет исходный файл")
        } catch (error: Throwable) {
            // Сохраняем причину отказа реального симулятора, не заменяя плеер заглушкой.
            runCatching {
                IosAudioSessionBridge.activatePlayback()
                val probe = AVAudioPlayer(NSURL.fileURLWithPath(path), error = null)
                try {
                    println("AUDIO_DIAGNOSTIC duration=${probe.duration}; channels=${probe.numberOfChannels}; " +
                        "sampleRate=${probe.format.sampleRate}; sessionRate=${session.sampleRate}; " +
                        "outputs=${session.currentRoute.outputs}")
                    val prepared = probe.prepareToPlay()
                    val started = probe.play()
                    println("AUDIO_DIAGNOSTIC prepared=$prepared; started=$started; playing=${probe.playing}")
                } finally { probe.stop() }
            }.onFailure { println("AUDIO_DIAGNOSTIC ${it.message}") }
            throw error
        } finally {
            audio.stop()
            center.removeObserver(activation)
            center.removeObserver(deactivation)
            IosPaths.remove(root)
        }
    }

    private fun pump(seconds: Double) {
        NSRunLoop.currentRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(seconds))
    }

    private fun awaitState(audio: IosAudio, label: String,
                           check: (PlaybackSessionState) -> Boolean): PlaybackSessionState {
        val started = TimeSource.Monotonic.markNow()
        while (started.elapsedNow().inWholeMilliseconds < 8000) {
            val state = audio.playbackState()
            if (check(state)) return state
            pump(0.02)
        }
        error("Не дождались $label: ${audio.playbackState()}")
    }

    @Test fun realFileSupportsPauseResumeSeekAndStop() = fixture { audio, _, _, capture ->
        audio.playCapture(capture.id, false, 0.0, 1.0)
        awaitState(audio, "движение позиции") { it.phase == PlaybackPhase.PLAYING && it.positionSeconds > 0.1 }
        audio.pause()
        val paused = audio.playbackState()
        assertEquals(PlaybackPhase.PAUSED, paused.phase)
        pump(0.12)
        assertEquals(paused.positionSeconds, audio.playbackState().positionSeconds, 0.04)
        // Перемотка внутри последних 50 мс — всё ещё пауза, не окончание файла.
        val nearEnd = audio.seekTo(2.98)
        assertEquals(PlaybackPhase.PAUSED, nearEnd.phase)
        assertEquals(2.98, nearEnd.positionSeconds, 0.04)
        audio.seekTo(0.25)
        audio.resume()
        awaitState(audio, "продолжение") { it.phase == PlaybackPhase.PLAYING && it.positionSeconds > 0.3 }
        val sought = audio.seekTo(1.0)
        assertEquals(PlaybackPhase.PLAYING, sought.phase)
        assertEquals(capture.id, sought.sourceId)
        audio.stop()
        assertEquals(PlaybackPhase.IDLE, audio.playbackState().phase)
    }

    @Test fun naturalEndAndReplayWorkAtEverySupportedSpeed() = fixture { audio, _, _, capture ->
        for (rate in listOf(1.0, 1.5, 2.0)) {
            audio.playCapture(capture.id, false, 0.0, rate)
            awaitState(audio, "воспроизведение $rate") { it.phase == PlaybackPhase.PLAYING && it.positionSeconds > 0.05 }
            val ended = awaitState(audio, "конец файла $rate") { it.phase == PlaybackPhase.IDLE }
            assertEquals(capture.id, ended.sourceId)
        }
        audio.playCapture(capture.id, false, 0.0, 1.0)
        assertEquals(PlaybackPhase.PLAYING, audio.playbackState().phase)
        assertTrue(audio.playbackState().positionSeconds < 0.5)
    }

    @Test fun changingSourceAndDecodeFailureReleaseThePreviousPlayer() = fixture { audio, repository, root, capture ->
        val otherPath = IosPaths.child(root, "other.wav")
        check(NSFileManager.defaultManager.copyItemAtPath(capture.audioFileName!!, otherPath, null))
        val other = repository.createAudioCapture(otherPath, 3.0, emptyList())
        val brokenPath = IosPaths.child(root, "broken.wav")
        IosPaths.write(brokenPath, "not audio")
        val broken = repository.createAudioCapture(brokenPath, 3.0, emptyList())
        audio.playCapture(capture.id, false, 0.0, 1.0)
        audio.playCapture(other.id, false, 0.0, 2.0)
        assertEquals(other.id, audio.playbackState().sourceId)
        assertEquals(PlaybackPhase.PLAYING, audio.playbackState().phase)
        assertFails { audio.playCapture(broken.id, false, 0.0, 1.0) }
        assertEquals(PlaybackPhase.IDLE, audio.playbackState().phase)
        audio.playCapture(capture.id, false, 0.0, 1.0)
        assertEquals(capture.id, audio.playbackState().sourceId)
        assertEquals(PlaybackPhase.PLAYING, audio.playbackState().phase)
    }

    private fun wav(): ByteArray {
        val rate = 48000
        val samples = rate * 3
        val result = ByteArray(44 + samples * 2)
        fun text(at: Int, value: String) = value.encodeToByteArray().copyInto(result, at)
        fun number(at: Int, value: Int, count: Int) {
            repeat(count) { result[at + it] = (value ushr (it * 8)).toByte() }
        }
        text(0, "RIFF"); number(4, result.size - 8, 4); text(8, "WAVEfmt ")
        number(16, 16, 4); number(20, 1, 2); number(22, 1, 2)
        number(24, rate, 4); number(28, rate * 2, 4); number(32, 2, 2); number(34, 16, 2)
        text(36, "data"); number(40, samples * 2, 4)
        repeat(samples) { number(44 + it * 2, (sin(it * 2 * kotlin.math.PI * 440 / rate) * 1000).toInt(), 2) }
        return result
    }
}
