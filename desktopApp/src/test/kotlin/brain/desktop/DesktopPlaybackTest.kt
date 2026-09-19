package brain.desktop

import brain.domain.PlaybackPhase
import brain.model.RuntimeStatus
import brain.runtime.CommandRunner
import brain.runtime.FileBrainStore
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.IOException
import java.lang.reflect.Proxy
import java.nio.file.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.*
import kotlin.test.*

/** Настоящий DesktopAudio и файловое хранилище; только системный аудиовыход управляемый. */
class DesktopPlaybackTest {
    private class Sink {
        @Volatile var closed = false
        @Volatile var started = false
        var onOpen: () -> Unit = {}
        var failStart = false
        val starts = AtomicInteger()
        val line = Proxy.newProxyInstance(SourceDataLine::class.java.classLoader, arrayOf(SourceDataLine::class.java)) { _, method, args ->
            when (method.name) {
                "open" -> { onOpen(); null }
                "start" -> { check(!failStart) { "deviceFailed" }; starts.incrementAndGet(); started = true; null }
                "stop" -> { started = false; null }
                "close" -> { closed = true; started = false; null }
                "getLongFramePosition", "getMicrosecondPosition" -> 0L
                "write" -> args!![2] as Int
                "isOpen" -> !closed
                "isRunning", "isActive" -> started
                "drain", "flush" -> null
                else -> error("Unexpected audio call: ${method.name}")
            }
        } as SourceDataLine
    }
    private class Input : AudioInputStream(ByteArrayInputStream(ByteArray(2)), AudioFormat(16000f, 16, 1, true, false), 960000) {
        @Volatile var released = false
        override fun read(bytes: ByteArray): Int {
            Thread.sleep(2)
            if (released) throw IOException("closed")
            return 160
        }
        override fun close() { released = true; super.close() }
    }
    private class Fixture {
        val root = Files.createTempDirectory("kasha-playback-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = FileBrainStore(root) { RuntimeStatus() }
        val sinks = mutableListOf<Sink>()
        val inputs = mutableListOf<Input>()
        val commands = mutableListOf<List<String>>()
        var decode: () -> Unit = {}
        var load: () -> Unit = {}
        var newSink: () -> Sink = { Sink() }
        val audio = DesktopAudio(store, "test-decoder", root, scope, CommandRunner { command, _ ->
            commands += command; decode(); ""
        }, {
            load(); Input().also { inputs += it }
        }, { newSink().also { sinks += it }.line })
        suspend fun capture(): String {
            val capture = store.createCapture("source.wav", byteArrayOf(1, 2))
            store.updateCapture(capture.id) { it.copy(durationSeconds = 60.0) }
            return capture.id
        }
        suspend fun noTemporaryAudio() = withTimeout(5000) {
            while (Files.list(root).use { files -> files.anyMatch { it.fileName.toString().startsWith(".playback-") } }) delay(10)
        }
        suspend fun finish() {
            audio.stop(); scope.cancel(); noTemporaryAudio(); root.toFile().deleteRecursively()
        }
    }
    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture()
        try { withTimeout(10000) { fixture.block() } } finally { fixture.finish() }
    }

    @Test fun stopDuringDecodePreventsOpeningSoundDevice() = fixture {
        val id = capture(); decode = audio::stop
        audio.playCapture(id, false, 0.0, 1.0)
        assertEquals(PlaybackPhase.IDLE, audio.playbackState().phase)
        assertTrue(sinks.isEmpty()); noTemporaryAudio()
    }
    @Test fun stopDuringDeviceOpenNeverStartsLateSound() = fixture {
        val id = capture(); newSink = { Sink().apply { onOpen = audio::stop } }
        audio.playCapture(id, false, 0.0, 1.0)
        assertEquals(0, sinks.single().starts.get()); assertTrue(sinks.single().closed)
        assertTrue(inputs.single().released); assertEquals(PlaybackPhase.IDLE, audio.playbackState().phase)
        noTemporaryAudio()
    }
    @Test fun streamDecodeFailureCleansTemporaryFileAndState() = fixture {
        val id = capture(); load = { throw IOException("invalid decoded audio") }
        assertFails { audio.playCapture(id, false, 0.0, 1.0) }
        assertEquals(PlaybackPhase.IDLE, audio.playbackState().phase); noTemporaryAudio()
    }
    @Test fun partiallyOpenedDeviceIsClosedWhenOpenFails() = fixture {
        val id = capture(); newSink = { Sink().apply { onOpen = { throw IOException("device unavailable") } } }
        assertFails { audio.playCapture(id, false, 0.0, 1.0) }
        assertTrue(sinks.single().closed); assertTrue(inputs.single().released)
        assertEquals(PlaybackPhase.IDLE, audio.playbackState().phase); noTemporaryAudio()
    }
    @Test fun failedResumeReleasesDevice() = fixture {
        val id = capture(); audio.playCapture(id, false, 0.0, 1.0); audio.pause()
        sinks.single().failStart = true
        assertFails { audio.resume() }
        assertTrue(sinks.single().closed); assertEquals(PlaybackPhase.IDLE, audio.playbackState().phase)
        noTemporaryAudio()
    }
    @Test fun seekPreservesSourceRateAndPlayingOrPaused() = fixture {
        val id = capture(); audio.playCapture(id, false, 0.0, 1.5)
        val playing = audio.seekTo(32.0)
        assertEquals(id, playing.sourceId); assertEquals(PlaybackPhase.PLAYING, playing.phase)
        assertEquals(32.0, playing.positionSeconds); assertTrue(sinks.last().started)
        audio.pause()
        val paused = audio.seekTo(22.0)
        assertEquals(id, paused.sourceId); assertEquals(PlaybackPhase.PAUSED, paused.phase)
        assertEquals(22.0, paused.positionSeconds); assertEquals(0, sinks.last().starts.get())
        assertTrue(commands.all { "atempo=1.5" in it })
        assertTrue(sinks.dropLast(1).all { it.closed })
    }
    @Test fun cancelledOwnerDoesNotLeakPreparedResources() = fixture {
        val id = capture(); scope.cancel()
        assertFails { audio.playCapture(id, false, 0.0, 1.0) }
        assertTrue(sinks.single().closed); assertTrue(inputs.single().released)
        assertEquals(PlaybackPhase.IDLE, audio.playbackState().phase); noTemporaryAudio()
    }
    @Test fun lateFailureOfOldSourceDoesNotResetNewPlayer() = fixture {
        val id = capture(); val entered = CompletableDeferred<Unit>(); val released = CountDownLatch(1)
        val first = Sink().apply { onOpen = {
            entered.complete(Unit)
            check(released.await(5, TimeUnit.SECONDS))
            throw IOException("old device failed")
        } }
        newSink = { first }
        coroutineScope {
            val old = async { runCatching { audio.playCapture(id, false, 0.0, 1.0) } }
            entered.await(); newSink = { Sink() }
            try { audio.playCapture(id, false, 4.0, 1.0) } finally { released.countDown() }
            assertTrue(old.await().isFailure)
            assertTrue(first.closed); assertTrue(sinks.last().started)
            assertEquals(PlaybackPhase.PLAYING, audio.playbackState().phase)
            assertEquals(4.0, audio.playbackState().positionSeconds)
        }
    }
}
