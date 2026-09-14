package brain.desktop

import brain.model.*
import brain.runtime.*
import kotlinx.coroutines.runBlocking
import java.io.RandomAccessFile
import java.nio.file.*
import java.util.UUID
import kotlin.test.*

class DesktopTest {
    @Test fun platformDetectionCoversDesktopFamilies() {
        assertEquals(DesktopOs.MACOS, DesktopPlatform.detectOs("Mac OS X"))
        assertEquals(DesktopOs.MACOS, DesktopPlatform.detectOs("Darwin"))
        assertEquals(DesktopOs.WINDOWS, DesktopPlatform.detectOs("Windows 11"))
        assertEquals(DesktopOs.LINUX, DesktopPlatform.detectOs("Linux"))
        assertEquals(DesktopOs.OTHER, DesktopPlatform.detectOs("FreeBSD"))
    }

    @Test fun platformDataRootsFollowOsConventions() {
        val home = Path.of("home-root").toAbsolutePath()
        val roaming = Path.of("roaming-root").toAbsolutePath()
        val local = Path.of("local-root").toAbsolutePath()
        val xdg = Path.of("xdg-root").toAbsolutePath()
        val override = Path.of("override-root").toAbsolutePath()

        assertEquals(
            home.resolve("Library").resolve("Application Support").resolve("Kasha"),
            DesktopPlatform.dataRoot("Kasha", DesktopOs.MACOS, emptyMap(), home),
        )
        assertEquals(
            roaming.resolve("Kasha"),
            DesktopPlatform.dataRoot("Kasha", DesktopOs.WINDOWS, mapOf("APPDATA" to roaming.toString()), home),
        )
        assertEquals(
            xdg.resolve("kasha"),
            DesktopPlatform.dataRoot("Kasha", DesktopOs.LINUX, mapOf("XDG_DATA_HOME" to xdg.toString()), home),
        )
        assertEquals(
            local.resolve("Kasha"),
            DesktopPlatform.localDataRoot("Kasha", DesktopOs.WINDOWS, mapOf("LOCALAPPDATA" to local.toString()), home),
        )
        assertEquals(
            override,
            DesktopPlatform.dataRoot("Kasha", DesktopOs.LINUX, mapOf("KASHA_HOME" to override.toString()), home),
        )
    }

    @Test fun windowsExecutablesPreferExeWithoutChangingOtherOs() {
        assertEquals(listOf("ffmpeg.exe", "ffmpeg"), DesktopPlatform.executableCandidates("ffmpeg", DesktopOs.WINDOWS))
        assertEquals(listOf("ffmpeg"), DesktopPlatform.executableCandidates("ffmpeg", DesktopOs.MACOS))
        assertEquals(listOf("ffmpeg"), DesktopPlatform.executableCandidates("ffmpeg", DesktopOs.LINUX))
    }

    @Test fun windowsBundleResolverUsesExeCandidate() {
        val dir = Files.createTempDirectory("kasha-win-bundle")
        try {
            val bin = Files.createDirectories(dir.resolve("bin"))
            val exe = bin.resolve("ffmpeg.exe")
            Files.write(exe, byteArrayOf(1))
            assertEquals(exe.toAbsolutePath().toString(), bundledExecutable(dir, "ffmpeg", DesktopOs.WINDOWS))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun bundledModelUsesFirstGgufShardWhenMonolithIsAbsent() {
        val dir = Files.createTempDirectory("kasha-sharded-model")
        try {
            val models = Files.createDirectories(dir.resolve("models"))
            val first = models.resolve("Qwen3-4B-Q4_K_M-00001-of-00002.gguf")
            val second = models.resolve("Qwen3-4B-Q4_K_M-00002-of-00002.gguf")
            Files.write(first, ByteArray(1_000_001))
            Files.write(second, ByteArray(1_000_001))
            assertEquals(first.toAbsolutePath().toString(), bundledModel(dir, "Qwen3-4B-Q4_K_M.gguf"))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun singleInstanceLockRejectsSecondProcessAndReleasesOnClose() {
        val dir = Files.createTempDirectory("kasha-instance-lock")
        try {
            val first = DesktopInstanceLock.acquire(dir)
            try {
                assertFails { DesktopInstanceLock.acquire(dir) }
            } finally {
                first.close()
            }
            DesktopInstanceLock.acquire(dir).use { }
        } finally { dir.toFile().deleteRecursively() }
    }

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
