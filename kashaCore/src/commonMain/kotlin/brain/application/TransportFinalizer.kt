package brain.application

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Ограниченное по времени перечитывание фактов, в том числе после отмены команды. */
internal suspend fun reconcileTransportFinalizer(failure: Throwable?, reconcile: suspend () -> Unit) {
    try {
        withContext(NonCancellable) {
            withTimeout(5_000) { reconcile() }
        }
        // Отмена во время перечитывания не должна превращаться в успешный ответ команды.
        if (failure == null) currentCoroutineContext().ensureActive()
    } catch (cleanup: Exception) {
        if (failure == null) throw cleanup
        if (cleanup !== failure) failure.addSuppressed(cleanup)
    }
}
