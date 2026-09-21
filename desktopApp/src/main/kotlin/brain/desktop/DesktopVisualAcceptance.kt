package brain.desktop

import androidx.compose.runtime.withFrameNanos
import brain.model.*
import brain.studio.StudioState
import brain.studio.Tab
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Window
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.sin

/** Проверка существующего --ui-smoke: настоящие окна и общий UI, только изолированные данные. */
internal suspend fun desktopVisualAcceptance(window: Window, state: StudioState, services: DesktopServices, output: Path) {
    val original = services.store.snapshot()
    check(original.notes.isEmpty() && original.tasks.isEmpty() && original.captures.isEmpty()) {
        "Визуальная проверка не должна менять существующие данные"
    }
    check(services.root.toAbsolutePath().normalize().startsWith(output.toAbsolutePath().normalize())) {
        "KASHA_HOME для матрицы должен находиться внутри каталога результатов"
    }
    val project = services.store.createProject(ProjectDraft("Длинное название проекта для проверки переноса текста", instruction = "Изолированные тестовые данные"))
    suspend fun source(text: String): Capture {
        val capture = services.store.createCapture("visual.wav", visualWav())
        return services.store.updateCapture(capture.id) {
            it.copy(status = CaptureStatus.READY, preparedText = text, transcript = text,
                audioFinalized = true, durationSeconds = 3.0, waveform = List(80) { index -> (index % 9) / 10f })
        }
    }
    val note = services.store.distribute(source("Сохранённая заметка\nТекст и исходное аудио доступны в общем интерфейсе.").id,
        DistributionRequest(project.id))
    services.store.pinProject(project.id, true)
    services.store.pinNote(note.id, true)
    val due = System.currentTimeMillis() + 86_400_000L
    services.store.distributeTask(source("Активная задача с длинным названием\nПроверка переноса и доступности срока.").id,
        TaskDistributionRequest(dueAt = due, reminderRepeat = ReminderRepeat.DAILY))
    val archived = services.store.distributeTask(source("Выполненная задача").id,
        TaskDistributionRequest(dueAt = due, reminderRepeat = ReminderRepeat.HOURLY))
    services.store.completeTask(archived.id)
    state.refresh()
    Files.createDirectories(output)
    val shots = mutableListOf<String>()
    val host = window.graphicsConfiguration.bounds
    val wide = (host.width - 40).coerceAtMost(1600)
    val height = (host.height - 80).coerceAtMost(900)
    check(wide >= 900 && height >= 680) { "Недостаточный размер тестового дисплея: $host" }

    suspend fun screenshot(name: String) {
        // Ждём два кадра Compose после команды, а не фотографируем старый layout.
        withFrameNanos { }
        withFrameNanos { }
        delay(200)
        check(window.isShowing)
        val rectangle = Rectangle(window.locationOnScreen, window.size)
        check(host.contains(rectangle)) { "Окно выходит за тестовый экран: $rectangle / $host" }
        val image = Robot(window.graphicsConfiguration.device).createScreenCapture(rectangle)
        check(ImageIO.write(image, "png", output.resolve("$name.png").toFile()))
        shots += "$name:${window.width}x${window.height}"
    }

    for (width in listOf(430, wide).distinct()) {
        window.setSize(width, height)
        window.setLocation(host.x + (host.width - width) / 2, host.y + (host.height - height) / 2)
        for (theme in listOf("light", "dark")) {
            check(state.savePreferences(state.preferences.copy(theme = theme, autoRecord = false, autoRoute = false)))
            val prefix = "$theme-$width"
            state.navigate(Tab.HOME)
            screenshot("$prefix-home-empty")
            state.navigate(Tab.PROJECTS)
            screenshot("$prefix-projects")
            state.selectedProjectId = project.id
            screenshot("$prefix-notes")
            state.openNote(note.id)
            screenshot("$prefix-note")
            state.navigate(Tab.TASKS)
            state.taskArchive = false
            screenshot("$prefix-tasks")
            state.taskArchive = true
            screenshot("$prefix-archive")
            state.navigate(Tab.SETTINGS)
            screenshot("$prefix-settings")
            state.languagePage = true
            screenshot("$prefix-language")
            state.navigate(Tab.HOME)
            val ready = source("Готовый текст записи\nПроверка редактора и действий сохранения.")
            state.refresh()
            screenshot("$prefix-ready-capture")
            // Ошибка файлового источника через существующую команду, не ручной флаг UI.
            state.requestListen("missing-visual-source")
            check(state.error != null)
            screenshot("$prefix-recoverable-error")
            state.error = null
            services.store.discard(ready.id)
            state.refresh()
        }
    }
    val report = mapOf(
        "scope" to "actual-desktop-window; test-data; no AI inference or microphone claim",
        "os" to System.getProperty("os.name"),
        "host" to "${host.width}x${host.height}",
        "screenshots" to shots.joinToString("\n"),
        "passed" to "true",
    )
    Files.writeString(output.resolve("visual-result.json"), Json.encodeToString(report))
}

private fun visualWav(): ByteArray {
    val rate = 16000
    val samples = rate * 3
    val data = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
    data.put("RIFF".toByteArray()).putInt(data.capacity() - 8).put("WAVEfmt ".toByteArray()).putInt(16)
    data.putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
    data.put("data".toByteArray()).putInt(samples * 2)
    repeat(samples) { data.putShort((sin(it * 2 * Math.PI * 440 / rate) * 1000).toInt().toShort()) }
    return data.array()
}
