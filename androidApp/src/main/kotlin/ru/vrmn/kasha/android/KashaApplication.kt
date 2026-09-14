package ru.vrmn.kasha.android

import android.app.Application
import brain.studio.StudioState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.util.Locale

/**
 * Process-scoped Android composition root. Он переживает пересоздание Activity и не
 * создаёт второй recorder/repository при повороте экрана; после убийства процесса
 * состояние восстанавливается из app-private storage.
 */
class KashaApplication : Application() {
    internal val platform by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidPlatformRuntime(this)
    }
}

internal class AndroidPlatformRuntime(application: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val systemLanguage = Locale.getDefault().toLanguageTag()

    val repository = AndroidStudioRepository(
        root = File(application.filesDir, "Kasha"),
        intelligence = AndroidUnavailableIntelligence,
        systemLanguage = systemLanguage,
    )
    val recorder = AndroidRecorder(application, repository, scope)
    val audio = AndroidUnavailableAudio
    val state = StudioState(
        repository = repository,
        recorder = recorder,
        audio = audio,
        systemLanguage = systemLanguage,
    )
}
