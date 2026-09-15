package ru.vrmn.kasha.android

import brain.ai.ModelArtifacts
import brain.ai.ModelArtifact
import brain.studio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.AtomicMoveNotSupportedException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Один файловый менеджер пакетов для Whisper и llama.cpp; пользовательские данные не передаются. */
internal class AndroidWhisperPackages(
    private val directory: File,
    private val artifacts: Map<String, ModelArtifact> = ModelArtifacts.speech,
    private val nativeAvailable: () -> Boolean = { AndroidWhisperNative.available },
    private val open: (URL) -> HttpURLConnection = ::openModelHttps,
    private val engineAvailable: (String) -> Boolean = { nativeAvailable() },
) : AiPackageGateway {
    override val available get() = artifacts.keys.any(engineAvailable)
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val live = ConcurrentHashMap<String, AiPackageState>()
    private val verified = ConcurrentHashMap<String, Pair<Long, Long>>()

    fun hasVerifiedModel(ids: Set<String> = artifacts.keys): Boolean = verified.any { (id, fingerprint) ->
        val file = File(directory, spec(id).fileName)
        id in ids && engineAvailable(id) && file.isFile && (file.length() to file.lastModified()) == fingerprint
    }

    private fun lock(id: String) = locks.computeIfAbsent(id) { Mutex() }
    private fun spec(id: String) = artifacts[id] ?: error("platformUnavailable")

    override suspend fun states(): List<AiPackageState> = withContext(Dispatchers.IO) {
        if (!available) return@withContext emptyList()
        artifacts.keys.filter(engineAvailable).map { id ->
            live[id]?.takeIf { it.downloading } ?: lock(id).withLock {
                AiPackageState(id, installed = model(id) != null, error = live[id]?.error)
            }
        }
    }

    /** Проверка хеша до первого исполнения в процессе, затем — проверка размера/времени файла. */
    private suspend fun model(id: String): File? {
        val spec = spec(id)
        val target = File(directory, spec.fileName)
        if (!target.isFile) return null
        val fingerprint = target.length() to target.lastModified()
        if (verified[id] == fingerprint) return target
        val digest = MessageDigest.getInstance("SHA-256")
        target.inputStream().buffered().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        if (actual != spec.sha256) return null
        verified[id] = fingerprint
        return target
    }

    suspend fun <T> withModel(id: String, action: suspend (File) -> T): T = lock(id).withLock {
        check(engineAvailable(id)) { "androidAiNotConfigured" }
        withContext(Dispatchers.IO) { action(model(id) ?: error("androidAiNotConfigured")) }
    }

    override suspend fun install(engineId: String) = lock(engineId).withLock {
        withContext(Dispatchers.IO) {
            check(engineAvailable(engineId)) { "platformUnavailable" }
            val artifact = spec(engineId)
            if (model(engineId) != null) return@withContext
            check(directory.isDirectory || directory.mkdirs()) { "saveFailed" }
            val part = File(directory, ".${artifact.fileName}.part")
            live[engineId] = AiPackageState(engineId, false, downloading = true)
            try {
                val connection = open(URL(artifact.url))
                try {
                    val total = connection.contentLengthLong
                    if (total > 0) check(directory.usableSpace >= total) { "saveFailed" }
                    connection.inputStream.use { input ->
                        FileOutputStream(part).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var written = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                written += count
                                live[engineId] = AiPackageState(engineId, false, true,
                                    if (total > 0) (written.toDouble() / total).toFloat().coerceIn(0f, 1f) else null)
                            }
                            output.fd.sync()
                        }
                    }
                } finally { connection.disconnect() }
                currentCoroutineContext().ensureActive()
                val hash = MessageDigest.getInstance("SHA-256")
                part.inputStream().buffered().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        hash.update(buffer, 0, n)
                    }
                }
                check(hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == artifact.sha256) { "Повреждён пакет модели: SHA-256 не совпал. Повторите скачивание." }
                val target = File(directory, artifact.fileName)
                try { Files.move(part.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
                catch (_: AtomicMoveNotSupportedException) { Files.move(part.toPath(), target.toPath(), REPLACE_EXISTING) }
                verified[engineId] = target.length() to target.lastModified()
                live.remove(engineId)
            } catch (error: Exception) {
                live[engineId] = AiPackageState(engineId, false, error = if (error is CancellationException) null else error.message)
                throw error
            } finally { part.delete() }
        }
    }

    override suspend fun remove(engineId: String) = lock(engineId).withLock {
        withContext(Dispatchers.IO) {
            Files.deleteIfExists(File(directory, spec(engineId).fileName).toPath())
            verified.remove(engineId)
            live.remove(engineId)
            Unit
        }
    }

}

/** Никаких переадресаций на незашифрованное соединение. */
private fun openModelHttps(initial: URL): HttpURLConnection {
        var url = initial
        repeat(8) {
            require(url.protocol == "https") { "Не удалось скачать модель по HTTPS. Повторите скачивание." }
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            try {
                val status = connection.responseCode
                if (status in 200..299) return connection
                if (status !in setOf(301, 302, 303, 307, 308)) error("Не удалось скачать модель: HTTP $status")
                url = URL(url, connection.getHeaderField("Location") ?: error("Не удалось скачать модель по HTTPS. Повторите скачивание."))
            } catch (error: Exception) { connection.disconnect(); throw error }
            connection.disconnect()
        }
        error("Не удалось скачать модель по HTTPS. Повторите скачивание.")
    }
