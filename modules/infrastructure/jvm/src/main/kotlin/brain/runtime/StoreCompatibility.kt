package brain.runtime

import brain.model.RuntimeStatus
import java.nio.file.Path

/** Сохраняет исходный вызов FileBrainStore(path) { status } для старых адаптеров и регрессионных тестов. */
fun FileBrainStore(root: Path, runtimeStatus: () -> RuntimeStatus): FileBrainStore = FileBrainStore(root, runtimeStatus, false)
