package brain.studio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun AiSettingsSection(s: StudioState) {
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val services = s.repository as? AiPlatformServices
    val packages = services?.aiPackages ?: NoopAiPackageGateway
    val cloud = services?.cloudAi ?: NoopCloudAiGateway
    val execution = services?.aiExecution ?: NoopAiExecutionCapabilityGateway
    val device = services?.deviceCapabilities ?: NoopDeviceCapabilityGateway

    var packageStates by remember { mutableStateOf<List<AiPackageState>>(emptyList()) }
    var connections by remember { mutableStateOf<List<CloudAiConnection>>(emptyList()) }
    var expandedRole by remember { mutableStateOf<AiRole?>(null) }
    var providerEditor by remember { mutableStateOf<CloudProviderDescriptor?>(null) }
    var modelIds by remember { mutableStateOf<Map<AiRole, String>>(emptyMap()) }
    var endpoint by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    var connectionResult by remember { mutableStateOf<Boolean?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var refreshVersion by remember { mutableStateOf(0) }
    var actionBusy by remember { mutableStateOf(false) }
    var permissionSnapshot by remember { mutableStateOf<DeviceCapabilitySnapshot?>(null) }

    fun t(key: String): String = KashaCopy.text(s.language, key) ?: key
    fun roleLabel(role: AiRole): String = when (role) {
        AiRole.SPEECH_TO_TEXT -> t("aiSpeech")
        AiRole.TEXT -> t("aiText")
        AiRole.ROUTING -> t("aiRouting")
    }
    fun dataLabel(kind: AiDataKind): String = when (kind) {
        AiDataKind.AUDIO -> t("aiDataAudio")
        AiDataKind.NOTE_TEXT -> t("aiDataNote")
        AiDataKind.PROJECT_IDS -> t("aiDataProjectIds")
        AiDataKind.PROJECT_TITLES -> t("aiDataProjectTitles")
        AiDataKind.PROJECT_DESCRIPTIONS -> t("aiDataProjectDescriptions")
        AiDataKind.PROJECT_INSTRUCTIONS -> t("aiDataProjectInstructions")
    }
    fun stateFor(id: String): AiPackageState? = packageStates.firstOrNull { it.engineId == (AiCatalog.engine(id)?.id ?: id) }
    fun checkFailed(): String = AiReadinessCopy.text(s.language, "capabilityCheckFailed")
    suspend fun refreshPlatformState() {
        try { packageStates = packages.states() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { packageStates = emptyList(); actionError = checkFailed() }
        try { connections = cloud.connections() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { connections = emptyList(); actionError = checkFailed() }
        refreshVersion++
    }
    fun openProvider(provider: CloudProviderDescriptor) {
        providerEditor = provider
        val existing = connections.firstOrNull { it.providerId == provider.id }
        modelIds = existing?.modelIds.orEmpty()
        endpoint = existing?.endpoint.orEmpty()
        apiKey = ""
        consent = existing?.let(AiPrivacy::hasCurrentConsent) == true
        connectionResult = null
        actionError = null
    }
    fun installModel(requestedId: String) {
        val engineId = AiCatalog.engine(requestedId)?.id ?: requestedId
        if (stateFor(engineId)?.downloading == true) return
        scope.launch {
            actionError = null
            packageStates = packageStates.filterNot { it.engineId == engineId } + AiPackageState(engineId, false, downloading = true)
            try { packages.install(engineId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { actionError = AiReadinessCopy.text(s.language, "modelInstallFailed") }
            refreshPlatformState()
        }
    }
    fun repair(capability: AiRoleCapability) {
        when (capability.action) {
            AiCapabilityAction.CHOOSE_ENGINE -> expandedRole = capability.role
            AiCapabilityAction.INSTALL_MODEL -> {
                if (packages.available && AiCatalog.engine(capability.selectedEngineId)?.installable == true)
                    installModel(capability.selectedEngineId)
                else expandedRole = capability.role
            }
            AiCapabilityAction.EDIT_CONNECTION -> {
                AiCatalog.cloudProviderId(capability.selectedEngineId)?.let(AiCatalog::provider)?.let(::openProvider)
            }
            AiCapabilityAction.REQUEST_PERMISSION, AiCapabilityAction.OPEN_SETTINGS, AiCapabilityAction.RETRY -> scope.launch {
                if (actionBusy) return@launch
                actionBusy = true
                actionError = null
                try {
                    when (capability.action) {
                        AiCapabilityAction.REQUEST_PERMISSION -> capability.permission?.let { device.request(it) }
                        AiCapabilityAction.OPEN_SETTINGS -> if (!device.openSettings()) actionError = checkFailed()
                        else -> Unit
                    }
                    refreshPlatformState()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { actionError = checkFailed() }
                finally { actionBusy = false }
            }
            AiCapabilityAction.NONE -> Unit
        }
    }

    LaunchedEffect(packages, cloud) { refreshPlatformState() }
    LaunchedEffect(packages, packageStates.any { it.downloading }) {
        while (packageStates.any { it.downloading }) {
            delay(500)
            try { packageStates = packages.states() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { actionError = checkFailed(); break }
        }
    }
    // Только на открытом экране: возврат из системных настроек обновляет статус, но не включает микрофон.
    LaunchedEffect(device) {
        if (device === NoopDeviceCapabilityGateway) return@LaunchedEffect
        while (true) {
            try { permissionSnapshot = device.snapshot() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { permissionSnapshot = null }
            delay(2000)
        }
    }

    // Процент загрузки не запускает новые проверки; изменение языка/выбора не использует старый ответ.
    val packageKey = packageStates.map { listOf(it.engineId, it.installed, it.downloading, it.error) }
    val availabilityKey = listOf(execution, s.preferences.ai, s.language, packageKey, connections, expandedRole, refreshVersion, permissionSnapshot)
    var capabilities by remember(availabilityKey) { mutableStateOf<Map<Pair<AiRole, String>, AiRoleCapability>>(emptyMap()) }
    LaunchedEffect(availabilityKey) {
        val selection = s.preferences.ai
        val selections = buildList {
            add(selection)
            expandedRole?.let { role ->
                AiCatalog.enginesFor(role).forEach { engine -> add(AiSelection(engine.id, engine.id, engine.id)) }
                AiCatalog.connectedCloudChoices(role, connections).forEach { engine -> add(selection.with(role, engine.id)) }
            }
        }.distinct()
        for (requested in selections) {
            val result = readAiCapabilities(execution, requested)
            capabilities = capabilities + result.associateBy { it.role to it.selectedEngineId }
        }
    }
    fun capability(role: AiRole, id: String): AiRoleCapability = capabilities[role to id]
        ?: AiReadiness.blocked(role, id, "capabilityChecking")

    Text(t("ai"), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(12.dp))
    if (actionError != null && providerEditor == null) {
        Text(actionError.orEmpty(), style = MaterialTheme.typography.bodySmall, color = colors.error)
        Spacer(Modifier.height(8.dp))
    }

    AiRole.entries.forEach { role ->
        val selectedId = s.preferences.ai.engineId(role)
        val selected = AiCatalog.selectedDescriptor(selectedId)
        val selectedCapability = capability(role, selectedId)
        KashaListCard(
            onClick = { expandedRole = if (expandedRole == role) null else role },
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(roleLabel(role), style = MaterialTheme.typography.bodyMedium)
                Text(selected?.name ?: selectedId, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Text(AiReadinessCopy.status(s.language, selectedCapability, selected?.locality),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Text(if (selected?.locality == AiLocality.CLOUD) t("aiCloud") else t("aiLocal"),
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
        if (!selectedCapability.executable && selectedCapability.action != AiCapabilityAction.NONE) {
            KashaQuietButton(AiReadinessCopy.text(s.language, selectedCapability.action.name),
                { repair(selectedCapability) }, enabled = !actionBusy)
            Spacer(Modifier.height(8.dp))
        }

        if (expandedRole == role) {
            KashaPanel(Modifier.fillMaxWidth().padding(bottom = 10.dp), padding = 14.dp) {
                Text(t("aiModels"), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(10.dp))
                AiCatalog.enginesFor(role).filter { engine ->
                    engine.locality != AiLocality.NATIVE || selectedId == engine.id ||
                        capability(role, engine.id).reason != "platformUnavailable"
                }.forEach { engine ->
                    val state = stateFor(engine.id)
                    val selectedNow = selectedId == engine.id
                    val actual = capability(role, engine.id)
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(engine.name, style = MaterialTheme.typography.bodyMedium)
                            val meta = buildList {
                                engine.approximateSizeMb?.let { add(if (it >= 1000) "~${it / 1000.0} GB" else "~$it MB") }
                                add(engine.description)
                            }.joinToString(" · ")
                            Text(meta, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            Text(AiReadinessCopy.status(s.language, actual, engine.locality),
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(8.dp))
                        when {
                            selectedNow && actual.executable -> Text(t("aiSelected"), style = MaterialTheme.typography.labelSmall)
                            actual.executable -> Column(horizontalAlignment = Alignment.End) {
                                KashaQuietButton(t("aiSelected"), {
                                    scope.launch { s.updatePreferences { it.copy(ai = it.ai.with(role, engine.id)) } }
                                })
                                if (engine.locality == AiLocality.LOCAL && packages.available && !engine.defaultInstalled) {
                                    KashaQuietButton(t("aiRemove"), {
                                        scope.launch {
                                            actionError = null
                                            try { packages.remove(engine.id) }
                                            catch (cancelled: CancellationException) { throw cancelled }
                                            catch (_: Exception) { actionError = checkFailed() }
                                            refreshPlatformState()
                                        }
                                    }, enabled = !actionBusy)
                                }
                            }
                            state?.downloading == true -> Text(
                                state.progress?.let { "${(it * 100).toInt()}%" } ?: s.tr("preparing"),
                                style = MaterialTheme.typography.labelSmall,
                            )
                            actual.action == AiCapabilityAction.INSTALL_MODEL && engine.installable && packages.available ->
                                KashaQuietButton(t("aiDownload"), { installModel(engine.id) }, enabled = !actionBusy)
                            actual.action in setOf(AiCapabilityAction.REQUEST_PERMISSION, AiCapabilityAction.OPEN_SETTINGS, AiCapabilityAction.RETRY) ->
                                KashaQuietButton(AiReadinessCopy.text(s.language, actual.action.name), { repair(actual) }, enabled = !actionBusy)
                        }
                    }
                }
                val cloudChoices = AiCatalog.connectedCloudChoices(role, connections.filter(AiPrivacy::hasCurrentConsent))
                if (cloudChoices.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(t("aiCloudProviders"), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    cloudChoices.forEach { engine ->
                        val actual = capability(role, engine.id)
                        KashaListCard(
                            onClick = {
                                if (actual.executable) scope.launch { s.updatePreferences { it.copy(ai = it.ai.with(role, engine.id)) } }
                                else repair(actual)
                            },
                            modifier = Modifier.padding(bottom = 6.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(engine.name, style = MaterialTheme.typography.bodyMedium)
                                Text(AiReadinessCopy.status(s.language, actual, AiLocality.CLOUD),
                                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                            if (selectedId == engine.id) Text(t("aiSelected"), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Text(t("aiCloudProviders"), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    KashaPanel(Modifier.fillMaxWidth(), padding = 14.dp) {
        Text(t("aiPrivacyWarning"), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
    Spacer(Modifier.height(8.dp))
    AiCatalog.cloudProviders.forEach { provider ->
        val connected = connections.firstOrNull { it.providerId == provider.id && it.enabled && AiPrivacy.hasCurrentConsent(it) }
        KashaListCard(onClick = { openProvider(provider) }, modifier = Modifier.padding(bottom = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(provider.name, style = MaterialTheme.typography.bodyMedium)
                Text(provider.description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Text(if (connected != null) AiReadinessCopy.text(s.language, "connectionSaved")
                    else if (cloud.available) t("aiConnect") else t("aiUnavailable"),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }

    val provider = providerEditor
    if (provider != null) {
        val configuredModels = modelIds.filter { (role, model) -> role in provider.roles && model.isNotBlank() }
        val configuredRoles = configuredModels.keys
        fun configuredConnection(consentGranted: Boolean): CloudAiConnection {
            val base = CloudAiConnection(
                providerId = provider.id,
                modelIds = configuredModels.mapValues { it.value.trim() },
                endpoint = endpoint.trim().ifBlank { null },
                enabled = true,
                privacyConsentVersion = if (consentGranted) AiPrivacy.CONSENT_VERSION else 0,
            )
            return if (consentGranted) base.copy(consentSnapshot = AiPrivacy.snapshot(base)) else base
        }
        Spacer(Modifier.height(8.dp))
        KashaPanel(Modifier.fillMaxWidth(), padding = 16.dp) {
            Text(provider.name, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(14.dp))
            provider.roles.sortedBy { it.ordinal }.forEachIndexed { index, role ->
                if (index > 0) Spacer(Modifier.height(10.dp))
                KashaField(modelIds[role].orEmpty(), { value -> modelIds = modelIds + (role to value); consent = false },
                    "${roleLabel(role)} · ${t("aiModelId")}", Modifier.fillMaxWidth())
            }
            if (provider.endpointRequired) {
                Spacer(Modifier.height(12.dp))
                KashaField(endpoint, { endpoint = it; consent = false }, t("aiEndpoint"), Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(12.dp))
            KashaField(apiKey, { apiKey = it }, t("aiApiKey"), Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            Text(t("aiPrivacy"), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Text(t("aiSends") + ":", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            AiPrivacy.dataFor(configuredRoles.ifEmpty { provider.roles }).forEach { kind ->
                Text("• ${dataLabel(kind)}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            KashaSwitchRow(t("aiConsent"), consent, { consent = it }, enabled = cloud.available)
            if (connectionResult != null) {
                Text(if (connectionResult == true) t("aiConnectionOk") else t("aiConnectionFailed"),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
            }
            if (actionError != null) {
                Text(actionError.orEmpty(), style = MaterialTheme.typography.bodySmall, color = colors.error)
                Spacer(Modifier.height(8.dp))
            }
            val canSave = cloud.available && consent && configuredModels.isNotEmpty() && (!provider.endpointRequired || endpoint.isNotBlank())
            val testConnection: () -> Unit = {
                scope.launch {
                    actionError = null
                    val connection = configuredConnection(consent)
                    connectionResult = runCatching { cloud.test(connection, apiKey.ifBlank { null }) }
                        .onFailure { actionError = it.message }.getOrDefault(false)
                    refreshVersion++
                }
            }
            val saveConnection: () -> Unit = {
                scope.launch {
                    actionError = null
                    val connection = configuredConnection(true)
                    runCatching { cloud.save(connection, apiKey.ifBlank { null }) }
                        .onSuccess { connections = cloud.connections(); providerEditor = null; refreshVersion++ }
                        .onFailure { actionError = it.message }
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth < 520.dp) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        KashaButton(t("aiTestConnection"), testConnection, Modifier.fillMaxWidth(), enabled = canSave)
                        KashaButton(t("aiSaveConnection"), saveConnection, Modifier.fillMaxWidth(), primary = true, enabled = canSave)
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KashaButton(t("aiTestConnection"), testConnection, Modifier.weight(1f), enabled = canSave)
                        KashaButton(t("aiSaveConnection"), saveConnection, Modifier.weight(1f), primary = true, enabled = canSave)
                    }
                }
            }
            val existing = connections.firstOrNull { it.providerId == provider.id }
            if (existing != null) {
                Spacer(Modifier.height(8.dp))
                KashaQuietButton(t("aiDisconnect"), {
                    scope.launch {
                        val result = cloud.disconnect(provider.id)
                        if (result.secretDeletion == SecureSecretDeletion.FAILED) actionError = t("aiConnectionFailed")
                        connections = cloud.connections()
                        providerEditor = null
                        refreshVersion++
                        s.updatePreferences { latest ->
                            var next = latest.ai
                            AiRole.entries.forEach { role ->
                                if (AiCatalog.cloudProviderId(next.engineId(role)) == provider.id) {
                                    next = next.with(role, when (role) {
                                        AiRole.SPEECH_TO_TEXT -> AiCatalog.DEFAULT_STT
                                        AiRole.TEXT -> AiCatalog.DEFAULT_TEXT
                                        AiRole.ROUTING -> AiCatalog.DEFAULT_ROUTING
                                    })
                                }
                            }
                            latest.copy(ai = next)
                        }
                    }
                })
            }
        }
    }
}
