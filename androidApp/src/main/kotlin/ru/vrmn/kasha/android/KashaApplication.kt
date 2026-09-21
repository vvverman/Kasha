package ru.vrmn.kasha.android

import android.app.Application
import brain.model.RuntimeStatus
import brain.studio.Preferences
import brain.studio.StudioState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/** Один runtime на процесс; Activity не владеет записью или воспроизведением. */
class KashaApplication : Application() {
    internal val platform by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidPlatformRuntime(this)
    }
}

internal class AndroidPlatformRuntime(application: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val systemLanguage = Locale.getDefault().toLanguageTag()
    private lateinit var data: AndroidStudioRepository
    private val cloud = androidCloudAi(application)
    private val intelligence = AndroidIntelligence(application, cloud) { data.preferences() }
    init {
        data = AndroidStudioRepository(
            root = File(application.filesDir, "Kasha"),
            intelligence = intelligence,
            systemLanguage = systemLanguage,
            runtimeStatus = { RuntimeStatus(
                whisperConfigured = intelligence.speechAvailable(),
                llmConfigured = false,
                localOnly = true,
                simulated = false,
                message = "Android · системное распознавание требует поддерживаемого файлового ввода и установленного языка",
            ) },
            defaultPreferences = Preferences(),
        )
    }
    val permissions = AndroidPermissions(application)
    private val nativeRecorder = AndroidRecorder(application, data, scope)
    val audio = AndroidAudio(application, data, scope,
        recorderPhase = { nativeRecorder.sessionState().phase },
        foreground = { permissions.foreground },
    )
    val recorder = AndroidPermissionRecorder(nativeRecorder, permissions, data, audio)
    val reminders = AndroidReminders(application, data, permissions)
    val repository = AndroidSystemRepository(data, reminders, permissions, intelligence, cloud)
    val state = StudioState(repository, recorder, audio, systemLanguage, reminders)

    fun onForeground() {
        scope.launch {
            try {
                reminders.reconcile()
                if (state.initialized) state.refresh()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state.error = "actionFailed" }
        }
        // launch/start/resume намеренно отсутствуют: ими управляет общий StudioState.
    }

    fun onBackground() {
        scope.launch { state.autosave() }
        // Переключение вкладок, поворот и background не закрывают системную аудиосессию.
    }
}
