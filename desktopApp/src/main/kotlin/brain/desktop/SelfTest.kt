package brain.desktop

import brain.ai.ModelArtifacts
import brain.domain.LocalModelText
import brain.domain.ProjectOrder
import brain.model.*
import brain.studio.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.*
import java.security.MessageDigest
import kotlin.time.TimeSource

/** Явная проверка настоящего приложения, не запускается при обычном открытии. */
object SelfTest {
    suspend fun run(resources: Path, output: Path, fixture: Path) {
        Files.createDirectories(output)
        Files.deleteIfExists(output.resolve("self-test.json"))
        val networkReachable = try {
            Socket().use { it.connect(InetSocketAddress("1.1.1.1", 443), 1500) }; true
        } catch (_: java.io.IOException) { false } catch (_: SecurityException) { false }
        check(!networkReachable) { "Для проверки локального AI внешняя сеть должна быть запрещена" }
        val started = TimeSource.Monotonic.markNow()
        val services = DesktopServices(output.resolve("data"), resources, cpuOnly = true)
        val selection = AiSelection()
        var savedNote: Note? = null
        try {
            fun hash(path: Path): String {
                val digest = MessageDigest.getInstance("SHA-256")
                Files.newInputStream(path).use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
                }
                return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            }
            val sourceHash = hash(fixture)
            val env = bundledEnvironment(resources)
            val speechModel = ModelArtifacts.packages.getValue(selection.speechToText)
            val textModel = ModelArtifacts.packages.getValue(selection.text)
            check(hash(Path.of(env.getValue("KASHA_WHISPER_MODEL"))) == speechModel.sha256)
            val qwen = Path.of(env.getValue("KASHA_LLAMA_MODEL"))
            // Windows содержит штатные GGUF shards, полученные из проверенного pinned-файла.
            // Исполнение ниже проверяет загрузку всего набора, а не только первого shard.
            if (qwen.fileName.toString() == textModel.fileName) check(hash(qwen) == textModel.sha256)
            else check(qwen.fileName.toString().startsWith(textModel.fileName.removeSuffix(".gguf") + "-00001-of-"))
            val repo = services.repository
            repo.savePreferences(Preferences(autoRecord = false, language = "ru", ai = selection))
            val capabilities = (repo as AiPlatformServices).aiExecution.roles(selection)
            check(capabilities.size == 3 && capabilities.all { it.executable && it.selectedEngineId == selection.engineId(it.role) })
            val work = repo.createProject(ProjectDraft("Разработка приложения", instruction = "Запись голоса, интерфейс и сохранение заметок."))
            val pin = repo.createProject(ProjectDraft("Закреплённый"))
            repo.pinProject(pin.id, true)
            val projectsBefore = repo.snapshot().projects
            val capture = services.store.createCapture("fixture.wav", Files.readAllBytes(fixture))
            // Именно StudioProcessor/RoutedStudioIntelligence, используемые приложением,
            // а не сохранённый для совместимости прежний LocalProcessing.
            val transcribed = services.studioProcessor.process(capture.id)
            check(transcribed.status == CaptureStatus.READY) { "${transcribed.status}: ${transcribed.message}" }
            check(!transcribed.simulated && transcribed.transcript.any { it in 'А'..'я' })
            check(transcribed.inputSha256 == sourceHash)
            check(!transcribed.llmApplied) // «Привести в порядок» выполняется отдельно, как в UI.
            val tidied = repo.tidy(capture.id)
            check(tidied.llmApplied)
            LocalModelText.requirePreserved(transcribed.textToSave, tidied.textToSave)
            val ready = repo.rank(capture.id)
            check(ready.rankingApplied && ready.relevance.keys == projectsBefore.map { it.id }.toSet())
            check(ready.relevance.values.all { it in 0..4 })
            check(repo.snapshot().projects == projectsBefore)
            check(ProjectOrder.sorted(projectsBefore, ready.relevance).first().id == pin.id)
            check(ready.audioFinalized && ready.durationSeconds > 0 && Files.size(services.store.resolveAudio(ready)) > 0)
            check(hash(fixture) == sourceHash)
            check(repo.preferences().ai == selection)
            val note = repo.distribute(ready.id, DistributionRequest(work.id))
            check(repo.distribute(ready.id, DistributionRequest(work.id)).id == note.id)
            println("Самопроверка: восстановление и добавление в заметку")
            val id = java.util.UUID.randomUUID().toString()
            val journal = output.resolve("data/pending/$id.wav")
            WavJournal(journal).use { it.append(ByteArray(3200), 3200) }
            val source2 = services.recorder.recoverPending()
            services.scope.cancel(); services.scope.coroutineContext[Job]?.join()
            services.store.updateCapture(source2.id) { it.copy(status = CaptureStatus.NEEDS_MODEL) }
            repo.updateCaptureDraft(source2.id, CaptureDraftUpdate("Добавление", "Вторая мысль."))
            val appended = repo.distribute(source2.id, DistributionRequest(work.id, note.id))
            check(appended.body == note.body + "\n\nВторая мысль.")
            check(services.store.snapshot().captures.count { it.noteId == note.id } == 2)
            savedNote = appended
            val report = buildJsonObject {
                put("passed", true); put("os", System.getProperty("os.name")); put("arch", System.getProperty("os.arch"))
                put("javaHome", System.getProperty("java.home")); put("resources", resources.toString()); put("inferenceDevice", "CPU")
                put("transcript", ready.transcript); put("preparedText", ready.preparedText); put("title", ready.title)
                put("llmApplied", ready.llmApplied); put("rankingApplied", ready.rankingApplied)
                put("selectedSpeech", selection.speechToText); put("selectedText", selection.text); put("selectedRouting", selection.routing)
                put("applicationRouter", true); put("externalNetworkReachable", false)
                put("originalSha256", sourceHash); put("savedSeconds", ready.durationSeconds)
                put("seconds", started.elapsedNow().inWholeMilliseconds / 1000.0)
                put("diskRecovery", true); put("sourcesPreserved", true); put("fixtureUnchanged", true)
            }
            Files.writeString(output.resolve("self-test.json"), Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report))
        } finally { services.close() }
        DesktopServices(output.resolve("data"), resources, cpuOnly = true).use { reopened ->
            check(reopened.repository.snapshot().notes.single() == savedNote)
            check(reopened.repository.preferences().ai == selection)
        }
        println("DESKTOP BUNDLED AI SELF TEST PASSED")
    }
}
