package ru.vrmn.kasha.android

import brain.domain.BrainData
import brain.model.*
import brain.studio.Intelligence
import brain.studio.Preferences
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*

/** ТЗ 8.6: реальные файлы; ошибка чтения не считается новой установкой. */
class AndroidStorageFailureTest {
    private inline fun assertFails(block: () -> Unit) {
        assertNotNull("Ожидалась ошибка операции", runCatching(block).exceptionOrNull())
    }
    private val json = Json { encodeDefaults = true }
    private val intelligence = object : Intelligence {
        override val simulated = false
        override suspend fun transcribe(file: String, language: String, example: String): String = error("unexpected AI")
        override suspend fun title(text: String, language: String): String = error("unexpected AI")
        override suspend fun tidy(text: String, language: String): String = error("unexpected AI")
        override suspend fun rank(text: String, projects: List<Project>, language: String): Map<String, Int> = error("unexpected AI")
    }
    private fun repository(root: File) = AndroidStudioRepository(root, intelligence, "ru-RU")
    private fun withRoot(test: (File) -> Unit) {
        val root = Files.createTempDirectory("kasha-storage-failure-").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }
    private fun files(root: File): Map<String, List<Byte>> = root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes().toList() }

    @Test fun corruptDatabaseDoesNotDeleteStagedAudioOrPublishDefaults() = withRoot { root -> runBlocking {
        val store = AndroidStorage(root)
        store.stateFile.writeText("{\"captures\":[")
        store.preferencesFile.writeText(json.encodeToString(Preferences(autoRecord = false)))
        val staged = File(store.audioDir, ".deleted-${UUID.randomUUID()}/saved.m4a")
        staged.parentFile.mkdirs(); staged.writeBytes(byteArrayOf(7, 8, 9))
        val before = files(root)
        val repository = repository(root)
        repeat(2) { assertFails { repository.snapshot() } }
        assertFails { repository.createProject(ProjectDraft("Не создавать")) }
        assertFails { repository.savePreferences(Preferences()) }
        assertFails { repository.newPendingFile() }
        assertFails { repository.pendingFiles() }
        assertEquals(before, files(root))
    } }

    @Test fun corruptPreferencesBlockAudioReconcileAndDatabaseMigration() = withRoot { root -> runBlocking {
        val store = AndroidStorage(root)
        val id = UUID.randomUUID().toString()
        val capture = Capture(id = id, createdAt = 1, status = CaptureStatus.TRANSCRIBING,
            audioFileName = "audio/$id/saved.m4a", audioFinalized = true)
        store.stateFile.writeText(json.encodeToString(BrainData(captures = listOf(capture))))
        store.preferencesFile.writeText("{broken")
        val staged = File(store.audioDir, ".deleted-$id/saved.m4a")
        staged.parentFile.mkdirs(); staged.writeBytes(byteArrayOf(1, 2, 3))
        val before = files(root)
        val repository = repository(root)
        assertFails { repository.snapshot() }
        assertFails { repository.preferences() }
        assertFails { repository.savePreferences(Preferences()) }
        assertEquals(before, files(root))
    } }

    @Test fun retryReadsRepairedDatabaseUsingTheSameRepository() = withRoot { root -> runBlocking {
        val store = AndroidStorage(root)
        store.stateFile.writeText("{broken")
        val repository = repository(root)
        assertFails { repository.snapshot() }
        val expected = Project("p", "Сохранённый проект")
        store.stateFile.writeText(json.encodeToString(BrainData(projects = listOf(expected))))
        assertEquals(listOf(expected), repository.snapshot().projects)
    } }

    @Test fun retryReadsRepairedPreferencesWithoutLosingDatabase() = withRoot { root -> runBlocking {
        val store = AndroidStorage(root)
        val expected = Project("p", "Проект")
        store.stateFile.writeText(json.encodeToString(BrainData(projects = listOf(expected))))
        store.preferencesFile.writeText("")
        val repository = repository(root)
        assertFails { repository.snapshot() }
        val preferences = Preferences(autoRecord = false, language = "de")
        store.preferencesFile.writeText(json.encodeToString(preferences))
        assertEquals(preferences, repository.preferences())
        assertEquals(listOf(expected), repository.snapshot().projects)
    } }

    @Test fun directoryInPlaceOfDatabaseIsNotMissingDatabase() = withRoot { root -> runBlocking {
        val store = AndroidStorage(root)
        store.stateFile.mkdir()
        File(store.stateFile, "keep.txt").writeText("Не удалять")
        val before = files(root)
        val repository = repository(root)
        assertFails { repository.snapshot() }
        assertEquals(before, files(root))
    } }

    @Test fun emptyDatabaseFileIsNotNewInstallation() = withRoot { root -> runBlocking {
        val store = AndroidStorage(root)
        store.stateFile.writeText("")
        assertFails { repository(root).snapshot() }
        assertEquals("", store.stateFile.readText())
    } }

    @Test fun newInstallationStillCreatesAndReopensAProject() = withRoot { root -> runBlocking {
        val repository = repository(root)
        assertTrue(repository.snapshot().projects.isEmpty())
        val project = repository.createProject(ProjectDraft("Новый проект"))
        assertEquals(listOf(project), repository(root).snapshot().projects)
    } }

    @Test fun failedPreferencesWriteDoesNotPublishNewValue() = withRoot { root -> runBlocking {
        val repository = repository(root)
        val previous = repository.preferences()
        val path = File(root, "preferences.json")
        path.mkdir(); File(path, "keep.txt").writeText("Не удалять")
        assertFails { repository.savePreferences(previous.copy(autoRecord = !previous.autoRecord)) }
        assertEquals(previous, repository.preferences())
        assertEquals("Не удалять", File(path, "keep.txt").readText())
    } }
}
