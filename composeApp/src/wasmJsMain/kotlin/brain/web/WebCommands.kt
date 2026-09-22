@file:OptIn(ExperimentalWasmJsInterop::class)
package brain.web

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.js.*

/** Отдельный жизненный цикл длинного HTTP-запроса, без отмены успешно прочитанного ответа. */
internal suspend fun captureCommand(base: String, id: String, stage: String, timeoutMillis: Long): String {
    currentCoroutineContext().ensureActive()
    val request = startCommand(base.toJsString(), id.toJsString(), stage.toJsString(), timeoutMillis.toDouble())
    return try {
        commandResponse(request).await().toString()
    } catch (cancelled: CancellationException) {
        cancelCommand(request)
        throw cancelled
    }
}

private fun startCommand(base: JsString, id: JsString, stage: JsString, timeoutMillis: Double): JsAny =
    js("globalThis.kashaCommands.start(base, id, stage, timeoutMillis)")
private fun commandResponse(request: JsAny): Promise<JsString> = js("request.promise")
private fun cancelCommand(request: JsAny): Unit = js("request.cancel()")
