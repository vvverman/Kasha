package brain.studio

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.launch

@Composable
internal fun SettingsScreen(s: StudioState) {
    val scope = rememberCoroutineScope(); val p = s.preferences; val c = MaterialTheme.colorScheme
    fun save(value: Preferences) { scope.launch { s.savePreferences(value) } }
    if (s.languagePage) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
            Heading(s.tr("appLanguage"), { s.languagePage = false }, s.tr("back"))
            (listOf("system" to s.tr("systemLanguage")) + Languages.codes.zip(Languages.names)).forEach { (code, name) ->
                KashaListCard(onClick = { save(p.copy(language = code)) }, modifier = Modifier.padding(bottom = 8.dp)) {
                    Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = if (p.language == code) c.onSurface else c.onSurfaceVariant)
                    if (p.language == code) KashaIcon(Glyph.CHECK, Modifier.size(19.dp))
                }
            }
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
        Heading(s.tr("settings"))

        val externalAi = AiRole.entries.any { role -> AiCatalog.selectedDescriptor(p.ai.engineId(role))?.isExternal == true }
        KashaPanel(Modifier.fillMaxWidth(), padding = 16.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KashaIcon(Glyph.CHECK, Modifier.size(21.dp), c.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text(
                    if (externalAi) KashaCopy.text(s.language, "aiPrivacyWarning") ?: ""
                    else KashaCopy.text(s.language, "localOnly") ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        AiSettingsSection(s)

        Spacer(Modifier.height(24.dp))
        Text(s.tr("recordSettings"), style = MaterialTheme.typography.titleSmall); Spacer(Modifier.height(18.dp))
        KashaPanel(Modifier.fillMaxWidth(), padding = 18.dp) {
            Text(s.tr("quality"), style = MaterialTheme.typography.bodyMedium)
            var quality by remember(p.quality) { mutableStateOf(p.quality.toFloat()) }
            KashaSlider(quality, { quality = it }, 0f..3f, 2, s.tr("quality"), { save(p.copy(quality = quality.toInt())) })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(s.tr("economy"), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                Text(s.tr("high"), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
            Row {
                Text(s.tr("savedSpeed"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text("${p.savedSpeed}×", style = MaterialTheme.typography.bodyMedium)
            }
            var speed by remember(p.savedSpeed) { mutableStateOf(p.savedSpeed.toFloat()) }
            KashaSlider(speed, { speed = it }, 1f..2f, 3, s.tr("savedSpeed"), { save(p.copy(savedSpeed = (kotlin.math.round(speed * 4) / 4).toDouble())) })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("1×", "1,5×", "2×").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant) }
            }
        }
        Spacer(Modifier.height(10.dp)); ToggleRow(s.tr("autoRecord"), p.autoRecord) { save(p.copy(autoRecord = it)) }
        ToggleRow(s.tr("autoRoute"), p.autoRoute) { save(p.copy(autoRoute = it)) }
        Spacer(Modifier.height(24.dp)); Text(s.tr("appearance"), style = MaterialTheme.typography.titleSmall); Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("system", "light", "dark").forEach { theme ->
                Action(s.tr(theme), { save(p.copy(theme = theme)) }, primary = p.theme == theme, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(14.dp))
        KashaListCard(onClick = { s.languagePage = true }) {
            Column(Modifier.weight(1f)) {
                Text(s.tr("appLanguage"), style = MaterialTheme.typography.bodyMedium)
                Text(if (p.language == "system") s.tr("systemLanguage") else Languages.names[Languages.codes.indexOf(p.language)], style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            KashaIcon(Glyph.NEXT, Modifier.size(18.dp))
        }
    }
}
