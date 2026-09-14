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
        AiDataKind.PROJECT_TITLES -> t("aiDataProjectTitles")
        AiDataKind.PROJECT_INSTRUCTIONS -> t("aiDataProjectInstructions")
    }
    fun stateFor(id: String): AiPackageState? = packageStates.firstOrNull { it.engineId == id }
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

    Text(t("ai"), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(12.dp))

    AiRole.entries.forEach { role ->
        val selectedId = s.preferences.ai.engineId(role)
        val selected = AiCatalog.selectedDescriptor(selectedId)
        KashaListCard(
            onClick = { expandedRole = if (expandedRole == role) null else role },
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(roleLabel(role), style = MaterialTheme.typography.bodyMedium)
                Text(selected?.name ?: selectedId, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Text(
                if (selected?.locality == AiLocality.CLOUD) t("aiCloud") else t("aiLocal"),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }

        if (expandedRole == role) {
            KashaPanel(Modifier.fillMaxWidth().padding(bottom = 10.dp), padding = 14.dp) {
                Text(t("aiModels"), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(10.dp))
                AiCatalog.enginesFor(role).forEach { engine ->
                    val state = stateFor(engine.id)
                    val selectedNow = selectedId == engine.id
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
                            selectedNow -> Text(t("aiSelected"), style = MaterialTheme.typography.labelSmall)
                            state?.installed == true || (!packages.available && engine.defaultInstalled) -> Column(horizontalAlignment = Alignment.End) {
                                KashaQuietButton(t("aiSelected"), {
                                    scope.launch { s.savePreferences(s.preferences.copy(ai = s.preferences.ai.with(role, engine.id))) }
                                })
                                if (packages.available && !engine.defaultInstalled) {
                                    KashaQuietButton(t("aiRemove"), {
                                        scope.launch {
                                            actionError = null
                                            runCatching { packages.remove(engine.id) }.onFailure { actionError = it.message }
                                            refreshPlatformState()
                                        }
                                    })
                                }
                            }
                            engine.installable && packages.available -> KashaQuietButton(
                                if (state?.downloading == true) "${((state.progress ?: 0f) * 100).toInt()}%" else t("aiDownload"),
                                {
                                    scope.launch {
                                        actionError = null
                                        runCatching { packages.install(engine.id) }.onFailure { actionError = it.message }
                                        refreshPlatformState()
                                    }
                                },
                                enabled = state?.downloading != true,
                            )
                            else -> Text(t("aiUnavailable"), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                    }
                }

                val cloudChoices = AiCatalog.connectedCloudChoices(role, connections)
                if (cloudChoices.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(t("aiCloudProviders"), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    cloudChoices.forEach { engine ->
                        KashaListCard(
                            onClick = { scope.launch { s.savePreferences(s.preferences.copy(ai = s.preferences.ai.with(role, engine.id))) } },
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
        val connected = connections.firstOrNull { it.providerId == provider.id && it.enabled }
        KashaListCard(
            onClick = {
                providerEditor = provider
                val existing = connections.firstOrNull { it.providerId == provider.id }
                modelIds = existing?.modelIds.orEmpty()
                endpoint = existing?.endpoint.orEmpty()
                apiKey = ""
                consent = existing?.privacyConsentVersion?.let { it >= AiPrivacy.CONSENT_VERSION } == true
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
        Spacer(Modifier.height(8.dp))
        KashaPanel(Modifier.fillMaxWidth(), padding = 16.dp) {
            Text(provider.name, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(14.dp))
            provider.roles.sortedBy { it.ordinal }.forEachIndexed { index, role ->
                if (index > 0) Spacer(Modifier.height(10.dp))
                KashaField(
                    modelIds[role].orEmpty(),
                    { value -> modelIds = modelIds + (role to value) },
                    "${roleLabel(role)} · ${t("aiModelId")}",
                    Modifier.fillMaxWidth(),
                )
            }
            if (provider.endpointRequired) {
                Spacer(Modifier.height(12.dp))
                KashaField(endpoint, { endpoint = it }, t("aiEndpoint"), Modifier.fillMaxWidth())
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KashaButton(
                    t("aiTestConnection"),
                    onClick = {
                        scope.launch {
                            actionError = null
                            val connection = CloudAiConnection(
                                providerId = provider.id,
                                modelIds = configuredModels.mapValues { it.value.trim() },
                                endpoint = endpoint.trim().ifBlank { null },
                                enabled = true,
                                privacyConsentVersion = if (consent) AiPrivacy.CONSENT_VERSION else 0,
                            )
                            connectionResult = runCatching { cloud.test(connection, apiKey.ifBlank { null }) }
                                .onFailure { actionError = it.message }
                                .getOrDefault(false)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = canSave,
                )
                KashaButton(
                    t("aiSaveConnection"),
                    onClick = {
                        scope.launch {
                            actionError = null
                            val connection = CloudAiConnection(
                                providerId = provider.id,
                                modelIds = configuredModels.mapValues { it.value.trim() },
                                endpoint = endpoint.trim().ifBlank { null },
                                enabled = true,
                                privacyConsentVersion = AiPrivacy.CONSENT_VERSION,
                            )
                            runCatching { cloud.save(connection, apiKey.ifBlank { null }) }
                                .onSuccess { connections = cloud.connections(); providerEditor = null }
                                .onFailure { actionError = it.message }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    primary = true,
                    enabled = canSave,
                )
            }
            val existing = connections.firstOrNull { it.providerId == provider.id }
            if (existing != null) {
                Spacer(Modifier.height(8.dp))
                KashaQuietButton(t("aiDisconnect"), {
                    scope.launch {
                        cloud.remove(provider.id)
                        connections = cloud.connections()
                        providerEditor = null
                        var next = s.preferences.ai
                        AiRole.entries.forEach { role ->
                            if (AiCatalog.cloudProviderId(next.engineId(role)) == provider.id) {
                                next = next.with(role, when (role) {
                                    AiRole.SPEECH_TO_TEXT -> AiCatalog.DEFAULT_STT
                                    AiRole.TEXT -> AiCatalog.DEFAULT_TEXT
                                    AiRole.ROUTING -> AiCatalog.DEFAULT_ROUTING
                                })
                            }
                        }
                        s.savePreferences(s.preferences.copy(ai = next))
                    }
                })
            }
        }
    }
}
