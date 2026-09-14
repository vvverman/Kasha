package brain.ios

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.ComposeUIViewController
import brain.studio.StudioApp
import brain.studio.StudioState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Тонкая iOS composition root: весь продуктовый state/UI/Core общий,
 * здесь только связываются реальные iOS platform adapters.
 */
@Suppress("FunctionName")
fun MainViewController() = ComposeUIViewController {
    val scope = rememberCoroutineScope()
    val localIntelligence = remember { IosOnDeviceIntelligence() }
    val cloudAi = remember { IosCloudAiGateway() }
    val baseRepository = remember(localIntelligence, cloudAi) { IosRepository(localIntelligence, cloudAi) }
    val reminders = remember { IosReminder() }
    val deviceCapabilities = remember { IosDeviceCapabilities() }
    val repository = remember(baseRepository, reminders, deviceCapabilities, cloudAi) {
        IosReminderRepository(baseRepository, reminders, deviceCapabilities, cloudAi)
    }
    val recorder = remember(baseRepository, scope) { IosRecorder(baseRepository, scope) }
    val audio = remember(baseRepository) { IosAudio(baseRepository) }
    val state = remember(repository, recorder, audio, reminders) {
        StudioState(
            repository = repository,
            recorder = recorder,
            audio = audio,
            reminders = reminders,
            systemLanguage = "ru-RU",
        )
    }

    LaunchedEffect(repository) {
        repository.reconcileReminders()
    }

    DisposableEffect(repository, scope) {
        val stop = IosReminderLifecycleBridge.observe {
            scope.launch {
                try {
                    repository.reconcileReminders()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Notification resync не должен ломать приложение.
                }
            }
        }
        onDispose { stop() }
    }

    StudioApp(state)
}
