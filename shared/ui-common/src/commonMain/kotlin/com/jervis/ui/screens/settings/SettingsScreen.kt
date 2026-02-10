package com.jervis.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.*
import com.jervis.ui.screens.settings.sections.*

@Composable
fun SettingsScreen(
    repository: JervisRepository,
    onBack: () -> Unit,
) {
    val categories = remember { SettingsCategory.entries.toList() }
    var selectedIndex by remember { mutableIntStateOf(0) }

    JAdaptiveSidebarLayout(
        categories = categories,
        selectedIndex = selectedIndex,
        onSelect = { selectedIndex = it },
        onBack = onBack,
        title = "Nastavení",
        categoryIcon = { Icon(it.icon, contentDescription = it.title) },
        categoryTitle = { it.title },
        categoryDescription = { it.description },
        content = { category ->
            SettingsContent(
                category = category,
                repository = repository,
            )
        },
    )
}

enum class SettingsCategory(
    val title: String,
    val icon: ImageVector,
    val description: String,
) {
    GENERAL("Obecné", Icons.Default.Settings, "Základní nastavení aplikace a vzhledu."),
    CLIENTS("Klienti a projekty", Icons.Default.Business, "Správa klientů, projektů a jejich konfigurace."),
    PROJECT_GROUPS("Skupiny projektů", Icons.Default.Folder, "Logické seskupení projektů se sdílenou KB."),
    CONNECTIONS("Připojení", Icons.Default.Power, "Technické parametry připojení (Atlassian, Git, Email)."),
    ENVIRONMENTS("Prostředí", Icons.Default.Language, "Definice K8s prostředí pro testování."),
    CODING_AGENTS("Coding Agenti", Icons.Default.Code, "Nastavení API klíčů a konfigurace coding agentů (Claude, Junie, Aider)."),
    WHISPER("Whisper", Icons.Default.Mic, "Nastavení přepisu řeči na text a konfigurace modelu."),
}

@Composable
private fun GeneralSettings(repository: JervisRepository) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        JSection(title = "Vzhled") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.height(JervisSpacing.touchTarget),
            ) {
                Text("Téma aplikace")
                Spacer(modifier = Modifier.weight(1f))
                Text("Systémové", style = MaterialTheme.typography.bodySmall)
            }
        }
        JSection(title = "Lokalizace") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.height(JervisSpacing.touchTarget),
            ) {
                Text("Jazyk")
                Spacer(modifier = Modifier.weight(1f))
                Text("Čeština", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SettingsContent(
    category: SettingsCategory,
    repository: JervisRepository,
) {
    when (category) {
        SettingsCategory.GENERAL -> GeneralSettings(repository)
        SettingsCategory.CLIENTS -> ClientsSettings(repository)
        SettingsCategory.PROJECT_GROUPS -> ProjectGroupsSettings(repository)
        SettingsCategory.CONNECTIONS -> ConnectionsSettings(repository)
        SettingsCategory.ENVIRONMENTS -> EnvironmentsSettings(repository)
        SettingsCategory.CODING_AGENTS -> CodingAgentsSettings(repository)
        SettingsCategory.WHISPER -> WhisperSettings(repository)
    }
}
