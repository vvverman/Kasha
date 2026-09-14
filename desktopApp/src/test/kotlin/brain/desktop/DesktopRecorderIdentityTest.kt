package brain.desktop

import brain.domain.*
import brain.runtime.FileBrainStore
import kotlinx.coroutines.runBlocking
import java.nio.file.*
import kotlin.test.*

/** Файлы настоящие; прерванная сессия задаётся без физического микрофона. */
class DesktopRecorderIdentityTest {
    private fun fixture(block: suspend (Path, FileBrainStore, DesktopRecorder, MutableList<String>) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("kasha-recorder-identity-")
        val store = FileBrainStore(root, runtimeStatus = { brain.model.RuntimeStatus() }, singleCurrent = true)
        val enqueued = mutableListOf<String>()
        val recorder = DesktopRecorder(root, store) { enqueued += it }
        try { block(root, store, recorder, enqueued) }
        finally { recorder.close(); root.toFile().deleteRecursively() }
    }
    private fun journal(root: Path, id: String): Path {
        val path = root.resolve("pending/$id.wav")
        WavJournal(path).use { it.append(ByteArray(3200), 3200) }
        return path
    }
    private fun interrupted(recorder: DesktopRecorder, id: String) {
        recorder.javaClass.getDeclaredField("state").apply { isAccessible = true }
            .set(recorder, RecorderSessionState(RecorderPhase.INTERRUPTED, id))
    }

    @Test fun recoveryUsesRequestedIdAndPreservesOtherJournal() = fixture { root, store, recorder, enqueued ->
        val first = journal(root, "10000000-0000-0000-0000-000000000001"); journal(root, "10000000-0000-0000-0000-000000000002")
        assertEquals("10000000-0000-0000-0000-000000000002", recorder.recoverPending("10000000-0000-0000-0000-000000000002").id)
        assertTrue(Files.exists(first))
        assertEquals(listOf("10000000-0000-0000-0000-000000000002"), store.snapshot().captures.map { it.id })
        assertEquals(listOf("10000000-0000-0000-0000-000000000002"), enqueued)
        assertEquals(listOf("10000000-0000-0000-0000-000000000001"), recorder.pendingRecordings().map { it.id })
    }
    @Test fun legacyRecoveryRejectsAmbiguousPending() = fixture { root, store, recorder, _ ->
        journal(root, "10000000-0000-0000-0000-000000000001"); journal(root, "10000000-0000-0000-0000-000000000002")
        assertFailsWith<IllegalArgumentException> { recorder.recoverPending() }
        assertEquals(2, recorder.pendingRecordings().size)
        assertTrue(store.snapshot().captures.isEmpty())
    }
    @Test fun cancelInterruptedSessionDoesNotUploadOrTouchOtherFile() = fixture { root, store, recorder, enqueued ->
        val active = journal(root, "active"); val other = journal(root, "other")
        interrupted(recorder, "active")
        recorder.cancelActive("active"); recorder.cancelActive("active")
        assertFalse(Files.exists(active)); assertTrue(Files.exists(other))
        assertEquals(RecorderPhase.IDLE, recorder.sessionState().phase)
        assertTrue(store.snapshot().captures.isEmpty()); assertTrue(enqueued.isEmpty())
    }
    @Test fun staleCancellationCannotDeleteCurrentSession() = fixture { root, store, recorder, enqueued ->
        val active = journal(root, "new-session"); interrupted(recorder, "new-session")
        assertFailsWith<IllegalStateException> { recorder.cancelActive("old-session") }
        assertTrue(Files.exists(active))
        assertEquals("new-session", recorder.sessionState().activeSessionId)
        assertTrue(store.snapshot().captures.isEmpty()); assertTrue(enqueued.isEmpty())
    }
    @Test fun discardOnlyDeletesAddressedPending() = fixture { root, store, recorder, enqueued ->
        val first = journal(root, "10000000-0000-0000-0000-000000000001"); val second = journal(root, "10000000-0000-0000-0000-000000000002")
        recorder.discardPending("10000000-0000-0000-0000-000000000001")
        assertFalse(Files.exists(first)); assertTrue(Files.exists(second))
        assertTrue(store.snapshot().captures.isEmpty()); assertTrue(enqueued.isEmpty())
    }
    @Test fun emptyJournalDoesNotBlockStartupButActiveJournalIsPreserved() = fixture { root, _, recorder, _ ->
        val empty = root.resolve("pending/empty.wav"); WavJournal(empty).close()
        assertTrue(recorder.pendingRecordings().isEmpty()); assertFalse(Files.exists(empty))
        val active = root.resolve("pending/active.wav"); WavJournal(active).close(); interrupted(recorder, "active")
        assertTrue(recorder.pendingRecordings().isEmpty()); assertTrue(Files.exists(active))
    }
    @Test fun addressIsNotAFilePath() = fixture { root, _, recorder, _ ->
        val path = journal(root, "safe")
        assertFailsWith<IllegalStateException> { recorder.discardPending("../safe") }
        assertTrue(Files.exists(path))
    }
}
