@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package brain.ios

import brain.domain.BrainData
import brain.model.ProjectDraft
import brain.studio.Preferences
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.*
import kotlin.test.*

/** ТЗ 8.6: настоящий Foundation storage в отдельном каталоге симулятора. */
class IosStorageFailureTest {
    private val json = Json { encodeDefaults = true }
    private fun repository(root: String) = IosRepository(IosOnDeviceIntelligence(),
        systemLanguage = "ru-RU", storageRoot = root)
    private fun withRoot(test: (String) -> Unit) {
        val root = IosPaths.directory(IosPaths.child(NSTemporaryDirectory(), "kasha-state-test-${NSUUID().UUIDString}"))
        try {
            IosPaths.write(IosPaths.child(root, "state.json"), json.encodeToString(BrainData()))
            IosPaths.write(IosPaths.child(root, "preferences.json"), json.encodeToString(Preferences(autoRecord = false)))
            test(root)
        } finally { NSFileManager.defaultManager.removeItemAtPath(root, error = null) }
    }

    @Test fun corruptDatabaseIsPreservedAndCanBeReadAgainAfterRepair() = withRoot { root -> runBlocking {
        val path = IosPaths.child(root, "state.json")
        IosPaths.write(path, "{broken")
        val repository = repository(root)
        assertFails { repository.snapshot() }
        assertFails { repository.createProject(ProjectDraft("Не создавать")) }
        assertFails { repository.savePreferences(Preferences()) }
        assertEquals("{broken", IosPaths.read(path))
        IosPaths.write(path, json.encodeToString(BrainData()))
        assertTrue(repository.snapshot().projects.isEmpty())
    } }

    @Test fun corruptPreferencesAreNotReplacedWithDefaults() = withRoot { root -> runBlocking {
        val path = IosPaths.child(root, "preferences.json")
        IosPaths.write(path, "")
        val repository = repository(root)
        val originalData = IosPaths.read(IosPaths.child(root, "state.json"))
        assertFails { repository.snapshot() }
        assertFails { repository.savePreferences(Preferences()) }
        assertEquals("", IosPaths.read(path))
        assertEquals(originalData, IosPaths.read(IosPaths.child(root, "state.json")))
        val expected = Preferences(autoRecord = false, language = "de")
        IosPaths.write(path, json.encodeToString(expected))
        assertEquals(expected, repository.preferences())
    } }

    @Test fun failedDataWriteDoesNotPublishChangesInMemory() = withRoot { root -> runBlocking {
        val repository = repository(root)
        val previous = repository.snapshot().projects
        val path = IosPaths.child(root, "state.json")
        IosPaths.remove(path); IosPaths.directory(path)
        IosPaths.write(IosPaths.child(path, "keep.txt"), "Не удалять")
        assertFails { repository.createProject(ProjectDraft("Не сохранён")) }
        assertEquals(previous, repository.snapshot().projects)
        assertEquals("Не удалять", IosPaths.read(IosPaths.child(path, "keep.txt")))
    } }

    @Test fun failedPreferencesWriteDoesNotPublishChangesInMemory() = withRoot { root -> runBlocking {
        val repository = repository(root)
        val previous = repository.preferences()
        val path = IosPaths.child(root, "preferences.json")
        IosPaths.remove(path); IosPaths.directory(path)
        IosPaths.write(IosPaths.child(path, "keep.txt"), "Не удалять")
        assertFails { repository.savePreferences(previous.copy(autoRecord = !previous.autoRecord)) }
        assertEquals(previous, repository.preferences())
    } }

    @Test fun unreadablePathIsAnErrorNotAMissingFile() = withRoot { root ->
        val directory = IosPaths.directory(IosPaths.child(root, "not-a-file"))
        assertFailsWith<IllegalStateException> { IosPaths.read(directory) }
        assertNull(IosPaths.read(IosPaths.child(root, "absent.json")))
    }
}
