package brain.runtime

import brain.model.*
import brain.studio.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class RepeatTranscriptionTest {
    @Test fun finalizedAudioIsTranscribedAgainWithoutRunningOtherRoles() = runBlocking<Unit> {
        val root = Files.createTempDirectory("kasha-repeat-stt-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val demo = DemoIntelligence(0)
            val stt = AtomicInteger()
            val text = AtomicInteger()
            val routing = AtomicInteger()
            val engine = object : Intelligence by demo {
                override suspend fun transcribe(file: String, language: String, example: String): String {
                    stt.incrementAndGet()
                    return demo.transcribe(file, language, example)
                }
                override suspend fun tidy(value: String, language: String): String {
                    text.incrementAndGet()
                    return demo.tidy(value, language)
                }
                override suspend fun rank(value: String, projects: List<Project>, language: String): Map<String, Int> {
                    routing.incrementAndGet()
                    return demo.rank(value, projects, language)
                }
            }
            val store = FileBrainStore(root, runtimeStatus = { RuntimeStatus(simulated = true) }, singleCurrent = true)
            val preferences = PreferenceStore(root)
            preferences.save(Preferences(autoRecord = false, language = "ru"))
            val repository = StudioDiskRepository(store,
                StudioProcessor(store, preferences, engine, "ffmpeg"), preferences, scope)
            val capture = repository.createDemo()
            withTimeout(15000) { while (store.capture(capture.id)!!.status.isWorking) delay(50) }
            val ready = store.capture(capture.id)!!
            assertEquals(CaptureStatus.READY, ready.status, ready.message)
            assertEquals(1, stt.get())
            assertEquals(0, text.get())
            assertEquals(0, routing.get())
            repository.createProject(ProjectDraft("Приложение"))
            val normalized = repository.tidy(capture.id)
            assertTrue(normalized.llmApplied)
            repository.rank(capture.id)
            val audio = Files.readAllBytes(store.resolveAudio(ready))
            preferences.save(preferences.read().copy(demoExample = "cooking"))
            val repeated = repository.retranscribe(capture.id)
            assertEquals(CaptureStatus.READY, repeated.status, repeated.message)
            assertTrue(repeated.transcript.startsWith("Рецепт ужина"), repeated.transcript)
            assertEquals(2, stt.get())
            assertEquals(1, text.get())
            assertEquals(1, routing.get())
            assertEquals("", repeated.preparedText)
            assertFalse(repeated.llmApplied)
            assertFalse(repeated.rankingApplied)
            assertTrue(repeated.relevance.isEmpty())
            assertEquals(CaptureTextVariant.TRANSCRIPTION, repeated.selectedTextVariant)
            assertEquals(ready.audioFileName, repeated.audioFileName)
            assertContentEquals(audio, Files.readAllBytes(store.resolveAudio(repeated)))
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]?.join()
            root.toFile().deleteRecursively()
        }
    }
}
