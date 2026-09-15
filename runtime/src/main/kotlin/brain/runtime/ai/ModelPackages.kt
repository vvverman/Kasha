package brain.runtime.ai

import brain.studio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

typealias ModelPackageSpec = brain.ai.ModelArtifact

object JvmModelManifest {
    val packages = brain.ai.ModelArtifacts.packages
}

class JvmAiPackageGateway(
    private val root: Path,
    private val bundled: Map<String, Path> = emptyMap(),
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30)).build(),
    private val artifacts: Map<String, ModelPackageSpec> = JvmModelManifest.packages,
) : AiPackageGateway {
    override val available = true
    private val modelDir = root.resolve("models")
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val live = ConcurrentHashMap<String, AiPackageState>()
    private data class Fingerprint(val path: Path, val size: Long, val modified: java.nio.file.attribute.FileTime)
    private val verified = ConcurrentHashMap<String, Fingerprint>()

    init { Files.createDirectories(modelDir) }

    override suspend fun states(): List<AiPackageState> = withContext(Dispatchers.IO) {
        AiCatalog.engines.filter { it.locality == AiLocality.LOCAL }.map { engine ->
            live[engine.id]?.takeIf { it.downloading }
                ?: AiPackageState(engine.id, installed = modelPath(engine.id) != null, error = live[engine.id]?.error)
        }
    }

    fun modelPath(engineId: String): Path? {
        // Ресурсы установщика уже проверены упаковкой. В Windows Qwen разделён
        // штатным GGUF splitter: hash одного shard нельзя сравнивать с hash исходного GGUF.
        bundled[engineId]?.takeIf { Files.isRegularFile(it) && Files.size(it) > 0 }?.let { return it }
        val spec = artifacts[engineId] ?: return null
        val target = modelDir.resolve(spec.fileName)
        if (!Files.isRegularFile(target)) return null
        val fingerprint = Fingerprint(target.toAbsolutePath(), Files.size(target), Files.getLastModifiedTime(target))
        if (verified[engineId] == fingerprint) return target
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(target).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                val count = input.read(buffer); if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        if (actual == spec.sha256) { verified[engineId] = fingerprint; return target }
        verified.remove(engineId)
        return null
    }

    suspend fun <T> withModel(engineId: String, action: suspend (Path) -> T): T =
        locks.computeIfAbsent(engineId) { Mutex() }.withLock {
            withContext(Dispatchers.IO) { action(modelPath(engineId) ?: error("modelNotInstalled")) }
        }

    override suspend fun install(engineId: String) {
        val spec = artifacts[engineId] ?: error("Для этой модели нет автоматического пакета")
        locks.computeIfAbsent(engineId) { Mutex() }.withLock {
            withContext(Dispatchers.IO) {
                if (modelPath(engineId) != null) return@withContext
                Files.createDirectories(modelDir)
                val target = modelDir.resolve(spec.fileName)
                val part = modelDir.resolve(".${spec.fileName}.part")
                val marker = modelDir.resolve("${spec.fileName}.sha256")
                Files.deleteIfExists(part)
                live[engineId] = AiPackageState(engineId, installed = false, downloading = true, progress = 0f)
                try {
                    require(URI(spec.url).scheme == "https") { "Модель должна загружаться по HTTPS" }
                    currentCoroutineContext().ensureActive()
                    val request = HttpRequest.newBuilder(URI(spec.url)).GET().timeout(Duration.ofHours(4)).build()
                    val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                    if (response.statusCode() !in 200..299 || response.uri().scheme != "https") {
                        response.body().close()
                        error("Не удалось скачать модель: HTTP ${response.statusCode()}")
                    }
                    val total = response.headers().firstValueAsLong("content-length").orElse(-1L)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var written = 0L
                    response.body().use { input ->
                        if (total > 0) check(Files.getFileStore(modelDir).usableSpace >= total) { "Недостаточно места для модели" }
                        Files.newOutputStream(part).use { output ->
                            val buffer = ByteArray(1024 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer); if (count < 0) break
                                output.write(buffer, 0, count); digest.update(buffer, 0, count); written += count
                                if (total > 0) live[engineId] = AiPackageState(engineId, installed = false,
                                    downloading = true, progress = (written.toDouble() / total).toFloat().coerceIn(0f, 1f))
                            }
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                    require(actual.equals(spec.sha256, true)) { "SHA-256 модели не совпал; файл удалён" }
                    try { Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                    catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(part, target, StandardCopyOption.REPLACE_EXISTING) }
                    verified.remove(engineId)
                    Files.writeString(marker, spec.sha256)
                    live.remove(engineId)
                } catch (e: Exception) {
                    Files.deleteIfExists(part)
                    live[engineId] = AiPackageState(engineId, installed = false, error = if (e is CancellationException) null else e.message)
                    throw e
                }
            }
        }
    }

    override suspend fun remove(engineId: String) = locks.computeIfAbsent(engineId) { Mutex() }.withLock {
        withContext(Dispatchers.IO) {
            require(engineId !in bundled || bundled[engineId]?.let { Files.isRegularFile(it) } != true) { "Встроенную модель удалить нельзя" }
            artifacts[engineId]?.let { spec ->
                Files.deleteIfExists(modelDir.resolve(spec.fileName))
                Files.deleteIfExists(modelDir.resolve("${spec.fileName}.sha256"))
                Files.deleteIfExists(modelDir.resolve(".${spec.fileName}.part"))
            }
            verified.remove(engineId); live.remove(engineId)
            Unit
        }
    }
}
