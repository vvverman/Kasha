package brain.desktop

import brain.domain.*
import brain.model.*
import brain.runtime.*
import brain.runtime.ai.*
import brain.studio.*
import kotlinx.coroutines.*
import java.nio.file.*
import java.util.concurrent.atomic.AtomicBoolean

class DesktopServices(val root: Path, val resources: Path, cpuOnly: Boolean = false) : AutoCloseable {
    private val instanceLock: DesktopInstanceLock
    private val closed = AtomicBoolean(false)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val store: FileBrainStore
    val processing: LocalProcessing
    val studioProcessor: StudioProcessor
    val recorder: DesktopRecorder
    val audio: DesktopAudio
    val repository: StudioRepository
    val simulated = Files.exists(resources.resolve("demo-mode.txt"))
    init {
        instanceLock = DesktopInstanceLock.acquire(root)
        try {
            val env = if (simulated) mapOf("KASHA_FFMPEG" to bundledExecutable(resources,"ffmpeg")) else bundledEnvironment(resources)
            store = FileBrainStore(root, runtimeStatus = { RuntimeStatus(localOnly=true, simulated=simulated) }, singleCurrent=true)
            val runner = DesktopInferenceRunner(cpuOnly)
            processing = LocalProcessing(store, env, runner)
            val prefs = PreferenceStore(root)
            val bundledModels = if (simulated) emptyMap() else mapOf(
                AiCatalog.DEFAULT_STT to Path.of(env.getValue("KASHA_WHISPER_MODEL")),
                AiCatalog.DEFAULT_TEXT to Path.of(env.getValue("KASHA_LLAMA_MODEL")),
            )
            val packages = JvmAiPackageGateway(root, bundledModels)
            // Desktop и локальный Web runtime используют один адаптер защищённого хранилища.
            val cloud = JvmCloudAiGateway(root)
            val intelligence: Intelligence = if(simulated) DemoIntelligence() else RoutedStudioIntelligence(prefs,env,root,packages,cloud,runner)
            studioProcessor = StudioProcessor(store,prefs,intelligence,env.getValue("KASHA_FFMPEG"),runner)
            val baseRepository = StudioDiskRepository(store,studioProcessor,prefs,scope)
            // Probe и inference обязаны использовать одну конфигурацию устройства исполнения.
            repository = AiStudioRepository(baseRepository, packages, cloud, JvmAiRuntimeProbe(env, runner)::available)
            recorder = DesktopRecorder(root,store){studioProcessor.enqueue(it,scope)}
            audio = DesktopAudio(store,env.getValue("KASHA_FFMPEG"),root,scope)
        } catch(e: Exception) { instanceLock.close();scope.cancel();throw e }
    }
    override fun close() {
        if(!closed.compareAndSet(false,true))return
        try { recorder.close() } catch (e: Exception) { closed.set(false); throw e }
        audio.stop();scope.cancel()
        runBlocking { withTimeoutOrNull(5000){scope.coroutineContext[Job]?.join()} }
        instanceLock.close()
    }
}

internal fun bundledExecutable(resources: Path, name: String, os: DesktopOs = DesktopPlatform.os): String {
    val file = DesktopPlatform.executableCandidates(name, os).asSequence()
        .map { resources.resolve("bin").resolve(it).toAbsolutePath() }.firstOrNull(Files::isRegularFile)
        ?: error("В пакете отсутствует $name. Переустановите приложение целиком.")
    if (os != DesktopOs.WINDOWS) {
        require(Files.isExecutable(file)) { "Файл $name не имеет права запуска. Переустановите приложение целиком." }
    }
    return file.toString()
}

internal fun bundledModel(resources: Path, name: String): String {
    val models = resources.resolve("models")
    val direct = models.resolve(name).toAbsolutePath()
    if (Files.isRegularFile(direct) && Files.size(direct) > 1_000_000) return direct.toString()
    if (name.endsWith(".gguf") && Files.isDirectory(models)) {
        val prefix = "${name.removeSuffix(".gguf")}-00001-of-"
        val firstShard = Files.list(models).use { files ->
            files.filter { path ->
                val fileName = path.fileName.toString()
                Files.isRegularFile(path) && fileName.startsWith(prefix) && fileName.endsWith(".gguf")
            }.sorted().findFirst().orElse(null)
        }
        if (firstShard != null && Files.size(firstShard) > 1_000_000) return firstShard.toAbsolutePath().toString()
    }
    error("В пакете отсутствует модель $name")
}

fun bundledEnvironment(resources:Path):Map<String,String> = mapOf(
    "KASHA_WHISPER_CLI" to bundledExecutable(resources,"whisper-cli"),
    "KASHA_WHISPER_MODEL" to bundledModel(resources,"ggml-small.bin"),
    "KASHA_LLAMA_CLI" to bundledExecutable(resources,"llama-completion"),
    "KASHA_LLAMA_MODEL" to bundledModel(resources,"Qwen3-4B-Q4_K_M.gguf"),
    "KASHA_FFMPEG" to bundledExecutable(resources,"ffmpeg"),
)
