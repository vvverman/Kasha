package brain.application

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class TransportFinalizerTest {
    @Test fun suspendedReconciliationRunsAfterCallerCancellation() = runTest {
        var read = false
        val entered = CompletableDeferred<Unit>()
        val cause = CancellationException("caller cancelled")
        var completion: Throwable? = null
        val job = launch {
            var failure: Throwable? = null
            try { entered.complete(Unit); awaitCancellation() }
            catch (e: Throwable) { failure = e; throw e }
            finally {
                reconcileTransportFinalizer(failure) { delay(10); read = true }
            }
        }
        job.invokeOnCompletion { completion = it }
        entered.await(); job.cancel(cause); job.join()
        assertTrue(read)
        assertSame(cause, completion)
    }

    @Test fun cleanupFailureDoesNotReplaceOriginalCancellation() = runTest {
        val cause = CancellationException("original")
        val cleanup = IllegalStateException("read failed")
        reconcileTransportFinalizer(cause) { delay(1); throw cleanup }
        assertTrue(cause.suppressedExceptions.any { it === cleanup })
    }

    @Test fun cleanupFailureIsVisibleWithoutPreviousFailure() = runTest {
        val cleanup = IllegalStateException("read failed")
        val result = assertFailsWith<IllegalStateException> {
            reconcileTransportFinalizer(null) { throw cleanup }
        }
        assertSame(cleanup, result)
    }

    @Test fun reconciliationTimeoutDoesNotMaskOriginalFailure() = runTest {
        val cause = CancellationException("original")
        reconcileTransportFinalizer(cause) { awaitCancellation() }
        assertTrue(cause.suppressedExceptions.any { it is TimeoutCancellationException })
    }

    @Test fun identicalFailureIsNotAddedToItself() = runTest {
        val cause = CancellationException("same exception")
        reconcileTransportFinalizer(cause) { throw cause }
        assertTrue(cause.suppressedExceptions.isEmpty())
    }

    @Test fun reconciliationRunsOnceWithoutReplayingTheCommand() = runTest {
        var sideEffects = 0
        var reads = 0
        var failure: Throwable? = null
        try {
            sideEffects++
            throw IllegalStateException("response lost")
        } catch (e: IllegalStateException) {
            failure = e
        } finally {
            reconcileTransportFinalizer(failure) { reads++ }
        }
        assertEquals(1, sideEffects)
        assertEquals(1, reads)
    }

    @Test fun cancellationDuringReconciliationCannotReturnSuccess() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var reportedSuccess = false
        val cause = CancellationException("cancelled while reading")
        var completion: Throwable? = null
        val job = launch {
            reconcileTransportFinalizer(null) { entered.complete(Unit); release.await() }
            reportedSuccess = true
        }
        job.invokeOnCompletion { completion = it }
        entered.await(); job.cancel(cause); release.complete(Unit); job.join()
        assertFalse(reportedSuccess)
        assertSame(cause, completion)
    }

    @Test fun recoveredCopyOfOriginalFailureDoesNotCreateCauseCycle() = runTest {
        val cause = CancellationException("original")
        val recovered = CancellationException("original", cause)
        reconcileTransportFinalizer(cause) { throw recovered }
        assertTrue(cause.suppressedExceptions.isEmpty())
        assertSame(cause, recovered.cause)
    }

    @Test fun separateCleanupFailureRetainsItsDiagnosticCause() = runTest {
        val cause = CancellationException("original")
        val diskFailure = IllegalStateException("disk unavailable")
        val cleanup = IllegalStateException("read failed", diskFailure)
        reconcileTransportFinalizer(cause) { delay(1); throw cleanup }
        assertSame(cleanup, cause.suppressedExceptions.single())
        assertSame(diskFailure, cause.suppressedExceptions.single().cause)
    }

}
