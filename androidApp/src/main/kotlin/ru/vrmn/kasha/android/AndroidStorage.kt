package ru.vrmn.kasha.android

import brain.model.Capture
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/**
 * Android app-private filesystem layout. Всё находится под Context.filesDir/Kasha;
 * наружу не экспортируется и не содержит API-ключи внешних провайдеров.
 */
internal class AndroidStorage(root: File) {
    val root: File = root.absoluteFile.normalize()
    val stateFile = File(this.root, "brain.json")
    val preferencesFile = File(this.root, "preferences.json")
    val audioDir = File(this.root, "audio")
    val pendingDir = File(this.root, "pending")

    init {
        require(this.root.mkdirs() || this.root.isDirectory)
        require(audioDir.mkdirs() || audioDir.isDirectory)
        require(pendingDir.mkdirs() || pendingDir.isDirectory)
        require(!Files.isSymbolicLink(this.root.toPath()))
        require(!Files.isSymbolicLink(audioDir.toPath()))
        require(!Files.isSymbolicLink(pendingDir.toPath()))
    }

    fun read(file: File): String? {
        require(file.parentFile?.canonicalFile == root.canonicalFile)
        check(interruptedWrites(file).isEmpty()) { "Незавершённая запись ${file.name} сохранена для восстановления" }
        val path = file.toPath()
        if (Files.notExists(path, NOFOLLOW_LINKS)) {
            check(!marker(file).exists()) { "Локальный файл ${file.name} отсутствует; данные оставлены для восстановления" }
            return null
        }
        check(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Не удалось прочитать локальные данные Kasha" }
        return file.readText(Charsets.UTF_8)
    }

    fun markKnown(file: File) {
        require(file.parentFile?.canonicalFile == root.canonicalFile)
        val marker = marker(file)
        if (marker.exists()) {
            check(marker.isFile) { "Некорректный маркер локального хранилища" }
            return
        }
        FileOutputStream(marker).use { stream ->
            stream.write(byteArrayOf(1))
            stream.fd.sync()
        }
    }

    /** Атомарная замена небольших JSON-файлов состояния с fsync временного файла. */
    fun write(file: File, value: String) {
        require(file.parentFile?.canonicalFile == root.canonicalFile)
        val temp = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { stream ->
                stream.write(value.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            markKnown(file)
        } finally {
            temp.delete()
        }
    }

    fun newPendingFile(): File = File(pendingDir, "${UUID.randomUUID()}.m4a")

    fun pendingFiles(): List<File> = pendingDir.listFiles()
        .orEmpty()
        .filter { it.isFile && it.extension.equals("m4a", ignoreCase = true) }
        .filter { runCatching { pendingId(it) }.isSuccess }
        .sortedBy { it.name }

    fun pendingId(file: File): String {
        require(file.parentFile?.canonicalFile == pendingDir.canonicalFile)
        require(file.extension.equals("m4a", ignoreCase = true))
        val id = file.nameWithoutExtension
        UUID.fromString(id)
        return id
    }

    /**
     * Pending-файл и capture имеют один UUID. Если процесс погибнет после move, но до
     * записи brain.json, reconcile() вернёт orphan audio обратно в pending.
     */
    fun acceptPending(source: File, captureId: String): File {
        require(source.isFile && source.length() > 0)
        require(pendingId(source) == captureId)
        val targetDir = File(audioDir, captureId)
        require(!targetDir.exists())
        require(targetDir.mkdir())
        val target = File(targetDir, "saved.m4a")
        try {
            move(source, target)
            return target
        } catch (error: Throwable) {
            target.delete()
            targetDir.delete()
            throw error
        }
    }

    /** Восстанавливает незавершённые filesystem-транзакции после падения процесса. */
    fun reconcile(captures: List<Capture>) {
        val referenced = captures.map { it.id }.toSet()

        // Staged delete is destructive only after state no longer references the capture.
        audioDir.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith(DELETED_PREFIX) }
            .forEach { trash ->
                val captureId = trash.name.removePrefix(DELETED_PREFIX)
                if (runCatching { UUID.fromString(captureId) }.isFailure) return@forEach
                val target = File(audioDir, captureId)
                when {
                    captureId !in referenced -> trash.deleteRecursively()
                    !target.exists() -> move(trash, target)
                    else -> error("Конфликт восстановления аудио $captureId; обе копии сохранены")
                }
            }

        // A referenced capture may have both final and pending copies after a crash. Never
        // discard a non-empty copy merely because the file names match.
        captures.forEach { capture ->
            val directory = File(audioDir, capture.id)
            val saved = File(directory, "saved.m4a")
            val pending = File(pendingDir, "${capture.id}.m4a")
            if (!pending.exists()) return@forEach
            check(pending.isFile) { "Некорректный pending-файл ${capture.id}" }
            when {
                !saved.exists() && pending.length() > 0L -> {
                    require(directory.mkdirs() || directory.isDirectory)
                    move(pending, saved)
                }
                !saved.exists() -> error("Пустая pending-запись ${capture.id} сохранена для восстановления")
                !saved.isFile -> error("Некорректный финальный аудиофайл ${capture.id}")
                saved.length() == 0L && pending.length() > 0L -> {
                    check(saved.delete()) { "Не удалось заменить пустой финальный аудиофайл" }
                    move(pending, saved)
                }
                saved.length() > 0L && pending.length() == 0L -> check(pending.delete())
                saved.length() == 0L && pending.length() == 0L ->
                    error("Пустые копии аудио ${capture.id} сохранены для диагностики")
                sameBytes(saved, pending) -> check(pending.delete())
                else -> error("Конфликт аудиокопий ${capture.id}; обе копии сохранены")
            }
        }

        // Move may complete before state commit. Return an orphan final file to pending.
        // Conflicting non-empty copies are never overwritten or recursively deleted.
        audioDir.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(DELETED_PREFIX) }
            .forEach { directory ->
                val captureId = directory.name
                if (runCatching { UUID.fromString(captureId) }.isFailure || captureId in referenced) return@forEach
                val entries = directory.listFiles().orEmpty()
                val saved = File(directory, "saved.m4a")
                val unexpected = entries.filter { it.name != "saved.m4a" }
                check(unexpected.isEmpty()) { "Неизвестные файлы orphan-аудио $captureId сохранены для восстановления" }
                val pending = File(pendingDir, "$captureId.m4a")
                when {
                    !saved.exists() -> {
                        if (entries.isEmpty()) check(directory.delete())
                    }
                    !saved.isFile -> error("Некорректный orphan-аудиофайл $captureId")
                    !pending.exists() && saved.length() > 0L -> { move(saved, pending); check(directory.delete()) }
                    !pending.exists() -> error("Пустой orphan-аудиофайл $captureId сохранён для восстановления")
                    !pending.isFile -> error("Некорректный pending-файл $captureId")
                    saved.length() == 0L && pending.length() > 0L -> { check(saved.delete()); check(directory.delete()) }
                    saved.length() > 0L && pending.length() == 0L -> {
                        check(pending.delete()); move(saved, pending); check(directory.delete())
                    }
                    saved.length() > 0L && sameBytes(saved, pending) -> { check(saved.delete()); check(directory.delete()) }
                    else -> error("Конфликт orphan-аудио $captureId; обе копии сохранены")
                }
            }
    }

    fun relative(file: File): String {
        val canonicalRoot = root.canonicalFile.toPath()
        val canonical = file.canonicalFile.toPath()
        require(canonical.startsWith(canonicalRoot))
        return canonicalRoot.relativize(canonical).toString().replace(File.separatorChar, '/')
    }

    fun resolveAudio(capture: Capture): File {
        val relative = capture.audioFileName ?: error("Аудио ещё не готово")
        val candidate = File(root, relative).canonicalFile
        val allowed = File(audioDir, capture.id).canonicalFile
        require(candidate.parentFile == allowed)
        require(candidate.isFile && candidate.length() > 0)
        require(!Files.isSymbolicLink(candidate.toPath()))
        return candidate
    }

    fun stageDeleteCaptureAudio(captureId: String): File? {
        UUID.fromString(captureId)
        val directory = File(audioDir, captureId).canonicalFile
        require(directory.parentFile == audioDir.canonicalFile)
        if (!directory.exists()) return null
        val trash = File(audioDir, "$DELETED_PREFIX$captureId")
        require(!trash.exists())
        move(directory, trash)
        return trash
    }

    fun restoreStagedAudio(captureId: String, trash: File?) {
        if (trash == null || !trash.exists()) return
        val directory = File(audioDir, captureId)
        require(!directory.exists())
        move(trash, directory)
    }

    fun finishStagedDelete(trash: File?) {
        trash?.takeIf { it.exists() }?.deleteRecursively()
    }

    fun deleteCaptureAudio(captureId: String) {
        finishStagedDelete(stageDeleteCaptureAudio(captureId))
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun marker(file: File) = File(root, ".${file.name}.initialized")

    private fun interruptedWrites(file: File): List<File> {
        val prefix = ".${file.name}."
        return root.listFiles().orEmpty().filter { candidate ->
            candidate.isFile && candidate.name.startsWith(prefix) && candidate.name.endsWith(".tmp")
        }
    }

    private fun sameBytes(first: File, second: File): Boolean {
        if (first.length() != second.length()) return false
        first.inputStream().buffered().use { a ->
            second.inputStream().buffered().use { b ->
                val left = ByteArray(DEFAULT_BUFFER_SIZE)
                val right = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val ac = a.read(left); val bc = b.read(right)
                    if (ac != bc) return false
                    if (ac < 0) return true
                    for (index in 0 until ac) if (left[index] != right[index]) return false
                }
            }
        }
    }

    private fun move(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
        }
    }

    private companion object {
        const val DELETED_PREFIX = ".deleted-"
    }
}
