package brain.application

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Ограниченное по времени перечитывание фактов, в том числе после отмены команды. */
internal suspend fun reconcileTransportFinalizer(failure: Throwable?, reconcile: suspend () -> Unit) {
    val cleanup = try {
        withContext(NonCancellable) {
            withTimeout(5_000) {
                // Возвращаем ошибку как результат чтения: перенос через coroutine boundary
                // иначе может создать recovery-копию и замкнуть cause/suppressed на исходнике.
                try { reconcile(); null } catch (error: Exception) { error }
            }
        }
    } catch (error: Exception) {
        error // В том числе собственный таймаут ограниченного перечитывания.
    }
    if (cleanup != null) {
        if (failure == null) throw cleanup
        if (cleanup !== failure && cleanup.cause !== failure) failure.addSuppressed(cleanup)
    }
    // Отмена во время перечитывания не должна превращаться в успешный ответ команды.
    if (failure == null) currentCoroutineContext().ensureActive()
}
