package ru.vrmn.kasha.android

import android.content.Intent
import android.app.NotificationManager
import android.graphics.Bitmap
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import brain.ai.BuiltInAi
import brain.domain.BrainData
import brain.domain.PlaybackPhase
import brain.domain.RecorderPermission
import brain.domain.RecorderPhase
import brain.model.*
import brain.studio.Preferences
import brain.studio.Tab
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.sin

/** ТЗ 5/12/14: системные API и общий UI в изолированном эмуляторе; модели не устанавливаются. */
class AndroidPlatformAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val json = Json { encodeDefaults = true }
    private val checks = mutableListOf<String>()
    private val shots = mutableListOf<String>()
    private val output get() = File(context.filesDir, "platform-acceptance").apply { mkdirs() }

    @Test fun actualAdaptersAndSharedScreens() = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("kashaIsolated") == "true") {
            "Тест разрешён только на отдельной чистой установке с kashaIsolated=true"
        }
        val root = File(context.filesDir, "Kasha")
        check(!File(root, "brain.json").exists() && !File(root, "preferences.json").exists()) {
            "Не перезаписывать существующие пользовательские данные"
        }
        check(root.mkdirs() || root.isDirectory)
        val now = System.currentTimeMillis()
        val project = Project(id(), "Проект приёмки", instruction = "Тестовые данные", createdAt = now, updatedAt = now)
        val note = Note(id(), project.id, "Сохранённая заметка", "Сохранённая заметка\nИсходный текст.", now, now)
        val first = fileCapture(root, note.id, "Первая запись")
        val second = fileCapture(root, note.id, "Вторая запись")
        val broken = fileCapture(root, note.id, "Повреждённый источник", corrupt = true)
        val inbox = fileCapture(root, null, "Готовый текст\nПроверка сохранения в заметку.")
        val task = Task(id = id(), text = "Проверить напоминание", createdAt = now, updatedAt = now,
            dueAt = now + 86_400_000, reminderRepeat = ReminderRepeat.DAILY)
        val archived = Task(id = id(), text = "Завершённая задача", createdAt = now, updatedAt = now,
            dueAt = now, completedAt = now, nextReminderAt = Long.MAX_VALUE)
        File(root, "brain.json").writeText(json.encodeToString(BrainData(
            projects = listOf(project), notes = listOf(note), captures = listOf(first, second, broken, inbox),
            tasks = listOf(task, archived))))
        File(root, "preferences.json").writeText(json.encodeToString(Preferences(
            autoRecord = false, autoRoute = false, language = "ru", theme = "light", ai = BuiltInAi.androidSelection())))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val runtime = (context.applicationContext as KashaApplication).platform
        var passed = false
        val secretId = "platform-test-${id()}"
        try {
            await("инициализация общего UI") { runtime.permissions.foreground && runtime.state.initialized }
            screenshot("ready-light")
            runtime.repository.distribute(inbox.id, DistributionRequest(project.id, note.id))
            withContext(Dispatchers.Main.immediate) { runtime.state.refresh() }
            val source = File(root, first.audioFileName!!)
            val before = hash(source)

            for (rate in listOf(1.0, 1.5, 2.0)) {
                runtime.audio.playCapture(first.id, false, 0.0, rate)
                await("MediaPlayer начал $rate") { runtime.audio.playbackState().positionSeconds > 0.1 }
                assertEquals(PlaybackPhase.PLAYING, runtime.audio.playbackState().phase)
                await("естественное окончание $rate") { runtime.audio.playbackState().phase == PlaybackPhase.IDLE }
            }
            checks += "MediaPlayer: полный файл, естественное окончание и повтор на 1/1.5/2x"
            runtime.audio.playCapture(first.id, false, 0.0, 1.0)
            await("позиция плеера") { runtime.audio.playbackState().positionSeconds > 0.1 }
            runtime.audio.pause()
            val paused = runtime.audio.playbackState()
            delay(180)
            assertEquals(PlaybackPhase.PAUSED, runtime.audio.playbackState().phase)
            assertEquals(paused.positionSeconds, runtime.audio.playbackState().positionSeconds, 0.05)
            assertEquals(PlaybackPhase.PAUSED, runtime.audio.seekTo(1.0).phase)
            runtime.audio.resume()
            await("продолжение") { runtime.audio.playbackState().positionSeconds > 1.1 }
            assertEquals(PlaybackPhase.PLAYING, runtime.audio.seekTo(0.5).phase)
            assertNotNull(runCatching { runtime.recorder.start() }.exceptionOrNull())
            runtime.audio.playCapture(second.id, false, 0.0, 2.0)
            assertEquals(second.id, runtime.audio.playbackState().sourceId)
            assertNotNull(runCatching { runtime.audio.playCapture(broken.id, false, 0.0, 1.0) }.exceptionOrNull())
            assertEquals(PlaybackPhase.IDLE, runtime.audio.playbackState().phase)
            runtime.audio.playCapture(first.id, false, 0.0, 1.0)
            withContext(Dispatchers.Main.immediate) { runtime.audio.stop() }
            assertEquals(PlaybackPhase.IDLE, runtime.audio.playbackState().phase)
            assertEquals(before, hash(source))
            checks += "MediaPlayer: пауза/продолжение/перемотка, смена источника, ошибка/повтор, stop"

            assertEquals(RecorderPermission.GRANTED, runtime.recorder.permission())
            withContext(Dispatchers.Main.immediate) { runtime.state.navigate(Tab.HOME); runtime.state.startRecording() }
            await("MediaRecorder начал запись") { runtime.recorder.sessionState().phase == RecorderPhase.RECORDING }
            val recordingId = runtime.recorder.sessionState().activeSessionId!!
            delay(900)
            screenshot("recording-light")
            assertNotNull(runCatching { runtime.audio.playCapture(first.id, false, 0.0, 1.0) }.exceptionOrNull())
            withContext(Dispatchers.Main.immediate) { runtime.state.navigate(Tab.PROJECTS) }
            assertEquals(recordingId, runtime.recorder.sessionState().activeSessionId)
            withContext(Dispatchers.Main.immediate) { runtime.state.pauseRecording(); runtime.state.navigate(Tab.HOME) }
            assertEquals(RecorderPhase.PAUSED, runtime.recorder.sessionState().phase)
            screenshot("paused-light")
            withContext(Dispatchers.Main.immediate) { runtime.state.resumeRecording() }
            delay(900)
            val recorded = runtime.recorder.stopAndUpload()
            assertEquals(RecorderPhase.IDLE, runtime.recorder.sessionState().phase)
            val recording = File(root, recorded.audioFileName!!)
            assertTrue(recording.isFile && recording.length() > 0)
            assertTrue(recorded.durationSeconds > 0)
            // Проверяем именно записанный MediaRecorder файл системным MediaPlayer.
            runtime.audio.playCapture(recorded.id, false, 0.0, 1.0)
            await("записанный файл воспроизводится") { runtime.audio.playbackState().positionSeconds > 0.05 }
            withContext(Dispatchers.Main.immediate) { runtime.audio.stop() }
            checks += "MediaRecorder: запись/пауза/продолжение/навигация/stop; результат декодируется MediaPlayer"
            checks += "Запись и воспроизведение взаимно исключены в обоих направлениях"

            val secrets = AndroidSecretStore(context)
            assertNull(secrets.read(secretId))
            secrets.write(secretId, "test-only-not-a-provider-key")
            assertEquals("test-only-not-a-provider-key", AndroidSecretStore(context).read(secretId))
            secrets.write(secretId, "replacement-test-value")
            assertEquals("replacement-test-value", AndroidSecretStore(context).read(secretId))
            assertTrue(secrets.remove(secretId))
            assertFalse(secrets.remove(secretId))
            assertNull(AndroidSecretStore(context).read(secretId))
            checks += "Android Keystore: создание/чтение/перезапись/повторное открытие/удаление"

            val notifications = context.getSystemService(NotificationManager::class.java)
            assertTrue(runtime.reminders.available)
            runtime.reminders.notify(task)
            await("системное уведомление") { notifications.activeNotifications.count { it.tag == task.id } == 1 }
            runtime.reminders.notify(task)
            delay(150)
            assertEquals(1, notifications.activeNotifications.count { it.tag == task.id })
            val moved = runtime.repository.rescheduleTask(task.id, TaskScheduleUpdate(now + 172_800_000, ReminderRepeat.DAILY))
            await("старое уведомление отменено") { notifications.activeNotifications.none { it.tag == task.id } }
            runtime.reminders.notify(moved)
            await("новое уведомление") { notifications.activeNotifications.any { it.tag == task.id } }
            runtime.repository.completeTask(task.id)
            await("выполнение отменяет уведомление") { notifications.activeNotifications.none { it.tag == task.id } }
            runtime.reminders.notify(moved)
            assertEquals(0, notifications.activeNotifications.count { it.tag == task.id })
            runtime.repository.deleteTask(task.id)
            runtime.reminders.reconcile()
            assertEquals(0, notifications.activeNotifications.count { it.tag == task.id })
            checks += "NotificationManager: одно уведомление, перенос срока, выполнение, удаление, повторная сверка"

            // Это снимки настоящего общего Compose UI с тестовыми данными, не touch-E2E.
            for (theme in listOf("light", "dark")) {
                withContext(Dispatchers.Main.immediate) {
                    runtime.state.savePreferences(runtime.state.preferences.copy(theme = theme))
                    assertEquals(theme, runtime.state.preferences.theme)
                    runtime.state.refresh()
                    runtime.state.navigate(Tab.PROJECTS)
                }
                screenshot("projects-$theme")
                withContext(Dispatchers.Main.immediate) { runtime.state.selectedProjectId = project.id }
                screenshot("notes-$theme")
                withContext(Dispatchers.Main.immediate) { runtime.state.openNote(note.id) }
                screenshot("note-$theme")
                withContext(Dispatchers.Main.immediate) { runtime.state.navigate(Tab.TASKS); runtime.state.taskArchive = false }
                screenshot("tasks-empty-$theme")
                withContext(Dispatchers.Main.immediate) { runtime.state.taskArchive = true }
                screenshot("archive-$theme")
                withContext(Dispatchers.Main.immediate) { runtime.state.navigate(Tab.SETTINGS) }
                screenshot("settings-$theme")
                withContext(Dispatchers.Main.immediate) { runtime.state.languagePage = true }
                screenshot("language-$theme")
                withContext(Dispatchers.Main.immediate) { runtime.state.navigate(Tab.HOME) }
                screenshot("home-$theme")
            }
            checks += "Общие продуктовые экраны сняты в светлой и тёмной темах"
            passed = true
        } finally {
            if (!passed) runCatching { screenshot("failure") }
            withContext(Dispatchers.Main.immediate) { runtime.audio.stop() }
            runtime.recorder.sessionState().activeSessionId?.let { runCatching { runtime.recorder.cancelActive(it) } }
            runCatching { AndroidSecretStore(context).remove(secretId) }
            JSONObject().put("passed", passed).put("androidApi", Build.VERSION.SDK_INT)
                .put("checks", JSONArray(checks)).put("screenshots", JSONArray(shots))
                .put("physicalDevice", false).put("aiInferenceAcceptance", false)
                .let { File(output, "result.json").writeText(it.toString(2)) }
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private suspend fun await(label: String, ready: () -> Boolean) {
        withTimeout(12_000) { while (!ready()) delay(50) }
        checks += "Подтверждено: $label"
    }
    private suspend fun screenshot(name: String) {
        delay(600)
        instrumentation.waitForIdleSync()
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(output, "$name.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
        shots += "$name.png"
    }
    private fun fileCapture(root: File, noteId: String?, text: String, corrupt: Boolean = false): Capture {
        val captureId = id()
        val relative = "audio/$captureId/source.wav"
        val file = File(root, relative)
        check(file.parentFile!!.mkdirs())
        file.writeBytes(if (corrupt) "not an audio file".toByteArray() else wav())
        return Capture(id = captureId, createdAt = System.currentTimeMillis(), title = text.lineSequence().first(),
            preparedText = text, transcript = text, status = CaptureStatus.READY, audioFileName = relative,
            noteId = noteId, audioFinalized = true, durationSeconds = 3.0)
    }
    private fun wav(): ByteArray {
        val samples = 48_000
        val data = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(data.capacity() - 8).put("WAVEfmt ".toByteArray()).putInt(16)
        data.putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
        data.put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) { data.putShort((sin(it * 2 * Math.PI * 440 / 16_000) * 1500).toInt().toShort()) }
        return data.array()
    }
    private fun id() = UUID.randomUUID().toString()
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
}
