package brain.studio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun AiSettingsSection(s: StudioState) {
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val services = s.repository as? AiPlatformServices
    val packages = services?.aiPackages ?: NoopAiPackageGateway
    val cloud = services?.cloudAi ?: NoopCloudAiGateway

    var nativeCapabilities by remember { mutableStateOf<Map<Pair<AiRole, String>, AiRoleCapability>>(emptyMap()) }
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
    fun stateFor(id: String): AiPackageState? = packageStates.firstOrNull { it.engineId == id }
    fun selectedReady(role: AiRole, engineId: String, descriptor: AiEngineDescriptor?): Boolean {
        if (descriptor == null) return false
        return when (descriptor.locality) {
            AiLocality.LOCAL -> packages.available && stateFor(engineId)?.installed == true
            AiLocality.CLOUD -> {
                val providerId = AiCatalog.cloudProviderId(engineId) ?: return false
                connections.any {
                    it.providerId == providerId && it.enabled && AiPrivacy.hasCurrentConsent(it) && it.modelFor(role) != null
                }
            }
            AiLocality.NATIVE -> nativeCapabilities[role to engineId]?.executable == true
        }
    }
    fun refreshPlatformState() {
        scope.launch {
            packageStates = runCatching { packages.states() }.getOrDefault(emptyList())
            connections = runCatching { cloud.connections() }.getOrDefault(emptyList())
        }
    }

    LaunchedEffect(packages, cloud) {
        packageStates = runCatching { packages.states() }.getOrDefault(emptyList())
        connections = runCatching { cloud.connections() }.getOrDefault(emptyList())
    }

    LaunchedEffect(packages, packageStates.any { it.downloading }) {
        while (packageStates.any { it.downloading }) {
            kotlinx.coroutines.delay(500)
            try { packageStates = packages.states() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { actionError = error.message ?: t("aiUnavailable"); break }
        }
    }

    LaunchedEffect(services, s.preferences.ai, s.language) {
        val gateway = services?.aiExecution ?: NoopAiExecutionCapabilityGateway
        val selected = s.preferences.ai
        val result = mutableMapOf<Pair<AiRole, String>, AiRoleCapability>()
        for (engine in AiCatalog.engines.filter { it.locality == AiLocality.NATIVE }) {
            for (role in engine.roles) {
                try {
                    gateway.roles(selected.with(role, engine.id))
                        .firstOrNull { it.role == role && it.selectedEngineId == engine.id }
                        ?.let { result[role to engine.id] = it }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    result[role to engine.id] = AiRoleCapability(role, engine.id, false, "platformUnavailable")
                }
            }
        }
        nativeCapabilities = result
    }

    Text(t("ai"), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(12.dp))

    if (actionError != null && providerEditor == null) {
        Text(actionError.orEmpty(), style = MaterialTheme.typography.bodySmall, color = colors.error)
        Spacer(Modifier.height(8.dp))
    }

    AiRole.entries.forEach { role ->
        val selectedId = s.preferences.ai.engineId(role)
        val selected = AiCatalog.selectedDescriptor(selectedId)
        val ready = selectedReady(role, selectedId, selected)
        KashaListCard(
            onClick = { expandedRole = if (expandedRole == role) null else role },
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(roleLabel(role), style = MaterialTheme.typography.bodyMedium)
                Text(selected?.name ?: selectedId, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (selected?.locality == AiLocality.CLOUD) t("aiCloud") else t("aiLocal"),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    if (!ready) t("aiUnavailable") else if (selected?.locality == AiLocality.NATIVE) t("aiSelected") else t("aiInstalled"),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }

        if (expandedRole == role) {
            KashaPanel(Modifier.fillMaxWidth().padding(bottom = 10.dp), padding = 14.dp) {
                Text(t("aiModels"), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(10.dp))
                AiCatalog.enginesFor(role).filter { engine ->
                    val native = nativeCapabilities[role to engine.id]
                    engine.locality != AiLocality.NATIVE || selectedId == engine.id ||
                        (native != null && native.reason != "platformUnavailable")
                }.forEach { engine ->
                    val state = stateFor(engine.id)
                    val selectedNow = selectedId == engine.id
                    val native = nativeCapabilities[role to engine.id]
                    val engineReady = if (engine.locality == AiLocality.NATIVE) native?.executable == true else state?.installed == true
                    val canSelect = engineReady || (engine.locality == AiLocality.NATIVE && native != null && native.reason != "platformUnavailable")
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(engine.name, style = MaterialTheme.typography.bodyMedium)
                            val meta = buildList {
                                engine.approximateSizeMb?.let { add(if (it >= 1000) "~${it / 1000.0} GB" else "~$it MB") }
                                add(engine.description)
                            }.joinToString(" · ")
                            Text(meta, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        when {
                            selectedNow && engineReady -> Text(
                                if (engineReady) t("aiSelected") else t("aiUnavailable"),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                            )
                            !selectedNow && canSelect -> Column(horizontalAlignment = Alignment.End) {
                                KashaQuietButton(t("aiSelected"), {
                                    scope.launch { s.updatePreferences { it.copy(ai = it.ai.with(role, engine.id)) } }
                                })
                                if (engine.locality == AiLocality.LOCAL && packages.available && !engine.defaultInstalled) {
                                    KashaQuietButton(t("aiRemove"), {
                                        scope.launch {
                                            actionError = null
                                            runCatching { packages.remove(engine.id) }.onFailure { actionError = it.message }
                                            refreshPlatformState()
                                        }
                                    })
                                }
                            }
                            engine.installable && packages.available && state != null -> KashaQuietButton(
                                if (state?.downloading == true) state.progress?.let { "${(it * 100).toInt()}%" } ?: s.tr("preparing") else t("aiDownload"),
                                {
                                    scope.launch {
                                        actionError = null
                                        packageStates = packageStates.filterNot { it.engineId == engine.id } +
                                            AiPackageState(engine.id, false, downloading = true)
                                        try { packages.install(engine.id) }
                                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                        catch (error: Exception) { actionError = error.message ?: t("aiUnavailable") }
                                        refreshPlatformState()
                                    }
                                },
                                enabled = state?.downloading != true,
                            )
                            else -> Text(t("aiUnavailable"), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                    }
                }

                val cloudChoices = AiCatalog.connectedCloudChoices(role, connections.filter(AiPrivacy::hasCurrentConsent))
                if (cloudChoices.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(t("aiCloudProviders"), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    cloudChoices.forEach { engine ->
                        KashaListCard(
                            onClick = { scope.launch { s.updatePreferences { it.copy(ai = it.ai.with(role, engine.id)) } } },
                            modifier = Modifier.padding(bottom = 6.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(engine.name, style = MaterialTheme.typography.bodyMedium)
                                Text(t("aiCloud"), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
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
        val connected = connections.firstOrNull {
            it.providerId == provider.id && it.enabled && AiPrivacy.hasCurrentConsent(it)
        }
        KashaListCard(
            onClick = {
                providerEditor = provider
                val existing = connections.firstOrNull { it.providerId == provider.id }
                modelIds = existing?.modelIds.orEmpty()
                endpoint = existing?.endpoint.orEmpty()
                apiKey = ""
                consent = existing?.let(AiPrivacy::hasCurrentConsent) == true
                connectionResult = null
                actionError = null
            },
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(provider.name, style = MaterialTheme.typography.bodyMedium)
                Text(provider.description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Text(
                if (connected != null) t("aiInstalled") else if (cloud.available) t("aiConnect") else t("aiUnavailable"),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
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
                KashaField(
                    modelIds[role].orEmpty(),
                    { value -> modelIds = modelIds + (role to value); consent = false },
                    "${roleLabel(role)} · ${t("aiModelId")}",
                    Modifier.fillMaxWidth(),
                )
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
                Text(
                    if (connectionResult == true) t("aiConnectionOk") else t("aiConnectionFailed"),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
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
                        .onFailure { actionError = it.message }
                        .getOrDefault(false)
                }
            }
            val saveConnection: () -> Unit = {
                scope.launch {
                    actionError = null
                    val connection = configuredConnection(true)
                    runCatching { cloud.save(connection, apiKey.ifBlank { null }) }
                        .onSuccess { connections = cloud.connections(); providerEditor = null }
                        .onFailure { actionError = it.message }
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stacked = maxWidth < 520.dp
                if (stacked) {
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
