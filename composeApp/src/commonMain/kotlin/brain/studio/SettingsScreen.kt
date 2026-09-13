package brain.studio

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.launch
import kotlin.math.round

@Composable
internal fun SettingsScreen(s: StudioState) {
    val scope = rememberCoroutineScope()
    val p = s.preferences
    val c = MaterialTheme.colorScheme
    var audioPage by remember { mutableStateOf(false) }

    fun save(transform: (Preferences) -> Preferences) {
        scope.launch { s.savePreferences(transform(s.preferences)) }
    }

    if (s.languagePage) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
            Heading(s.tr("appLanguage"), { s.languagePage = false }, s.tr("back"))
            (listOf("system" to s.tr("systemLanguage")) + Languages.codes.zip(Languages.names)).forEach { (code, name) ->
                KashaListCard(onClick = { save { it.copy(language = code) } }, modifier = Modifier.padding(bottom = 8.dp)) {
                    Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = if (p.language == code) c.onSurface else c.onSurfaceVariant)
                    if (p.language == code) KashaIcon(Glyph.CHECK, Modifier.size(19.dp))
                }
            }
        }
        return
    }

    if (audioPage) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
            Heading(s.tr("recordSettings"), { audioPage = false }, s.tr("back"))
            KashaPanel(Modifier.fillMaxWidth(), padding = 18.dp) {
                Text(s.tr("quality"), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                var quality by remember(p.quality) { mutableStateOf(p.quality.toFloat()) }
                KashaSlider(
                    quality,
                    { quality = it },
                    0f..3f,
                    2,
                    s.tr("quality"),
                    { save { it.copy(quality = quality.toInt()) } },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(s.tr("economy"), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                    Text(s.tr("high"), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                }
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.tr("savedSpeed"), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Text("${p.savedSpeed}×", style = MaterialTheme.typography.bodyMedium)
                }
                var speed by remember(p.savedSpeed) { mutableStateOf(p.savedSpeed.toFloat()) }
                KashaSlider(
                    speed,
                    { speed = it },
                    1f..2f,
                    3,
                    s.tr("savedSpeed"),
                    { save { it.copy(savedSpeed = (round(speed * 4) / 4).toDouble()) } },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("1×", "1,5×", "2×").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant) }
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
        Heading(s.tr("settings"))

        Text(s.tr("recordSettings"), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(10.dp))
        KashaPanel(Modifier.fillMaxWidth(), padding = 16.dp) {
            ToggleRow(s.tr("autoRecord"), p.autoRecord) { value -> save { it.copy(autoRecord = value) } }
            Spacer(Modifier.height(4.dp))
            KashaListCard(onClick = { audioPage = true }) {
                Column(Modifier.weight(1f)) {
                    Text(s.tr("quality"), style = MaterialTheme.typography.bodyMedium)
                    Text("${p.bitrate} кбит/с · ${p.savedSpeed}×", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
                KashaIcon(Glyph.NEXT, Modifier.size(18.dp), c.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            ToggleRow(s.tr("autoRoute"), p.autoRoute) { value -> save { it.copy(autoRoute = value) } }
        }

        Spacer(Modifier.height(28.dp))
        AiSettingsSection(s)

        Spacer(Modifier.height(28.dp))
        Text(s.tr("appearance"), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(10.dp))
        KashaPanel(Modifier.fillMaxWidth(), padding = 16.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("system", "light", "dark").forEach { theme ->
                    Action(s.tr(theme), { save { it.copy(theme = theme) } }, primary = p.theme == theme, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(12.dp))
            KashaListCard(onClick = { s.languagePage = true }) {
                Column(Modifier.weight(1f)) {
                    Text(s.tr("appLanguage"), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (p.language == "system") s.tr("systemLanguage") else Languages.names[Languages.codes.indexOf(p.language)],
                        style = MaterialTheme.typography.bodySmall,
                        color = c.onSurfaceVariant,
                    )
                }
                KashaIcon(Glyph.NEXT, Modifier.size(18.dp), c.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(28.dp))
        Text(KashaCopy.text(s.language, "aiPrivacy") ?: "", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(10.dp))
        val externalAi = AiRole.entries.any { role -> AiCatalog.selectedDescriptor(p.ai.engineId(role))?.isExternal == true }
        KashaPanel(Modifier.fillMaxWidth(), padding = 16.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KashaIcon(if (externalAi) Glyph.EXTERNAL else Glyph.CHECK, Modifier.size(21.dp), c.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text(
                    if (externalAi) KashaCopy.text(s.language, "aiPrivacyWarning") ?: ""
                    else KashaCopy.text(s.language, "localOnly") ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.onSurfaceVariant,
                )
            }
        }

        if (s.repository.simulated) {
            Spacer(Modifier.height(28.dp))
            Text(s.tr("about"), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(10.dp))
            KashaPanel(Modifier.fillMaxWidth(), padding = 16.dp) {
                Text(s.tr("demoNotice"), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
        }
    }
}
