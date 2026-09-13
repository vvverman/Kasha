package brain.studio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*

private data class NavEntry(val tab: Tab, val key: String, val glyph: Glyph)

private val navEntries = listOf(
    NavEntry(Tab.HOME, "home", Glyph.HOME),
    NavEntry(Tab.PROJECTS, "projects", Glyph.FOLDER),
    NavEntry(Tab.TASKS, "tasks", Glyph.TASKS),
    NavEntry(Tab.SETTINGS, "settings", Glyph.SETTINGS),
)

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
            BoxWithConstraints(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
            ) {
                val width = maxWidth
                val desktop = width >= 1024.dp
                val wide = width >= 1200.dp
                val compact = width < 360.dp
                val medium = width in 600.dp..1023.dp
                val horizontalPadding = when {
                    desktop -> 32.dp
                    medium -> 24.dp
                    compact -> 16.dp
                    else -> 20.dp
                }
                val shellMaxWidth = if (desktop) 1440.dp else Dp.Unspecified

                Box(
                    Modifier.fillMaxSize().then(if (shellMaxWidth != Dp.Unspecified) Modifier.widthIn(max = shellMaxWidth) else Modifier)
                        .align(Alignment.TopCenter).padding(horizontal = horizontalPadding),
                ) {
                    if (desktop) {
                        DesktopShell(state, scope, wide)
                    } else {
                        MobileShell(state, scope, width)
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopShell(state: StudioState, scope: CoroutineScope, wide: Boolean) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        KashaNavigationSurface(KashaNavigationLayout.Sidebar, Modifier.width(224.dp).fillMaxHeight().padding(vertical = 16.dp)) {
            Column(Modifier.fillMaxSize()) {
                KashaBrandSlot(Modifier.width(30.dp).height(37.dp), solid = true)
                Spacer(Modifier.height(24.dp))
                navEntries.forEach { entry ->
                    val label = navLabel(state, entry.key)
                    KashaNavigationItem(
                        label = label,
                        glyph = entry.glyph,
                        selected = state.tab == entry.tab,
                        onClick = { state.navigate(entry.tab) },
                        modifier = Modifier.fillMaxWidth(),
                        layout = KashaNavigationLayout.Sidebar,
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            AppHeader(state)
            Box(
                Modifier.weight(1f).fillMaxWidth().then(if (wide) Modifier.widthIn(max = 1160.dp) else Modifier),
            ) { AppContent(state, scope) }
            GlobalPlayer(state)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun MobileShell(state: StudioState, scope: CoroutineScope, width: Dp) {
    val fontScale = LocalDensity.current.fontScale
    val gridNav = fontScale > 1.3f || width < 360.dp
    val contentMax = when {
        width >= 600.dp -> 720.dp
        width >= 431.dp -> 480.dp
        else -> Dp.Unspecified
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier.weight(1f).fillMaxWidth().then(if (contentMax != Dp.Unspecified) Modifier.widthIn(max = contentMax) else Modifier),
        ) {
            AppHeader(state)
            Box(Modifier.weight(1f).fillMaxWidth()) { AppContent(state, scope) }
            GlobalPlayer(state)
            Spacer(Modifier.height(8.dp))
        }
        KashaNavigationSurface(
            if (gridNav) KashaNavigationLayout.Grid else KashaNavigationLayout.Bottom,
            Modifier.fillMaxWidth().widthIn(max = 560.dp).padding(bottom = 8.dp),
        ) {
            if (gridNav) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    navEntries.chunked(2).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { entry ->
                                KashaNavigationItem(
                                    navLabel(state, entry.key), entry.glyph, state.tab == entry.tab,
                                    { state.navigate(entry.tab) }, Modifier.weight(1f), KashaNavigationLayout.Grid,
                                )
                            }
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    navEntries.forEach { entry ->
                        KashaNavigationItem(
                            navLabel(state, entry.key), entry.glyph, state.tab == entry.tab,
                            { state.navigate(entry.tab) }, Modifier.weight(1f), KashaNavigationLayout.Bottom,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppHeader(state: StudioState) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        KashaBrandSlot(Modifier.width(22.dp).height(27.dp), solid = true)
        Spacer(Modifier.weight(1f))
        if (state.repository.simulated) {
            Text(state.tr("demoBadge"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun AppContent(state: StudioState, scope: CoroutineScope) {
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

private fun navLabel(state: StudioState, key: String): String =
    if (key == "tasks") KashaCopy.text(state.language, key) ?: key else state.tr(key)

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
