package brain.desktop

import brain.domain.*
import brain.model.*
import brain.runtime.*
import brain.runtime.ai.*
import brain.studio.*
import kotlinx.coroutines.*
import java.nio.channels.FileChannel
import java.nio.file.*
import java.util.concurrent.atomic.AtomicBoolean

class DesktopServices(val root: Path, val resources: Path, cpuOnly: Boolean = false) : AutoCloseable {
    private val lockChannel: FileChannel
    private val lock: java.nio.channels.FileLock
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
        Files.createDirectories(root)
        lockChannel = FileChannel.open(root.resolve(".desktop.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        lock = try { lockChannel.tryLock() ?: error("Kasha уже запущен") } catch(e: Exception) { lockChannel.close(); throw e }
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
            val cloud = JvmCloudAiGateway(root, desktopSecretStore())
            val intelligence: Intelligence = if(simulated) DemoIntelligence() else RoutedStudioIntelligence(prefs,env,root,packages,cloud,runner)
            studioProcessor = StudioProcessor(store,prefs,intelligence,env.getValue("KASHA_FFMPEG"),runner)
            val baseRepository = StudioDiskRepository(store,studioProcessor,prefs,scope)
            repository = AiStudioRepository(baseRepository, packages, cloud)
            recorder = DesktopRecorder(root,store){studioProcessor.enqueue(it,scope)}
            audio = DesktopAudio(store,env.getValue("KASHA_FFMPEG"),root,scope)
        } catch(e: Exception) { lock.release();lockChannel.close();scope.cancel();throw e }
    }
    override fun close() {
        if(!closed.compareAndSet(false,true))return
        recorder.close();audio.stop();scope.cancel()
        runBlocking { withTimeoutOrNull(5000){scope.coroutineContext[Job]?.join()} }
        lock.release();lockChannel.close()
    }
}
private fun bundledExecutable(resources:Path,name:String):String {
    val file = DesktopPlatform.executableCandidates(name)
        .asSequence()
        .map { resources.resolve("bin").resolve(it).toAbsolutePath() }
        .firstOrNull(Files::isRegularFile)
        ?: error("В пакете отсутствует $name. Переустановите приложение целиком.")
    if (DesktopPlatform.os != DesktopOs.WINDOWS) {
        require(Files.isExecutable(file)) { "Файл $name не имеет права запуска. Переустановите приложение целиком." }
    }
    return file.toString()
}
fun bundledEnvironment(resources:Path):Map<String,String> {
    fun model(name:String)=resources.resolve("models/$name").toAbsolutePath().toString().also{require(Files.isRegularFile(Path.of(it))&&Files.size(Path.of(it))>1_000_000){"В пакете отсутствует модель $name"}}
    return mapOf("KASHA_WHISPER_CLI" to bundledExecutable(resources,"whisper-cli"),"KASHA_WHISPER_MODEL" to model("ggml-small.bin"),
        "KASHA_LLAMA_CLI" to bundledExecutable(resources,"llama-completion"),"KASHA_LLAMA_MODEL" to model("Qwen3-4B-Q4_K_M.gguf"),
        "KASHA_FFMPEG" to bundledExecutable(resources,"ffmpeg"))
}
