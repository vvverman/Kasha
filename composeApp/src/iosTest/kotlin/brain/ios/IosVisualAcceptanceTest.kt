@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package brain.ios

import androidx.compose.ui.window.ComposeUIViewController
import brain.ai.BuiltInAi
import brain.domain.AudioGateway
import brain.domain.BrainData
import brain.domain.RecorderPermission
import brain.domain.RecorderPhase
import brain.domain.RecorderSessionGateway
import brain.domain.RecorderSessionState
import brain.model.*
import brain.studio.*
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.*
import platform.UIKit.*
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ТЗ 14: iOS visual acceptance рендерит тот же shared StudioApp через ComposeUIViewController.
 * Persistence — настоящий IosRepository в отдельном временном каталоге.
 * Recorder здесь управляемый только для кадров recording/paused; системный recorder/lifecycle
 * проверяется отдельными iOS native tests.
 */
class IosVisualAcceptanceTest {
    private val json = Json { encodeDefaults = true; prettyPrint = false }
    private val now = 1_800_000_000_000L

    private class VisualRecorder(
        var actual: RecorderSessionState = RecorderSessionState(),
    ) : RecorderSessionGateway {
        override suspend fun permission() = RecorderPermission.GRANTED
        override fun sessionState() = actual
        override suspend fun pendingRecordings() = emptyList<brain.domain.PendingRecording>()
        override fun level() = if (actual.phase == RecorderPhase.RECORDING) 0.55f else 0f
        override suspend fun start() {
            actual = RecorderSessionState(RecorderPhase.RECORDING, "visual-session")
        }
        override suspend fun pause() {
            actual = RecorderSessionState(RecorderPhase.PAUSED, actual.activeSessionId ?: "visual-session")
        }
        override suspend fun resume() {
            actual = RecorderSessionState(RecorderPhase.RECORDING, actual.activeSessionId ?: "visual-session")
        }
        override suspend fun stopAndUpload() = Capture("visual-finished", 1L)
        override suspend fun cancelActive(sessionId: String) {
            require(sessionId == actual.activeSessionId)
            actual = RecorderSessionState()
        }
        override suspend fun recoverPending(pendingId: String) = error("unused")
        override suspend fun discardPending(pendingId: String) = Unit
    }

    private class VisualAudio : AudioGateway {
        override suspend fun playCapture(captureId: String, compact: Boolean, fromSeconds: Double, rate: Double) = Unit
        override fun stop() = Unit
    }

    private data class Harness(
        val root: String,
        val state: StudioState,
        val recorder: VisualRecorder,
        val controller: UIViewController,
        val window: UIWindow,
    )

    @Test
    fun sharedIosUiProducesCanonicalVisualMatrix() {
        val evidence = getenv("KASHA_IOS_VISUAL_EVIDENCE")?.toKString()
            ?: error("Required integration fixture: KASHA_IOS_VISUAL_EVIDENCE")
        IosPaths.directory(evidence)
        val screenshots = mutableListOf<String>()
        val hosts = mutableListOf<Harness>()
        val normal = Size(390.0, 844.0)
        val narrow = Size(320.0, 568.0)

        for (theme in listOf("light", "dark")) {
            // Result host: capture is present before launch; no visual test mutates persistence.
            val result = harness(resultData(), theme, normal)
            hosts += result
            capture(result, evidence, screenshots, "$theme-result", normal)
            capture(result, evidence, screenshots, "$theme-narrow-result", narrow)

            // Idle/content host drives only presentation state: navigation, selection,
            // archive/language flags and local error overlay.
            val idle = harness(baseData(), theme, normal)
            hosts += idle
            capture(idle, evidence, screenshots, "$theme-home", normal)
            capture(idle, evidence, screenshots, "$theme-narrow-home", narrow)

            idle.state.navigate(Tab.PROJECTS)
            capture(idle, evidence, screenshots, "$theme-projects", normal)
            idle.state.selectedProjectId = "project"
            capture(idle, evidence, screenshots, "$theme-notes", normal)
            idle.state.openNote("note")
            capture(idle, evidence, screenshots, "$theme-note", normal)

            idle.state.navigate(Tab.TASKS)
            idle.state.taskArchive = false
            capture(idle, evidence, screenshots, "$theme-tasks", normal)
            idle.state.taskArchive = true
            capture(idle, evidence, screenshots, "$theme-archive", normal)

            idle.state.navigate(Tab.SETTINGS)
            capture(idle, evidence, screenshots, "$theme-settings", normal)
            capture(idle, evidence, screenshots, "$theme-narrow-settings", narrow)
            idle.state.languagePage = true
            capture(idle, evidence, screenshots, "$theme-language", normal)

            idle.state.error = "loadFailed"
            capture(idle, evidence, screenshots, "$theme-error", normal)

            // Recording and paused are supplied by the typed recorder contract before
            // launch. Native recorder commands/lifecycle are already covered separately.
            val recording = harness(
                baseData(), theme, normal,
                RecorderSessionState(RecorderPhase.RECORDING, "visual-session"),
            )
            hosts += recording
            capture(recording, evidence, screenshots, "$theme-recording", normal)
            capture(recording, evidence, screenshots, "$theme-narrow-recording", narrow)

            val paused = harness(
                baseData(), theme, normal,
                RecorderSessionState(RecorderPhase.PAUSED, "visual-session"),
            )
            hosts += paused
            capture(paused, evidence, screenshots, "$theme-paused", normal)
        }

        assertTrue(hosts.size == 8, "Expected 8 retained iOS visual hosts")
        assertTrue(screenshots.size == 32, "Expected 32 iOS visual screenshots, got ${screenshots.size}")
        val report = buildJsonObject {
            put("passed", true)
            put("sharedComposeUi", true)
            put("realIosRepository", true)
            put("systemAudioClaim", false)
            put("normalSize", "390x844")
            put("narrowSize", "320x568")
            putJsonArray("themes") {
                add(kotlinx.serialization.json.JsonPrimitive("light"))
                add(kotlinx.serialization.json.JsonPrimitive("dark"))
            }
            putJsonArray("screenshots") {
                screenshots.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
            }
        }
        IosPaths.write(IosPaths.child(evidence, "result.json"), report.toString())
    }

    private data class Size(val width: Double, val height: Double)

    private fun capture(
        harness: Harness,
        evidence: String,
        screenshots: MutableList<String>,
        name: String,
        size: Size,
    ) {
        resize(harness, size)
        pump(0.18)
        val path = IosPaths.child(evidence, "$name.png")
        screenshot(harness.controller.view, size, path)
        assertTrue(IosPaths.exists(path), "Screenshot was not written: $name")
        screenshots += "$name.png"
    }

    private fun resize(harness: Harness, size: Size) {
        val frame = CGRectMake(0.0, 0.0, size.width, size.height)
        harness.window.setFrame(frame)
        harness.controller.view.setFrame(frame)
        harness.controller.view.setNeedsLayout()
        harness.controller.view.layoutIfNeeded()
    }

    private fun harness(
        data: BrainData,
        theme: String,
        size: Size,
        recorderState: RecorderSessionState = RecorderSessionState(),
    ): Harness {
        val root = IosPaths.directory(IosPaths.child(NSTemporaryDirectory(), "visual-${NSUUID().UUIDString}"))
        IosPaths.write(IosPaths.child(root, "state.json"), json.encodeToString(data))
        IosPaths.write(IosPaths.child(root, "preferences.json"), json.encodeToString(
            Preferences(autoRecord = false, autoRoute = false, language = "ru", theme = theme, ai = BuiltInAi.appleSelection())
        ))
        val repository = IosRepository(
            localIntelligence = IosOnDeviceIntelligence(),
            systemLanguage = "ru-RU",
            storageRoot = root,
        )
        val recorder = VisualRecorder(recorderState)
        val state = StudioState(repository, recorder, VisualAudio(), systemLanguage = "ru-RU")
        runBlocking { state.launch() }

        val controller = ComposeUIViewController(
            configure = { enforceStrictPlistSanityCheck = false },
        ) { StudioApp(state) }
        val frame = CGRectMake(0.0, 0.0, size.width, size.height)
        controller.view.setFrame(frame)
        val window = UIWindow(frame = frame)
        window.rootViewController = controller
        window.makeKeyAndVisible()
        controller.view.setNeedsLayout()
        controller.view.layoutIfNeeded()
        pump(0.35)
        return Harness(root, state, recorder, controller, window)
    }

    private fun screenshot(view: UIView, size: Size, path: String) {
        view.setNeedsLayout()
        view.layoutIfNeeded()
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(size.width, size.height), false, 1.0)
        try {
            val context = checkNotNull(UIGraphicsGetCurrentContext()) {
                "UIKit did not create screenshot graphics context"
            }
            // Native unit-test hosts are not regular foreground app scenes, so
            // drawViewHierarchyInRect may legitimately return false. CALayer
            // rendering captures the same shared Compose view without requiring
            // a scene-backed snapshot API.
            view.layer.renderInContext(context)
            val image = checkNotNull(UIGraphicsGetImageFromCurrentImageContext()) {
                "UIKit did not return screenshot image"
            }
            val png = checkNotNull(UIImagePNGRepresentation(image)) {
                "UIKit did not encode screenshot PNG"
            }
            check(png.writeToFile(path, atomically = true)) { "Could not write screenshot PNG" }
        } finally {
            UIGraphicsEndImageContext()
        }
    }

    private fun pump(seconds: Double) {
        NSRunLoop.currentRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(seconds))
    }

    private fun baseData(): BrainData {
        val project = Project(
            id = "project",
            title = "Длинное название проекта для проверки переноса",
            instruction = "Рабочие мысли и встречи",
            createdAt = now,
            updatedAt = now,
            pinned = true,
        )
        val note = Note(
            id = "note",
            projectId = project.id,
            title = "Сохранённая заметка с длинным названием",
            body = "Сохранённая заметка с длинным названием\nТекст заметки и исходное аудио доступны в общем интерфейсе.",
            createdAt = now + 1,
            updatedAt = now + 1,
            pinned = true,
        )
        val source = Capture(
            id = "source",
            createdAt = now + 2,
            title = "Исходная запись",
            transcript = "Исходная запись",
            preparedText = "Исходная запись",
            status = CaptureStatus.READY,
            noteId = note.id,
            audioFileName = "audio/source/saved.m4a",
            audioFinalized = true,
            durationSeconds = 183.0,
            waveform = List(80) { ((it % 9) + 1) / 10f },
        )
        val active = Task(
            id = "task-active",
            text = "Активная задача с длинным названием\nПроверить перенос строки и срок.",
            createdAt = now + 3,
            updatedAt = now + 3,
            dueAt = now + 86_400_000,
            reminderRepeat = ReminderRepeat.DAILY,
        )
        val archived = Task(
            id = "task-done",
            text = "Выполненная задача",
            createdAt = now + 4,
            updatedAt = now + 4,
            dueAt = now,
            completedAt = now + 5,
        )
        return BrainData(
            projects = listOf(project),
            notes = listOf(note),
            captures = listOf(source),
            tasks = listOf(active, archived),
        )
    }

    private fun resultData(): BrainData {
        val base = baseData()
        val inbox = Capture(
            id = "result",
            createdAt = now + 6,
            title = "Готовый текст записи",
            transcript = "Готовый текст записи\nВторая строка для проверки редактора.",
            preparedText = "Готовый текст записи\nВторая строка для проверки редактора.",
            status = CaptureStatus.READY,
            audioFileName = "audio/result/saved.m4a",
            audioFinalized = true,
            durationSeconds = 42.0,
            waveform = List(80) { ((it % 7) + 2) / 10f },
        )
        return base.copy(captures = base.captures + inbox)
    }
}
