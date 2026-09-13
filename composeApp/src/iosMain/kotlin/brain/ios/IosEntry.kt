package brain.ios

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.ComposeUIViewController
import brain.studio.StudioApp
import brain.studio.StudioState

/**
 * Тонкая iOS composition root: весь продуктовый state/UI/Core общий,
 * здесь только связываются реальные iOS platform adapters.
 */
@Suppress("FunctionName")
fun MainViewController() = ComposeUIViewController {
    val scope = rememberCoroutineScope()
    val intelligence = remember { IosOnDeviceIntelligence() }
    val baseRepository = remember(intelligence) { IosRepository(intelligence) }
    val reminders = remember { IosReminder() }
    val repository = remember(baseRepository, reminders) { IosReminderRepository(baseRepository, reminders) }
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
        repository.syncReminders()
    }

    StudioApp(state)
}
