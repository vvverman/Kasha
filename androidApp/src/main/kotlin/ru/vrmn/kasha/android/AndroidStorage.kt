package ru.vrmn.kasha.android

import brain.model.Capture
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
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

    fun read(file: File): String? = file.takeIf { it.isFile }?.readText(Charsets.UTF_8)

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
        .sortedBy { it.name }

    fun acceptPending(source: File, captureId: String): File {
        require(source.isFile && source.length() > 0)
        require(source.parentFile?.canonicalFile == pendingDir.canonicalFile)
        UUID.fromString(captureId)
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
        val directory = File(audioDir, captureId).canonicalFile
        require(directory.parentFile == audioDir.canonicalFile)
        if (!directory.exists()) return null
        val trash = File(audioDir, ".deleted-$captureId-${UUID.randomUUID()}")
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
}
