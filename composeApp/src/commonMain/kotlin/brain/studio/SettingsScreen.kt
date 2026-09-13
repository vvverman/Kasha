package brain.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.round

@Composable
internal fun SettingsScreen(s: StudioState) {
    val scope = rememberCoroutineScope()
    val p = s.preferences
    val c = KashaTheme.colors
    val fontScale = LocalDensity.current.fontScale

    fun save(value: Preferences) {
        scope.launch { s.savePreferences(value) }
    }

    if (s.languagePage) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 20.dp),
        ) {
            Heading(s.tr("appLanguage"), { s.languagePage = false }, s.tr("back"))
            (listOf("system" to s.tr("systemLanguage")) + Languages.codes.zip(Languages.names))
                .forEach { (code, name) ->
                    KashaListCard(
                        onClick = { save(p.copy(language = code)) },
                    ) {
                        Text(
                            name,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (p.language == code) c.textPrimary else c.textSecondary,
                        )
                        if (p.language == code) {
                            KashaIcon(
                                Glyph.CHECK,
                                Modifier.size(19.dp),
                                c.accentContent,
                            )
                        }
                    }
                }
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 20.dp),
    ) {
        Heading(s.tr("settings"))

        SettingsGroupTitle(s.tr("recordSettings"))
        KashaPanel(Modifier.fillMaxWidth(), padding = 16.dp) {
            ToggleRow(s.tr("autoRecord"), p.autoRecord) {
                save(p.copy(autoRecord = it))
            }
            Spacer(Modifier.height(12.dp))

            Text(s.tr("quality"), style = MaterialTheme.typography.bodyMedium)
            var quality by remember(p.quality) { mutableStateOf(p.quality.toFloat()) }
            KashaSlider(
                quality,
                { quality = it },
                0f..3f,
                2,
                s.tr("quality"),
                { save(p.copy(quality = quality.toInt())) },
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    s.tr("economy"),
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textSecondary,
                )
                Text(
                    s.tr("high"),
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textSecondary,
                )
            }

            Spacer(Modifier.height(24.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    s.tr("savedSpeed"),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "${p.savedSpeed}×",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textSecondary,
                )
            }
            var speed by remember(p.savedSpeed) { mutableStateOf(p.savedSpeed.toFloat()) }
            KashaSlider(
                speed,
                { speed = it },
                1f..2f,
                3,
                s.tr("savedSpeed"),
                {
                    save(
                        p.copy(
                            savedSpeed = (round(speed * 4) / 4).toDouble(),
                        ),
                    )
                },
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                listOf("1×", "1,5×", "2×").forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = c.textSecondary,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            ToggleRow(s.tr("autoRoute"), p.autoRoute) {
                save(p.copy(autoRoute = it))
            }
        }

        Spacer(Modifier.height(28.dp))
        AiSettingsSection(s)

        Spacer(Modifier.height(28.dp))
        SettingsGroupTitle(s.tr("appearance"))
        KashaPanel(Modifier.fillMaxWidth(), padding = 12.dp) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val horizontal = maxWidth >= 340.dp && fontScale <= 1.3f
                if (horizontal) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf("system", "light", "dark").forEach { theme ->
                            Action(
                                s.tr(theme),
                                { save(p.copy(theme = theme)) },
                                primary = p.theme == theme,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                } else {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf("system", "light", "dark").forEach { theme ->
                            Action(
                                s.tr(theme),
                                { save(p.copy(theme = theme)) },
                                primary = p.theme == theme,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            KashaListCard(onClick = { s.languagePage = true }) {
                Column(Modifier.weight(1f)) {
                    Text(
                        s.tr("appLanguage"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (p.language == "system") {
                            s.tr("systemLanguage")
                        } else {
                            Languages.names[Languages.codes.indexOf(p.language)]
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary,
                    )
                }
                KashaIcon(
                    Glyph.NEXT,
                    Modifier.size(18.dp),
                    c.iconSecondary,
                )
            }
        }

        Spacer(Modifier.height(28.dp))
        SettingsGroupTitle(KashaCopy.text(s.language, "aiPrivacy") ?: "Приватность")
        val externalAi = AiRole.entries.any { role ->
            AiCatalog.selectedDescriptor(p.ai.engineId(role))?.isExternal == true
        }
        KashaPanel(Modifier.fillMaxWidth(), padding = 16.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KashaIcon(
                    Glyph.CHECK,
                    Modifier.size(21.dp),
                    if (externalAi) c.iconSecondary else c.success,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    if (externalAi) {
                        KashaCopy.text(s.language, "aiPrivacyWarning") ?: ""
                    } else {
                        KashaCopy.text(s.language, "localOnly") ?: ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
            }
        }

        Spacer(Modifier.height(28.dp))
        SettingsGroupTitle("Kasha")
        KashaPanel(Modifier.fillMaxWidth(), padding = 16.dp) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KashaBrandSlot(
                    Modifier
                        .width(24.dp)
                        .aspectRatio(604f / 739f),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Kasha",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "v$KASHA_VERSION",
                    style = MaterialTheme.typography.labelMedium,
                    color = c.textSecondary,
                )
            }
            if (s.repository.simulated) {
                Spacer(Modifier.height(8.dp))
                Text(
                    s.tr("demoBadge"),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun SettingsGroupTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = KashaTheme.colors.textPrimary,
    )
    Spacer(Modifier.height(10.dp))
}
