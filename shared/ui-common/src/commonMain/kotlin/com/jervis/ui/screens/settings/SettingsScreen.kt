package com.jervis.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
        categoryIcon = { it.icon },
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
    val icon: String,
    val description: String,
) {
    GENERAL("Obecné", "⚙️", "Základní nastavení aplikace a vzhledu."),
    CLIENTS("Klienti a projekty", "🏢", "Správa klientů, projektů a jejich konfigurace."),
    PROJECT_GROUPS("Skupiny projektů", "📂", "Logické seskupení projektů se sdílenou KB."),
    CONNECTIONS("Připojení", "🔌", "Technické parametry připojení (Atlassian, Git, Email)."),
    ENVIRONMENTS("Prostředí", "🌐", "Definice K8s prostředí pro testování."),
    CODING_AGENTS("Coding Agenti", "🤖", "Nastavení API klíčů a konfigurace coding agentů (Claude, Junie, Aider)."),
    WHISPER("Whisper", "🎙️", "Nastavení přepisu řeči na text a konfigurace modelu."),
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
