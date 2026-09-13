package ru.vrmn.kasha.android

import brain.domain.BrainData
import brain.model.*
import brain.studio.Intelligence
import brain.studio.Preferences
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AndroidStudioRepositoryTest {
    private val intelligence = object : Intelligence {
        override val simulated = false
        override suspend fun transcribe(file: String, language: String, example: String) = "Тестовая запись"
        override suspend fun title(text: String, language: String) = text.lineSequence().firstOrNull().orEmpty()
        override suspend fun tidy(text: String, language: String) = text
        override suspend fun rank(text: String, projects: List<Project>, language: String) = projects.associate { it.id to 0 }
    }

    @Test
    fun persistsDataPreferencesAndAudioAcrossRepositoryRestart() = runBlocking {
        val root = Files.createTempDirectory("kasha-android-store-").toFile()
        try {
            val first = AndroidStudioRepository(root, intelligence, "ru-RU")
            first.savePreferences(Preferences(autoRecord = false, theme = "dark", language = "ru"))
            val project = first.createProject(ProjectDraft("Проект", instruction = "Инструкция"))

            val pendingNote = first.newPendingFile().apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val noteCapture = first.acceptPending(pendingNote, 1.25, listOf(0.1f, 0.8f))
            first.updateCaptureDraft(noteCapture.id, CaptureDraftUpdate(text = "Первая строка\nТекст"))
            val note = first.distribute(noteCapture.id, DistributionRequest(project.id))

            val pendingTask = first.newPendingFile().apply { writeBytes(byteArrayOf(5, 6, 7, 8)) }
            val taskCapture = first.acceptPending(pendingTask, 2.0, listOf(0.2f))
            first.updateCaptureDraft(taskCapture.id, CaptureDraftUpdate(text = "Сделать задачу"))
            val task = first.distributeTask(
                taskCapture.id,
                TaskDistributionRequest(dueAt = 123_456L, reminderRepeat = ReminderRepeat.DAILY),
            )

            val second = AndroidStudioRepository(root, intelligence, "ru-RU")
            val snapshot = second.snapshot()
            val prefs = second.preferences()

            assertEquals("dark", prefs.theme)
            assertFalse(prefs.autoRecord)
            assertEquals(project.title, snapshot.projects.single().title)
            assertEquals("Первая строка", snapshot.notes.single { it.id == note.id }.title)
            assertEquals(task.text, snapshot.tasks.single { it.id == task.id }.text)
            assertTrue(second.audioFile(noteCapture.id).isFile)
            assertTrue(second.audioFile(taskCapture.id).isFile)
            assertTrue(snapshot.captures.all { it.audioFinalized })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun interruptedWorkingCaptureBecomesRecoverableFailureOnRestart() = runBlocking {
        val root = Files.createTempDirectory("kasha-android-recovery-").toFile()
        try {
            val first = AndroidStudioRepository(root, intelligence, "ru-RU")
            val pending = first.newPendingFile().apply { writeBytes(byteArrayOf(9, 10, 11)) }
            val capture = first.acceptPending(pending, 1.0, listOf(0.4f))

            val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
            val stateFile = File(root, "brain.json")
            val stored = json.decodeFromString<BrainData>(stateFile.readText())
            stateFile.writeText(
                json.encodeToString(
                    stored.copy(captures = stored.captures.map {
                        if (it.id == capture.id) it.copy(status = CaptureStatus.TRANSCRIBING) else it
                    }),
                ),
            )

            val second = AndroidStudioRepository(root, intelligence, "ru-RU")
            val recovered = second.snapshot().captures.single { it.id == capture.id }

            assertEquals(CaptureStatus.FAILED, recovered.status)
            assertTrue(recovered.message.contains("прервана", ignoreCase = true))
            assertTrue(second.audioFile(capture.id).isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun orphanFinalizedAudioReturnsToPendingAfterCrashBeforeStateCommit() {
        val root = Files.createTempDirectory("kasha-android-orphan-").toFile()
        try {
            val storage = AndroidStorage(root)
            val pending = storage.newPendingFile().apply { writeBytes(byteArrayOf(12, 13, 14, 15)) }
            val captureId = storage.pendingId(pending)
            val finalized = storage.acceptPending(pending, captureId)
            assertTrue(finalized.isFile)
            assertFalse(pending.exists())

            val repository = AndroidStudioRepository(root, intelligence, "ru-RU")
            val recovered = repository.pendingFiles().single()

            assertEquals(captureId, recovered.nameWithoutExtension)
            assertArrayEquals(byteArrayOf(12, 13, 14, 15), recovered.readBytes())
            assertTrue(repository.snapshotBlocking().captures.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun stagedDeleteIsRestoredWhenStateStillReferencesCapture() = runBlocking {
        val root = Files.createTempDirectory("kasha-android-delete-recovery-").toFile()
        try {
            val first = AndroidStudioRepository(root, intelligence, "ru-RU")
            val pending = first.newPendingFile().apply { writeBytes(byteArrayOf(21, 22, 23)) }
            val capture = first.acceptPending(pending, 1.0, listOf(0.5f))
            val storage = AndroidStorage(root)
            val staged = storage.stageDeleteCaptureAudio(capture.id)
            assertNotNull(staged)
            assertFalse(first.audioFileExists(capture.id))

            val second = AndroidStudioRepository(root, intelligence, "ru-RU")
            assertTrue(second.audioFile(capture.id).isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun AndroidStudioRepository.snapshotBlocking(): AppSnapshot = runBlocking { snapshot() }
    private fun AndroidStudioRepository.audioFileExists(captureId: String): Boolean = runBlocking {
        runCatching { audioFile(captureId).isFile }.getOrDefault(false)
    }
}
