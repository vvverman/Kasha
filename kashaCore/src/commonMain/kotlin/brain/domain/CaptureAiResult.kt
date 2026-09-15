package brain.domain

import brain.model.Capture

/** Поздний AI-ответ не заменяет изменённый текст или уже сохранённый результат. */
object CaptureAiResult {
    fun apply(expected: Capture, current: Capture, result: Capture): Capture {
        require(result.id == expected.id && result.isInbox) { "aiCaptureMismatch" }
        if (current.id != expected.id || !current.isInbox) return current
        if (current == expected) return result
        // Редакция успела измениться, но завершилась та же стадия обработки.
        // Снимаем только её working-состояние; текст и пользовательские поля не трогаем.
        return if (expected.status.isWorking && current.status == expected.status && !result.status.isWorking) {
            current.copy(status = result.status, message = "")
        } else current
    }
}
