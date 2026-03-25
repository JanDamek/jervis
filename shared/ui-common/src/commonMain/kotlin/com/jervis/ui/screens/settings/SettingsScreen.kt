package com.jervis.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.jervis.repository.JervisRepository
import com.jervis.ui.LocalRpcGeneration
import com.jervis.ui.design.*
import com.jervis.ui.navigation.Screen
import com.jervis.ui.screens.settings.sections.*
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    repository: JervisRepository,
    onBack: () -> Unit,
    onNavigate: (Screen) -> Unit = {},
) {
    val categories = remember { SettingsCategory.entries.toList() }
    var selectedIndex by remember { mutableIntStateOf(0) }

    JAdaptiveSidebarLayout(
        categories = categories,
        selectedIndex = selectedIndex,
        onSelect = { selectedIndex = it },
        title = "Nastavení",
        categoryIcon = { Icon(it.icon, contentDescription = it.title) },
        categoryTitle = { it.title },
        categoryDescription = { it.description },
        content = { category ->
            SettingsContent(
                category = category,
                repository = repository,
                onNavigate = onNavigate,
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
    INDEXING("Indexace", Icons.Default.Schedule, "Intervaly automatické kontroly nových položek (Git, Jira, Wiki, Email)."),
    GUIDELINES("Pravidla a směrnice", Icons.Default.Gavel, "Coding standards, Git pravidla, review checklist, approval pravidla."),
    GPG_CERTIFICATES("GPG Certifikáty", Icons.Default.Lock, "Správa GPG klíčů pro podepisování commitů coding agentů."),
    OPENROUTER("OpenRouter", Icons.Default.Route, "Směrování LLM požadavků přes OpenRouter AI – API klíč, filtry, prioritní seznam modelů."),
    SPEAKERS("Řečníci", Icons.Default.RecordVoiceOver, "Správa řečníků a hlasových profilů pro automatickou identifikaci."),
}

@Composable
private fun GeneralSettings(repository: JervisRepository) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // System config state
    var isLoading by remember { mutableStateOf(true) }

    val rpcGeneration = LocalRpcGeneration.current
    LaunchedEffect(rpcGeneration) {
        try {
            repository.systemConfig.getSystemConfig()
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("Chyba načítání: ${e.message}")
        } finally {
            isLoading = false
        }
    }

    Box {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
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

            if (isLoading) {
                JCenteredLoading()
            }
        }

        JSnackbarHost(snackbarHostState)
    }
}

@Composable
private fun SettingsContent(
    category: SettingsCategory,
    repository: JervisRepository,
    onNavigate: (Screen) -> Unit,
) {
    when (category) {
        SettingsCategory.GENERAL -> GeneralSettings(repository)
        SettingsCategory.CLIENTS -> ClientsSettings(repository)
        SettingsCategory.PROJECT_GROUPS -> ProjectGroupsSettings(repository)
        SettingsCategory.CONNECTIONS -> ConnectionsSettings(repository)
        SettingsCategory.GUIDELINES -> GuidelinesSettings(repository)
        SettingsCategory.INDEXING -> IndexingSettings(repository)
        SettingsCategory.GPG_CERTIFICATES -> GpgCertificateSettings(repository)
        SettingsCategory.OPENROUTER -> OpenRouterSettings(repository)
        SettingsCategory.SPEAKERS -> SpeakerSettings(repository)
    }
}
