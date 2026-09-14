package brain.studio

/** Одно подтверждение хранит точное намерение, а не вычисляет его заново по текущему экрану. */
internal sealed interface DeleteConfirmation {
    val id: String
    data class Recording(override val id: String, val resumeOnKeep: Boolean) : DeleteConfirmation
    data class Capture(override val id: String) : DeleteConfirmation
    data class Task(override val id: String) : DeleteConfirmation
}

/** Только маршрутизация UI-намерения. Проверки и само удаление выполняет API Core. */
internal suspend fun DeleteConfirmation.execute(
    cancelRecording: suspend (String) -> Boolean,
    discardCapture: suspend (String) -> Boolean,
    deleteTask: suspend (String) -> Boolean,
): Boolean = when (this) {
    is DeleteConfirmation.Recording -> cancelRecording(id)
    is DeleteConfirmation.Capture -> discardCapture(id)
    is DeleteConfirmation.Task -> deleteTask(id)
}
