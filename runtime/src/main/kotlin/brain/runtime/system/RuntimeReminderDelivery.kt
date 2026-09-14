package brain.runtime.system

import brain.model.Task
import brain.studio.ReminderDeliveryStatus
import brain.studio.ReminderGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import kotlin.time.Clock

/** Единственный владелец доставки в локальном процессе. Вкладки не забирают напоминания. */
class RuntimeReminderDelivery(
    private val claim: suspend (Long, String) -> List<Task>,
    private val gateway: ReminderGateway,
) {
    private val mutex = Mutex()
    @Volatile private var failed = false

    fun status() = ReminderDeliveryStatus(gateway.available, failed)

    suspend fun tick(now: Long, zoneId: String) = mutex.withLock {
        if (!gateway.available) return@withLock
        try {
            val tasks = claim(now, zoneId)
            tasks.forEach { gateway.notify(it) }
            if (tasks.isNotEmpty()) failed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }

    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            tick(Clock.System.now().toEpochMilliseconds(), ZoneId.systemDefault().id)
            delay(9_750)
        }
    }
}
