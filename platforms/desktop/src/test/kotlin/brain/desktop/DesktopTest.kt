package brain.desktop

import brain.model.*
import brain.runtime.*
import kotlinx.coroutines.runBlocking
import java.io.RandomAccessFile
import java.nio.file.*
import java.util.UUID
import kotlin.test.*

class DesktopTest {
    @Test fun journalIsPlayableAndRecoversAfterHeaderDamage() {
        val dir = Files.createTempDirectory("brain-journal")
        try {
            val path = dir.resolve("source.wav")
            WavJournal(path).use { it.append(ByteArray(32000), 32000); it.flush() }
            assertEquals(1.0, PcmAudio.info(path).duration)
            RandomAccessFile(path.toFile(), "rw").use { it.seek(40); it.writeInt(0) }
            assertEquals(32000L, WavJournal.repair(path))
            assertEquals(1.0, PcmAudio.info(path).duration)
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun journalNeverOverwritesExistingSource() {
        val dir = Files.createTempDirectory("brain-original")
        try { val file = dir.resolve("original.wav"); Files.writeString(file, "original"); assertFails { WavJournal(file) }; assertEquals("original", Files.readString(file)) }
        finally { dir.toFile().deleteRecursively() }
    }
    @Test fun journalRejectsPartialSample() {
        val dir = Files.createTempDirectory("brain-pcm")
        try { WavJournal(dir.resolve("x.wav")).use { assertFails { it.append(ByteArray(3), 3) } } }
        finally { dir.toFile().deleteRecursively() }
    }
    @Test fun desktopRecoverySavesBeforeRemovingJournal() = runBlocking {
        val dir = Files.createTempDirectory("brain-recovery")
        try {
            val store = FileBrainStore(dir) { RuntimeStatus() }; val recorder = DesktopRecorder(dir, store) {}
            val id = UUID.randomUUID().toString(); val file = dir.resolve("pending/$id.wav")
            WavJournal(file).use { it.append(ByteArray(3200), 3200) }
            assertTrue(recorder.hasPending()); assertFalse(recorder.hasConsent())
            val capture = recorder.recoverPending(); assertEquals(id, capture.id); assertFalse(Files.exists(file))
            assertTrue(Files.exists(store.resolveAudio(capture))); assertFalse(recorder.hasPending())
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun failedSaveKeepsPendingAudio() = runBlocking {
        val dir = Files.createTempDirectory("brain-save-fail")
        try {
            val store = FileBrainStore(dir) { RuntimeStatus() }; val recorder = DesktopRecorder(dir, store) {}
            val file = dir.resolve("pending/${UUID.randomUUID()}.wav")
            WavJournal(file).use { it.append(ByteArray(3200), 3200) }
            Files.createDirectory(dir.resolve("brain.json")); Files.writeString(dir.resolve("brain.json/block"), "x")
            assertFails { recorder.recoverPending() }; assertTrue(Files.exists(file)); assertTrue(store.snapshot().captures.isEmpty())
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun incompleteBundleIsRejectedWithoutDownloader() {
        val dir = Files.createTempDirectory("brain-bundle")
        try { assertFails { bundledEnvironment(dir) }; assertEquals(0L, Files.list(dir).use { it.count() }) }
        finally { dir.toFile().deleteRecursively() }
    }
}
