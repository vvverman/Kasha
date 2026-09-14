package brain.desktop

import brain.domain.ProjectOrder
import brain.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.*
import java.security.MessageDigest
import kotlin.time.TimeSource

/** Явный диагностический режим, не запускается при обычном открытии приложения. */
object SelfTest {
    suspend fun run(resources: Path, output: Path, fixture: Path) {
        println("Самопроверка: resources=$resources; Java=${System.getProperty("java.home")}")
        Files.createDirectories(output)
        val started = TimeSource.Monotonic.markNow()
        val services = DesktopServices(output.resolve("data"), resources, cpuOnly = true)
        try {
            fun hash(path: Path): String {
                val digest = MessageDigest.getInstance("SHA-256")
                Files.newInputStream(path).use { input -> val buffer = ByteArray(1024 * 1024); while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) } }
                return digest.digest().joinToString("") { "%02x".format(it) }
            }
            println("Самопроверка: контрольные суммы исходных моделей")
            check(hash(resources.resolve("models/ggml-small.bin")) == "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b")
            check(hash(resources.resolve("models/Qwen3-4B-Q4_K_M.gguf")) == "7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5")
            val work = services.repository.createProject(ProjectDraft("Разработка приложения", instruction = "Разработка программ, запись звука, интерфейс, кнопки и сохранение заметок."))
            val cooking = services.repository.createProject(ProjectDraft("Кулинария", instruction = "Рецепты и приготовление еды. Не складывать сюда разработку программ."))
            val pin = services.repository.createProject(ProjectDraft("Закреплённый"))
            services.repository.pinProject(pin.id, true)
            val capture = services.store.createCapture("fixture.wav", Files.readAllBytes(fixture))
            println("Самопроверка: полный русский сценарий")
            val ready = services.processing.process(capture.id)
            check(ready.status == CaptureStatus.READY) { "Обработка: ${ready.status}: ${ready.message}" }
            check(ready.llmApplied && ready.rankingApplied) { "ИИ не завершился: ${ready.message}" }
            check(ready.transcript.contains("нельзя", ignoreCase = true) && ready.preparedText.contains("нельзя", ignoreCase = true))
            check((ready.relevance[work.id] ?: -1) > (ready.relevance[cooking.id] ?: -1)) { "Неправильный порядок проектов: ${ready.relevance}" }
            check(ready.compactAudioFileName != null && ready.compactDurationSeconds > 0 && ready.compactDurationSeconds < ready.durationSeconds)
            check(hash(services.store.resolveAudio(ready)) == hash(fixture))
            val ordered = ProjectOrder.sorted(services.store.snapshot().projects, ready.relevance)
            check(ordered.first().id == pin.id)
            val note = services.repository.distribute(ready.id, DistributionRequest(work.id))
            check(services.repository.distribute(ready.id, DistributionRequest(work.id)).id == note.id)
            println("Самопроверка: восстановление записи и добавление в заметку")
            val id = java.util.UUID.randomUUID().toString()
            val journal = output.resolve("data/pending/$id.wav")
            WavJournal(journal).use { it.append(ByteArray(3200), 3200) }
            val source2 = services.recorder.recoverPending()
            services.scope.cancel(); services.scope.coroutineContext[Job]?.join()
            services.store.updateCapture(source2.id) { it.copy(status = CaptureStatus.NEEDS_MODEL) }
            services.repository.updateCaptureDraft(source2.id, CaptureDraftUpdate("Добавление", "Вторая мысль."))
            val appended = services.repository.distribute(source2.id, DistributionRequest(work.id, note.id))
            check(appended.body == note.body + "\n\nВторая мысль.")
            check(services.store.snapshot().captures.count { it.noteId == note.id } == 2)
            val report = buildJsonObject {
                put("passed", true); put("os", System.getProperty("os.name")); put("arch", System.getProperty("os.arch"))
                put("javaHome", System.getProperty("java.home")); put("resources", resources.toString()); put("inferenceDevice", "CPU")
                put("transcript", ready.transcript); put("preparedText", ready.preparedText); put("title", ready.title)
                put("llmApplied", ready.llmApplied); put("rankingApplied", ready.rankingApplied)
                put("relevantScore", ready.relevance[work.id]!!); put("unrelatedScore", ready.relevance[cooking.id]!!)
                put("originalSeconds", ready.durationSeconds); put("compactSeconds", ready.compactDurationSeconds)
                put("originalSha256", hash(fixture)); put("seconds", started.elapsedNow().inWholeMilliseconds / 1000.0)
                put("diskRecovery", true); put("sourcesPreserved", true)
            }
            Files.writeString(output.resolve("self-test.json"), Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report))
            println("MACOS BUNDLED SELF TEST PASSED")
        } finally { services.close() }
    }
}
