package brain.web

import brain.model.Task
import brain.studio.ReminderDeliveryStatus
import brain.studio.ReminderGateway
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Доставкой владеет один локальный runtime; браузер только показывает фактический статус. */
class WebRuntimeReminders(private val baseUrl: String) : ReminderGateway {
    private val client = HttpClient(Js) {
        expectSuccess = true
        install(HttpTimeout) { requestTimeoutMillis = 5_000 }
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
    override val available = false // Нельзя включать второй цикл claim/notify в каждой вкладке.
    override suspend fun deliveryStatus(): ReminderDeliveryStatus =
        client.get("$baseUrl/api/reminders/status").body()
    override suspend fun notify(task: Task): Unit =
        throw UnsupportedOperationException("Reminder delivery belongs to the local runtime")
}
