package ru.vrmn.kasha.android

import brain.model.*
import brain.studio.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class AndroidAiDataSafetyTest {
    private class Engine : Intelligence {
        override val simulated = false
        var speech: suspend () -> String = { SOURCE }
        var process: suspend () -> String = { SOURCE }
        override suspend fun transcribe(file: String, language: String, example: String) = speech()
        override suspend fun title(text: String, language: String) = text
        override suspend fun tidy(text: String, language: String) = process()
        override suspend fun rank(text: String, projects: List<Project>, language: String) = projects.associate { it.id to 0 }
    }
    private suspend fun fixture(action: suspend (AndroidStudioRepository, Engine, Capture) -> Unit) {
        val root = Files.createTempDirectory("kasha-ai-safety-").toFile()
        try {
            val engine = Engine()
            val repository = AndroidStudioRepository(root, engine, "ru")
            val pending = repository.newPendingFile().apply { writeBytes(AUDIO) }
            val capture = repository.acceptPending(pending, 1.0, listOf(0.1f))
            val ready = repository.reprocess(capture.id)
            assertEquals(CaptureStatus.READY, ready.status)
            action(repository, engine, ready)
        } finally { root.deleteRecursively() }
    }
    @Test fun lateTidyCannotOverwriteNewManualEdit() = runBlocking {
        fixture { repository, engine, capture ->
            val started = CompletableDeferred<Unit>()
            val response = CompletableDeferred<String>()
            engine.process = { started.complete(Unit); response.await() }
            val processing = async { repository.tidy(capture.id) }
            started.await()
            repository.updateCaptureDraft(capture.id, CaptureDraftUpdate(text = "Моя новая редакция — 42"))
            response.complete("$SOURCE!")
            assertEquals("Моя новая редакция — 42", processing.await().textToSave)
            assertEquals("Моя новая редакция — 42", repository.snapshot().captures.single().textToSave)
            assertArrayEquals(AUDIO, repository.audioFile(capture.id).readBytes())
        }
    }
    @Test fun cancelledSpeechPreservesSourceAndManualText() = runBlocking {
        fixture { repository, engine, capture ->
            repository.updateCaptureDraft(capture.id, CaptureDraftUpdate(text = "Ручной текст"))
            val started = CompletableDeferred<Unit>()
            engine.speech = { started.complete(Unit); awaitCancellation() }
            val processing = launch { repository.reprocess(capture.id) }
            started.await(); processing.cancelAndJoin()
            val current = repository.snapshot().captures.single()
            assertEquals(CaptureStatus.FAILED, current.status)
            assertEquals("Ручной текст", current.textToSave)
            assertEquals(SOURCE, current.transcript)
            assertArrayEquals(AUDIO, repository.audioFile(capture.id).readBytes())
        }
    }
    @Test fun missingModelStillAllowsSavingExistingTextManually() = runBlocking {
        fixture { repository, engine, capture ->
            engine.speech = { error("androidAiNotConfigured") }
            val result = repository.reprocess(capture.id)
            assertEquals(CaptureStatus.NEEDS_MODEL, result.status)
            assertEquals(SOURCE, result.textToSave)
            val project = repository.createProject(ProjectDraft("Проект"))
            assertEquals(SOURCE, repository.distribute(capture.id, DistributionRequest(project.id)).body)
            assertArrayEquals(AUDIO, repository.audioFile(capture.id).readBytes())
        }
    }
    @Test fun invalidAiEditLeavesStoredTextUntouched() = runBlocking {
        fixture { repository, engine, capture ->
            engine.process = { "Ирина меняла 9000 пунктов." }
            try { repository.tidy(capture.id); fail("Invalid model edit accepted") }
            catch (_: IllegalArgumentException) { }
            assertEquals(capture, repository.snapshot().captures.single())
            assertArrayEquals(AUDIO, repository.audioFile(capture.id).readBytes())
        }
    }
    companion object {
        private const val SOURCE = "Ирина не меняла 1200 пунктов."
        private val AUDIO = byteArrayOf(1, 2, 3, 4, 5)
    }
}
