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
        val path = file.toPath()
        if (Files.notExists(path, NOFOLLOW_LINKS)) return null
        check(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Не удалось прочитать локальные данные Kasha" }
        return file.readText(Charsets.UTF_8)
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

        // Если удаление capture оборвалось: state решает, восстанавливать файл или удалять staged copy.
        audioDir.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith(DELETED_PREFIX) }
            .forEach { trash ->
                val captureId = trash.name.removePrefix(DELETED_PREFIX)
                if (runCatching { UUID.fromString(captureId) }.isFailure) return@forEach
                val target = File(audioDir, captureId)
                if (captureId in referenced && !target.exists()) move(trash, target)
                else trash.deleteRecursively()
            }

        // Если state уже содержит capture, его pending-дубликат не должен блокировать следующую запись.
        captures.forEach { capture ->
            val directory = File(audioDir, capture.id)
            val saved = File(directory, "saved.m4a")
            val pending = File(pendingDir, "${capture.id}.m4a")
            when {
                saved.isFile && pending.isFile -> pending.delete()
                !saved.isFile && pending.isFile -> {
                    require(directory.mkdirs() || directory.isDirectory)
                    move(pending, saved)
                }
            }
        }

        // Move мог завершиться до атомарной записи state. Возвращаем такой файл в pending.
        audioDir.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(DELETED_PREFIX) }
            .forEach { directory ->
                val captureId = directory.name
                if (runCatching { UUID.fromString(captureId) }.isFailure || captureId in referenced) return@forEach
                val saved = File(directory, "saved.m4a")
                val pending = File(pendingDir, "$captureId.m4a")
                if (saved.isFile && saved.length() > 0 && !pending.exists()) move(saved, pending)
                directory.deleteRecursively()
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
