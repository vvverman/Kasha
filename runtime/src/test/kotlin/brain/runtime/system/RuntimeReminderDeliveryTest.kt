package brain.runtime.system

import brain.model.Task
import brain.studio.ReminderGateway
import kotlinx.coroutines.*
import kotlin.test.*

class RuntimeReminderDeliveryTest {
    private val task = Task(id = "t", text = "Напомнить", createdAt = 1, updatedAt = 1, dueAt = 1)
    private class Gateway : ReminderGateway {
        override var available = true
        var failed = false
        val sent = mutableListOf<String>()
        override suspend fun notify(task: Task) {
            check(!failed)
            sent += task.id
        }
    }

    @Test fun oneOwnerSerializesOverlappingChecks() = runBlocking {
        val gateway = Gateway()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var claimed = false
        val delivery = RuntimeReminderDelivery({ _, _ ->
            entered.complete(Unit); release.await()
            if (claimed) emptyList() else listOf(task).also { claimed = true }
        }, gateway)
        val first = async { delivery.tick(10, "UTC") }; entered.await()
        val second = async { delivery.tick(10, "UTC") }; yield()
        release.complete(Unit); awaitAll(first, second)
        assertEquals(listOf("t"), gateway.sent)
        assertFalse(delivery.status().failed)
    }

    @Test fun unavailableServiceDoesNotConsumeDueTasks() = runBlocking {
        val gateway = Gateway().apply { available = false }
        var calls = 0
        val delivery = RuntimeReminderDelivery({ _, _ -> calls++; listOf(task) }, gateway)
        delivery.tick(10, "UTC")
        assertEquals(0, calls)
        assertFalse(delivery.status().available)
    }

    @Test fun failureRemainsVisibleUntilActualSuccessfulDelivery() = runBlocking {
        val gateway = Gateway().apply { failed = true }
        var due = listOf(task)
        val delivery = RuntimeReminderDelivery({ _, _ -> due }, gateway)
        delivery.tick(10, "UTC")
        assertTrue(delivery.status().failed)
        gateway.failed = false; due = emptyList(); delivery.tick(20, "UTC")
        assertTrue(delivery.status().failed)
        due = listOf(task); delivery.tick(30, "UTC")
        assertFalse(delivery.status().failed)
        assertEquals(listOf("t"), gateway.sent)
    }

    @Test fun processCancellationIsNotReportedAsNotificationFailure() = runBlocking {
        val gateway = Gateway()
        val delivery = RuntimeReminderDelivery({ _, _ -> throw CancellationException("shutdown") }, gateway)
        assertFailsWith<CancellationException> { delivery.tick(10, "UTC") }
        assertFalse(delivery.status().failed)
    }

    @Test fun reminderTextIsPassedAsDataNotShellCode() = runBlocking {
        val commands = mutableListOf<List<String>>()
        val inputs = mutableListOf<String?>()
        val process = object : JvmProcessGateway {
            override fun available(command: String) = true
            override fun run(command: List<String>, stdin: String?): JvmProcessResult {
                commands += command; inputs += stdin
                return JvmProcessResult(0, "")
            }
        }
        val text = "$(not-a-command) ; <строка>"
        JvmReminderGateway(process, "LINUX").notify(task.copy(text = text))
        assertEquals(text, inputs.single())
        assertFalse(commands.single().any { text in it })
    }
}
