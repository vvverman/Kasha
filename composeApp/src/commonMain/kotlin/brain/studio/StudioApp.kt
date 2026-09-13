package brain.studio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val KASHA_NAV_ITEMS = listOf(
    Triple(Tab.HOME, "home", Glyph.HOME),
    Triple(Tab.PROJECTS, "projects", Glyph.FOLDER),
    Triple(Tab.TASKS, "tasks", Glyph.TASKS),
    Triple(Tab.SETTINGS, "settings", Glyph.SETTINGS),
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
        if (state.editRevision > 0) {
            delay(400)
            state.autosave()
        }
    }

    StudioTheme(state.preferences.theme) {
        val c = KashaTheme.colors
        Surface(
            Modifier.fillMaxSize(),
            color = c.canvas,
            contentColor = c.textPrimary,
        ) {
            if (!state.initialized) {
                KashaSplash()
                return@Surface
            }

            val home = state.tab == Tab.HOME
            val brandAlpha = when {
                !home -> 0f
                state.recording -> .008f
                state.working -> .012f
                state.current != null -> .008f
                else -> .03f
            }
            Box(Modifier.fillMaxSize()) {
                KashaWorkspaceBackground(
                    Modifier.fillMaxSize(),
                    showBrandGeometry = home,
                    intensity = if (home) 1f else .5f,
                    brandAlpha = brandAlpha,
                )
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    if (maxWidth >= KashaMetrics.desktopBreakpoint) {
                        KashaDesktopShell(state, scope)
                    } else {
                        KashaCompactShell(state, scope, maxWidth)
                    }
                }
            }
        }
    }
}

@Composable
private fun KashaCompactShell(
    state: StudioState,
    scope: CoroutineScope,
    availableWidth: androidx.compose.ui.unit.Dp,
) {
    val margin = when {
        availableWidth < 360.dp -> KashaMetrics.pageMarginNarrow
        availableWidth < KashaMetrics.compactBreakpoint -> KashaMetrics.pageMarginCompact
        else -> KashaMetrics.pageMarginMedium
    }
    val contentMax = if (availableWidth < KashaMetrics.compactBreakpoint) {
        480.dp
    } else {
        KashaMetrics.contentColumnMax
    }
    val playerVisible = state.recording || state.loadedAudio != null || state.working

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = contentMax)
                    .fillMaxWidth()
                    .padding(horizontal = margin),
            ) {
                KashaMobileBrandBar(state)
            }
        }

        Box(
            Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.TopCenter,
        ) {
            KashaAppContent(
                state,
                scope,
                Modifier
                    .widthIn(max = contentMax)
                    .fillMaxWidth()
                    .padding(horizontal = margin),
            )
        }

        Box(
            Modifier.fillMaxWidth().padding(horizontal = margin),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .widthIn(max = KashaMetrics.navigationMax)
                    .fillMaxWidth(),
            ) {
                if (playerVisible) {
                    GlobalPlayer(state)
                    Spacer(Modifier.height(KashaMetrics.navigationGapToPlayer))
                }
                KashaBottomNavigation(Modifier.fillMaxWidth()) {
                    KASHA_NAV_ITEMS.forEach { (tab, key, icon) ->
                        KashaNavigationItem(
                            label = navigationLabel(state, key),
                            glyph = icon,
                            selected = state.tab == tab,
                            onClick = { state.navigate(tab) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun KashaDesktopShell(
    state: StudioState,
    scope: CoroutineScope,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier
                .widthIn(max = KashaMetrics.shellMaxWidth)
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(
                    horizontal = KashaMetrics.pageMarginExpanded,
                    vertical = 16.dp,
                ),
        ) {
            Column(
                Modifier
                    .width(KashaMetrics.sidebarWidth)
                    .fillMaxHeight(),
            ) {
                KashaBrandSlot(
                    Modifier
                        .width(28.dp)
                        .aspectRatio(604f / 739f),
                    solid = false,
                )
                if (state.repository.simulated) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        state.tr("demoBadge"),
                        style = MaterialTheme.typography.labelMedium,
                        color = KashaTheme.colors.textSecondary,
                    )
                }

                Spacer(Modifier.height(24.dp))
                KashaSidebarNavigation(Modifier.fillMaxWidth()) {
                    KASHA_NAV_ITEMS.forEach { (tab, key, icon) ->
                        KashaNavigationItem(
                            label = navigationLabel(state, key),
                            glyph = icon,
                            selected = state.tab == tab,
                            onClick = { state.navigate(tab) },
                            modifier = Modifier.fillMaxWidth(),
                            horizontal = true,
                        )
                    }
                }
            }

            Spacer(Modifier.width(24.dp))

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    KashaAppContent(
                        state,
                        scope,
                        Modifier
                            .widthIn(max = KashaMetrics.contentColumnMax)
                            .fillMaxWidth(),
                    )
                }

                Box(
                    Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .widthIn(max = KashaMetrics.contentColumnMax)
                            .fillMaxWidth(),
                    ) {
                        GlobalPlayer(state)
                    }
                }
            }
        }
    }
}

@Composable
private fun KashaMobileBrandBar(state: StudioState) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KashaBrandSlot(
            Modifier
                .width(28.dp)
                .aspectRatio(604f / 739f),
            solid = false,
        )
        Spacer(Modifier.weight(1f))
        if (state.repository.simulated) {
            Text(
                state.tr("demoBadge"),
                style = MaterialTheme.typography.labelMedium,
                color = KashaTheme.colors.textSecondary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun KashaAppContent(
    state: StudioState,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxHeight()) {
        when {
            state.error != null -> Notice(
                state.tr(state.error!!),
                state.tr("ok"),
            ) {
                state.error = null
            }

            state.confirmDelete -> Confirmation(
                state.tr("deleteTitle"),
                state.tr("deleteBody"),
                state.tr("delete"),
                state.tr("cancel"),
                { scope.launch { state.discard() } },
                { state.confirmDelete = false },
            )

            state.confirmListenId != null -> Confirmation(
                state.tr("confirmListen"),
                state.tr("preserveRecording"),
                state.tr("stopAndPlay"),
                state.tr("cancel"),
                { scope.launch { state.confirmStopAndListen() } },
                { state.confirmListenId = null },
            )

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
}

private fun navigationLabel(
    state: StudioState,
    key: String,
): String =
    if (key == "tasks") {
        KashaCopy.text(state.language, key) ?: key
    } else {
        state.tr(key)
    }

@Composable
internal fun Heading(
    title: String,
    back: (() -> Unit)? = null,
    backLabel: String = "",
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (back != null) {
            IconAction(backLabel, Glyph.BACK, back)
            Spacer(Modifier.width(12.dp))
        }
        Text(
            title,
            Modifier.weight(1f),
            style = MaterialTheme.typography.headlineMedium,
        )
        action?.invoke()
    }
}

@Composable
private fun Notice(
    text: String,
    label: String,
    action: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(24.dp))
        Action(
            label,
            action,
            primary = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Confirmation(
    title: String,
    body: String,
    confirm: String,
    cancel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(18.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = KashaTheme.colors.textSecondary,
        )
        Spacer(Modifier.height(28.dp))
        Action(
            confirm,
            onConfirm,
            primary = true,
            modifier = Modifier.fillMaxWidth(),
        )
        QuietAction(
            cancel,
            onCancel,
            Modifier.align(Alignment.CenterHorizontally),
        )
    }
}
