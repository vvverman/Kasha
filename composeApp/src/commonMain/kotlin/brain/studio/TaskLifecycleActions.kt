package brain.studio

/** Последовательность «сохранить рабочую копию → выполнить» целиком принадлежит Core. */
internal suspend fun StudioState.completeTaskFromDetail(id: String, workingText: String): Boolean =
    completeTaskDetailInCore(id, workingText)
