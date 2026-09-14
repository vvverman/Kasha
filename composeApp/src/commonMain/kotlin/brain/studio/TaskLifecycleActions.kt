package brain.studio

/**
 * Завершение из detail-screen всегда сначала сохраняет актуальную рабочую копию.
 * Если save не удался, complete не вызывается.
 */
internal suspend fun StudioState.completeTaskFromDetail(id: String, workingText: String): Boolean {
    val task = snapshot.tasks.firstOrNull { it.id == id } ?: return false
    if (task.completed || workingText.isBlank()) return false
    if (workingText != task.text && !saveTask(id, workingText)) return false
    return completeTask(id)
}
