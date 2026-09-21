@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package brain.ios

import brain.ai.ModelArtifact
import brain.ai.ModelArtifacts
import brain.studio.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.CoreCrypto.*
import platform.Foundation.*
import platform.darwin.NSObject
import platform.posix.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** App-private модели. Проверка hash предшествует атомарной публикации, исполнение держит тот же lock. */
internal class IosModelPackages(
    private val root: String,
    private val artifacts: Map<String, ModelArtifact> = ModelArtifacts.packages,
    private val selected: () -> AiSelection = { AiSelection() },
    private val download: suspend (String, String) -> Unit = ::downloadIosModel,
) : AiPackageGateway {
    override val available = true
    private val operation = Mutex()
    private val live = MutableStateFlow(artifacts.keys.associateWith { AiPackageState(it, false) })
    private val verified = mutableMapOf<String, Pair<Long, Double>>()

    private fun spec(id: String) = artifacts[id]?.also {
        require(it.fileName.isNotBlank() && '/' !in it.fileName && '\\' !in it.fileName && it.fileName != "." && it.fileName != "..")
        require(it.sha256.matches(Regex("[a-fA-F0-9]{64}")))
    } ?: error("platformUnavailable")
    private fun path(id: String) = IosPaths.child(root, spec(id).fileName)
    private fun publish(state: AiPackageState) { live.value = live.value + (state.engineId to state) }

    override suspend fun states(): List<AiPackageState> {
        if (!operation.tryLock()) return live.value.values.toList()
        try {
            return withContext(Dispatchers.Default) {
                artifacts.keys.forEach { id ->
                    val before = live.value.getValue(id)
                    publish(before.copy(installed = verifiedPath(id) != null, downloading = false))
                }
                live.value.values.toList()
            }
        } finally { operation.unlock() }
    }

    override suspend fun install(engineId: String) = operation.withLock {
        withContext(Dispatchers.Default) {
            val artifact = spec(engineId)
            require(NSURL.URLWithString(artifact.url)?.scheme == "https") { "modelInstallFailed" }
            if (verifiedPath(engineId) != null) return@withContext
            IosPaths.directory(root)
            val part = IosPaths.child(root, ".${artifact.fileName}.${NSUUID().UUIDString}.part")
            publish(AiPackageState(engineId, false, downloading = true))
            try {
                download(artifact.url, part)
                check(iosModelSha256(part).equals(artifact.sha256, true)) { "modelInstallFailed" }
                currentCoroutineContext().ensureActive()
                // part и target находятся в одном каталоге. Старый target не удаляется до rename.
                check(rename(part, path(engineId)) == 0) { "modelInstallFailed" }
                verified[engineId] = fingerprint(path(engineId)) ?: error("modelInstallFailed")
                publish(AiPackageState(engineId, true))
            } catch (cancelled: CancellationException) {
                publish(AiPackageState(engineId, false))
                throw cancelled
            } catch (error: Exception) {
                publish(AiPackageState(engineId, false, error = "modelInstallFailed"))
                throw error
            } finally { NSFileManager.defaultManager.removeItemAtPath(part, null) }
        }
    }

    override suspend fun remove(engineId: String) = operation.withLock {
        withContext(Dispatchers.Default) {
            spec(engineId)
            val selection = selected()
            require(AiRole.entries.none { selection.engineId(it) == engineId }) { "modelInUse" }
            if (IosPaths.exists(path(engineId)))
                check(NSFileManager.defaultManager.removeItemAtPath(path(engineId), null)) { "modelRemoveFailed" }
            verified.remove(engineId)
            publish(AiPackageState(engineId, false))
        }
    }

    suspend fun <T> withModel(engineId: String, use: suspend (String) -> T): T = operation.withLock {
        val model = withContext(Dispatchers.Default) { verifiedPath(engineId) } ?: error("modelNotInstalled")
        currentCoroutineContext().ensureActive()
        use(model)
    }

    private suspend fun verifiedPath(id: String): String? {
        val target = path(id)
        val stamp = fingerprint(target) ?: return null
        if (verified[id] != stamp) {
            if (!iosModelSha256(target).equals(spec(id).sha256, true)) {
                verified.remove(id)
                return null
            }
            verified[id] = stamp
        }
        return target
    }
    private fun fingerprint(path: String): Pair<Long, Double>? {
        if (!IosPaths.exists(path)) return null
        val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, null) ?: error("modelInstallFailed")
        check(attributes[NSFileType] == NSFileTypeRegular) { "modelInstallFailed" }
        val size = (attributes[NSFileSize] as? NSNumber)?.longLongValue ?: return null
        val changed = (attributes[NSFileModificationDate] as? NSDate)?.timeIntervalSince1970 ?: return null
        return (size to changed).takeIf { size > 0 }
    }
}

internal suspend fun iosModelSha256(path: String): String = memScoped {
    val file = fopen(path, "rb") ?: error("modelInstallFailed")
    try {
        val context = alloc<CC_SHA256_CTX>()
        val buffer = allocArray<UByteVar>(65536)
        val digest = allocArray<UByteVar>(32)
        check(CC_SHA256_Init(context.ptr) == 1)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = fread(buffer, 1u, 65536u, file).toInt()
            if (count == 0) { check(ferror(file) == 0) { "modelInstallFailed" }; break }
            check(CC_SHA256_Update(context.ptr, buffer, count.toUInt()) == 1)
        }
        check(CC_SHA256_Final(digest, context.ptr) == 1)
        (0 until 32).joinToString("") { digest[it].toString(16).padStart(2, '0') }
    } finally { fclose(file) }
}

private class HttpsModelRedirects : NSObject(), NSURLSessionTaskDelegateProtocol {
    override fun URLSession(session: NSURLSession, task: NSURLSessionTask,
        willPerformHTTPRedirection: NSHTTPURLResponse, newRequest: NSURLRequest,
        completionHandler: (NSURLRequest?) -> Unit) {
        completionHandler(newRequest.takeIf { it.URL?.scheme == "https" })
    }
}

/** NSURLSession кладёт данные сразу на диск; большие модели не собираются в NSData/ByteArray. */
internal suspend fun downloadIosModel(url: String, part: String) {
    val source = NSURL.URLWithString(url) ?: error("modelInstallFailed")
    require(source.scheme == "https") { "modelInstallFailed" }
    val configuration = NSURLSessionConfiguration.ephemeralSessionConfiguration.apply {
        timeoutIntervalForResource = 14400.0
        HTTPShouldSetCookies = false
    }
    val session = NSURLSession.sessionWithConfiguration(configuration, HttpsModelRedirects(), null)
    try {
        suspendCancellableCoroutine<Unit> { continuation ->
            val task = session.downloadTaskWithURL(source) { file, response, failure ->
                try {
                    if (continuation.isActive) {
                        check(failure == null && (response as? NSHTTPURLResponse)?.statusCode?.let { it in 200L..299L } == true && response?.URL?.scheme == "https") { "modelInstallFailed" }
                        check(file?.path != null && NSFileManager.defaultManager.copyItemAtPath(file.path!!, part, null)) { "modelInstallFailed" }
                        if (continuation.isActive) continuation.resume(Unit)
                        else NSFileManager.defaultManager.removeItemAtPath(part, null)
                    }
                } catch (error: Exception) {
                    NSFileManager.defaultManager.removeItemAtPath(part, null)
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
            continuation.invokeOnCancellation { task.cancel() }
            task.resume()
        }
    } finally { session.invalidateAndCancel() }
}
