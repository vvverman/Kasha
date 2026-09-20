package brain.runtime

import brain.model.CaptureDraftUpdate
import brain.model.CaptureStatus
import brain.model.DistributionRequest
import brain.model.ProjectDraft
import brain.model.Preferences
import brain.model.RuntimeStatus
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StoreTest {
    @Test
    fun appendIsIdempotentAndOldTextStaysUntouched() = runBlocking {
        val dir = Files.createTempDirectory("kasha-test")
        try {
            val store = FileBrainStore(dir) { RuntimeStatus() }
            val project = store.createProject(ProjectDraft("Проект", instruction = "Тест"))
            val first = store.createCapture("a.webm", byteArrayOf(1, 2, 3))
            store.updateCapture(first.id) { it.copy(status = CaptureStatus.NEEDS_MODEL) }
            store.updateDraft(first.id, CaptureDraftUpdate("Первая", "Старый текст"))
            val note = store.distribute(first.id, DistributionRequest(project.id))

            val second = store.createCapture("b.webm", byteArrayOf(4, 5, 6))
            store.updateCapture(second.id) { it.copy(status = CaptureStatus.NEEDS_MODEL) }
            store.updateDraft(second.id, CaptureDraftUpdate("Вторая", "Новая мысль"))
            val appended = store.distribute(second.id, DistributionRequest(project.id, note.id))
            val repeated = store.distribute(second.id, DistributionRequest(project.id, note.id))

            assertEquals("Старый текст\n\nНовая мысль", appended.body)
            assertEquals(appended, repeated)
            assertTrue(appended.body.startsWith("Старый текст"))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun stateAndManualOrderSurviveReopen() = runBlocking {
        val dir = Files.createTempDirectory("kasha-reopen")
        try {
            val first = FileBrainStore(dir) { RuntimeStatus() }
            val a = first.createProject(ProjectDraft("Первый проект"))
            val b = first.createProject(ProjectDraft("Второй проект"))
            first.orderProjects(listOf(b.id, a.id))

            val second = FileBrainStore(dir) { RuntimeStatus() }
            val reopened = second.snapshot().projects
            assertEquals(setOf("Первый проект", "Второй проект"), reopened.map { it.title }.toSet())
            assertEquals(
                listOf("Второй проект", "Первый проект"),
                reopened.sortedBy { it.manualOrder }.map { it.title },
            )
        } finally { dir.toFile().deleteRecursively() }
    }


    @Test
    fun corruptPrimaryRecoversFromLastValidBackup() = runBlocking {
        val dir = Files.createTempDirectory("kasha-recover")
        try {
            val first = FileBrainStore(dir) { RuntimeStatus() }
            first.createProject(ProjectDraft("Сохранённый проект"))
            first.createProject(ProjectDraft("Последний проект"))

            Files.writeString(dir.resolve("brain.json"), "{broken")

            val recovered = FileBrainStore(dir) { RuntimeStatus() }
            val titles = recovered.snapshot().projects.map { it.title }

            assertEquals(listOf("Сохранённый проект"), titles)
            assertTrue(Files.exists(dir.resolve("brain.json.corrupt")))
            assertTrue(Files.readString(dir.resolve("brain.json")).contains("Сохранённый проект"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun corruptStateWithoutValidBackupFailsClosed() {
        val dir = Files.createTempDirectory("kasha-corrupt")
        try {
            Files.writeString(dir.resolve("brain.json"), "{broken")
            val error = assertFailsWith<IllegalStateException> {
                FileBrainStore(dir) { RuntimeStatus() }
            }

            assertTrue(error.message.orEmpty().contains("пустое состояние не создано"))
            assertEquals("{broken", Files.readString(dir.resolve("brain.json")))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun preferencesRecoverFromBackupInsteadOfResetting() = runBlocking {
        val dir = Files.createTempDirectory("kasha-prefs-recover")
        try {
            val store = PreferenceStore(dir)
            store.save(Preferences(language = "ru"))
            store.save(Preferences(language = "en"))
            Files.writeString(dir.resolve("preferences.json"), "{broken")

            val recovered = PreferenceStore(dir).read()

            assertEquals("ru", recovered.language)
            assertTrue(Files.exists(dir.resolve("preferences.json.corrupt")))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
