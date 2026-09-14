package brain.studio

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DeleteConfirmationTest {
    @Test fun recordingConfirmationNeverFallsBackToCaptureAfterRecordingStops() = runTest {
        val target = DeleteConfirmation.Recording("recording-A", resumeOnKeep = false)
        val calls = mutableListOf<String>()
        assertFalse(target.execute(
            cancelRecording = { calls += "recording:$it"; false },
            discardCapture = { calls += "capture:$it"; true },
            deleteTask = { calls += "task:$it"; true },
        ))
        assertEquals(listOf("recording:recording-A"), calls)
    }

    @Test fun taskConfirmationKeepsTheOriginalTaskId() = runTest {
        val target = DeleteConfirmation.Task("task-A")
        var currentlySelected = "task-A"
        currentlySelected = "task-B"
        var removed: String? = null
        assertTrue(target.execute(
            cancelRecording = { error("must not cancel recording") },
            discardCapture = { error("must not discard capture") },
            deleteTask = { removed = it; true },
        ))
        assertEquals("task-A", removed)
        assertEquals("task-B", currentlySelected)
    }

    @Test fun captureConfirmationKeepsTheOriginalCaptureId() = runTest {
        val target = DeleteConfirmation.Capture("capture-A")
        var requested: String? = null
        assertTrue(target.execute(
            cancelRecording = { error("wrong target") },
            discardCapture = { requested = it; true },
            deleteTask = { error("wrong target") },
        ))
        assertEquals("capture-A", requested)
    }

    @Test fun failedRecordingCancellationDoesNotTryAnotherDeletion() = runTest {
        val target = DeleteConfirmation.Recording("recording-A", resumeOnKeep = true)
        val failure = IllegalStateException("session changed")
        val result = assertFailsWith<IllegalStateException> {
            target.execute(
                cancelRecording = { throw failure },
                discardCapture = { error("must not fall back") },
                deleteTask = { error("must not fall back") },
            )
        }
        assertSame(failure, result)
    }

    @Test fun alreadyPausedRecordingDoesNotRequestResumeOnKeep() {
        assertFalse(DeleteConfirmation.Recording("recording-A", resumeOnKeep = false).resumeOnKeep)
        assertTrue(DeleteConfirmation.Recording("recording-B", resumeOnKeep = true).resumeOnKeep)
    }
}
