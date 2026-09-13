package brain.studio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*

@Composable
fun StudioApp(state: StudioState) {
    val scope = rememberCoroutineScope()
    DisposableEffect(state, scope) {
        state.attachActionScope(scope)
        onDispose { state.detachActionScope(scope) }
    }
    LaunchedEffect(Unit) { state.launch() }
    LaunchedEffect(Unit) { state.poll() }
    LaunchedEffect(state.editRevision) {
        if (state.editRevision > 0) { delay(400); state.autosave() }
    }

    StudioTheme(state.preferences.theme) {
        val colors = MaterialTheme.colorScheme
        Surface(Modifier.fillMaxSize(), color = colors.background, contentColor = colors.onSurface) {
            if (!state.initialized) {
                KashaSplash()
                return@Surface
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 430.dp).fillMaxWidth().fillMaxHeight().padding(horizontal = 20.dp)) {
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        KashaBrandSlot(Modifier.width(22.dp).height(27.dp), solid = true)
                        Spacer(Modifier.width(9.dp))
                        Text("Kasha", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, letterSpacing = (-.3).sp)
                        if (state.repository.simulated) Text(state.tr("demoBadge"), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            state.error != null -> Notice(state.tr(state.error!!), state.tr("ok")) { state.error = null }
                            state.confirmDelete -> Confirmation(state.tr("deleteTitle"), state.tr("deleteBody"), state.tr("delete"), state.tr("cancel"), { scope.launch { state.discard() } }, { state.confirmDelete = false })
                            state.confirmListenId != null -> Confirmation(state.tr("confirmListen"), state.tr("preserveRecording"), state.tr("stopAndPlay"), state.tr("cancel"), { scope.launch { state.confirmStopAndListen() } }, { state.confirmListenId = null })
                            state.taskScheduleTarget != null -> TaskScheduleScreen(state)
                            state.editingProjectId != null -> ProjectEditor(state)
                            state.choosingProject -> DestinationScreen(state)
                            else -> when (state.tab) {
                                Tab.HOME -> HomeScreen(state)
                                Tab.PROJECTS -> ProjectsScreen(state)
                                Tab.TASKS -> TasksScreen(state)
                                Tab.SETTINGS -> SettingsScreen(state)
                            }
                        }
                    }
                    GlobalPlayer(state)
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(
                            Triple(Tab.HOME, "home", Glyph.HOME),
                            Triple(Tab.PROJECTS, "projects", Glyph.FOLDER),
                            Triple(Tab.TASKS, "tasks", Glyph.TASKS),
                            Triple(Tab.SETTINGS, "settings", Glyph.SETTINGS),
                        ).forEach { (tab, key, icon) ->
                            val label = if (key == "tasks") KashaCopy.text(state.language, key) ?: key else state.tr(key)
                            KashaNavigationItem(label, icon, state.tab == tab, { state.navigate(tab) }, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun Heading(title: String, back: (() -> Unit)? = null, backLabel: String = "", action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back != null) { IconAction(backLabel, Glyph.BACK, back); Spacer(Modifier.width(12.dp)) }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
        action?.invoke()
    }
}

@Composable
private fun Notice(text: String, label: String, action: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(text, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(24.dp))
        Action(label, action, primary = true, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Confirmation(title: String, body: String, confirm: String, cancel: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(18.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        Action(confirm, onConfirm, primary = true, modifier = Modifier.fillMaxWidth())
        QuietAction(cancel, onCancel, Modifier.align(Alignment.CenterHorizontally))
    }
}
