package brain.runtime.ai

import brain.studio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
) : AiPackageGateway {
    override val available = true
    private val modelDir = root.resolve("models")
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val live = ConcurrentHashMap<String, AiPackageState>()

    init { Files.createDirectories(modelDir) }

    override suspend fun states(): List<AiPackageState> = withContext(Dispatchers.IO) {
        AiCatalog.engines.filter { it.locality == AiLocality.LOCAL }.map { engine ->
            live[engine.id] ?: AiPackageState(engine.id, installed = modelPath(engine.id) != null)
        }
    }

    fun modelPath(engineId: String): Path? {
        bundled[engineId]?.takeIf { Files.isRegularFile(it) }?.let { return it }
        val spec = JvmModelManifest.packages[engineId] ?: return null
        val target = modelDir.resolve(spec.fileName)
        val marker = modelDir.resolve("${spec.fileName}.sha256")
        if (!Files.isRegularFile(target) || !Files.isRegularFile(marker)) return null
        return target.takeIf { runCatching { Files.readString(marker).trim().equals(spec.sha256, true) }.getOrDefault(false) }
    }

    override suspend fun install(engineId: String) {
        val spec = JvmModelManifest.packages[engineId] ?: error("Для этой модели нет автоматического пакета")
        locks.computeIfAbsent(engineId) { Mutex() }.withLock {
            if (modelPath(engineId) != null) return
            withContext(Dispatchers.IO) {
                Files.createDirectories(modelDir)
                val target = modelDir.resolve(spec.fileName)
                val part = modelDir.resolve(".${spec.fileName}.part")
                val marker = modelDir.resolve("${spec.fileName}.sha256")
                Files.deleteIfExists(part)
                live[engineId] = AiPackageState(engineId, installed = false, downloading = true, progress = 0f)
                try {
                    val request = HttpRequest.newBuilder(URI(spec.url)).GET().timeout(Duration.ofHours(4)).build()
                    val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                    require(response.statusCode() in 200..299) { "Не удалось скачать модель: HTTP ${response.statusCode()}" }
                    val total = response.headers().firstValueAsLong("content-length").orElse(-1L)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var written = 0L
                    response.body().use { input ->
                        Files.newOutputStream(part).use { output ->
                            val buffer = ByteArray(1024 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                                written += count
                                if (total > 0) live[engineId] = AiPackageState(
                                    engineId,
                                    installed = false,
                                    downloading = true,
                                    progress = (written.toDouble() / total).toFloat().coerceIn(0f, 1f),
                                )
                            }
                        }
                    }
                    val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    require(actual.equals(spec.sha256, true)) { "SHA-256 модели не совпал; файл удалён" }
                    try {
                        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    } catch (_: Exception) {
                        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                    Files.writeString(marker, spec.sha256)
                    live[engineId] = AiPackageState(engineId, installed = true)
                } catch (e: Exception) {
                    Files.deleteIfExists(part)
                    live[engineId] = AiPackageState(engineId, installed = false, error = e.message)
                    throw e
                }
            }
        }
    }

    override suspend fun remove(engineId: String) = withContext(Dispatchers.IO) {
        require(engineId !in bundled || bundled[engineId]?.let { Files.isRegularFile(it) } != true) {
            "Встроенную модель удалить нельзя"
        }
        val spec = JvmModelManifest.packages[engineId] ?: return@withContext
        Files.deleteIfExists(modelDir.resolve(spec.fileName))
        Files.deleteIfExists(modelDir.resolve("${spec.fileName}.sha256"))
        Files.deleteIfExists(modelDir.resolve(".${spec.fileName}.part"))
        live[engineId] = AiPackageState(engineId, installed = false)
    }
}
