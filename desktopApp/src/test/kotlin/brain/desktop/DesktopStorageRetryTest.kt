package brain.desktop

import brain.application.KashaApplication
import brain.model.ProjectDraft
import brain.model.RuntimeStatus
import brain.runtime.FileBrainStore
import brain.runtime.PreferenceStore
import brain.studio.Preferences
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Настоящие DesktopServices и команда запуска Core; без микрофона и AI-вызовов. */
class DesktopStorageRetryTest {
    private fun fixture(test: suspend (Path, Path) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("kasha-desktop-storage-")
        val root = Files.createDirectory(directory.resolve("data"))
        val resources = Files.createDirectories(directory.resolve("resources/bin")).parent
        Files.writeString(resources.resolve("demo-mode.txt"), "storage test")
        // Исполняемый файл здесь не вызывается: проверяем только чтение данных и Retry.
        val executable = resources.resolve("bin").resolve(DesktopPlatform.executableCandidates("ffmpeg").first())
        Files.writeString(executable, "#!/bin/sh\nexit 1\n")
        if (DesktopPlatform.os != DesktopOs.WINDOWS) check(executable.toFile().setExecutable(true))
        try { test(root, resources) } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun corruptStateReachesCoreAndRetryUsesTheSameDesktopServices() = fixture { root, resources ->
        val store = FileBrainStore(root, runtimeStatus = { RuntimeStatus() })
        val project = store.createProject(ProjectDraft("Сохранённый проект"))
        PreferenceStore(root).save(Preferences(autoRecord = false, language = "ru"))
        val file = root.resolve("brain.json")
        val original = Files.readAllBytes(file)
        Files.writeString(file, "{broken")
        DesktopServices(root, resources).use { services ->
            val app = KashaApplication(services.repository, services.recorder, services.audio)
            repeat(2) {
                assertFails { app.launch("ru-RU") { "Не создавать" } }
                assertFalse(app.state.value.initialized)
                assertEquals("{broken", Files.readString(file))
            }
            Files.write(file, original)
            repeat(2) {
                app.launch("ru-RU") { "Не создавать" }
                assertTrue(app.state.value.initialized)
                assertEquals(listOf(project), app.state.value.snapshot.projects)
                assertContentEquals(original, Files.readAllBytes(file))
            }
        }
    }

    @Test fun corruptPreferencesCanBeRepairedWithoutRecreatingTheShell() = fixture { root, resources ->
        val store = FileBrainStore(root, runtimeStatus = { RuntimeStatus() })
        val project = store.createProject(ProjectDraft("Сохранённый проект"))
        val expected = Preferences(autoRecord = false, language = "de")
        PreferenceStore(root).save(expected)
        val file = root.resolve("preferences.json")
        val original = Files.readAllBytes(file)
        val data = Files.readAllBytes(root.resolve("brain.json"))
        Files.writeString(file, "{broken")
        DesktopServices(root, resources).use { services ->
            val app = KashaApplication(services.repository, services.recorder, services.audio)
            repeat(2) {
                assertFails { app.launch("ru-RU") { "Не создавать" } }
                assertFalse(app.state.value.initialized)
                assertEquals("{broken", Files.readString(file))
                assertContentEquals(data, Files.readAllBytes(root.resolve("brain.json")))
            }
            Files.write(file, original)
            app.launch("ru-RU") { "Не создавать" }
            assertTrue(app.state.value.initialized)
            assertEquals(expected, app.state.value.preferences)
            assertEquals(listOf(project), app.state.value.snapshot.projects)
        }
    }
}
