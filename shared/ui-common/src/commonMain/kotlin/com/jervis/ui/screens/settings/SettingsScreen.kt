package com.jervis.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Power
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
import com.jervis.dto.SystemConfigDto
import com.jervis.dto.UpdateSystemConfigRequest
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionResourceDto
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.dto.connection.ConnectionStateEnum
import com.jervis.dto.connection.ProviderEnum
import com.jervis.repository.JervisRepository
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
    ENVIRONMENTS("Prostředí", Icons.Default.Language, "Definice K8s prostředí pro testování."),
    CODING_AGENTS("Coding Agenti", Icons.Default.Code, "Nastavení API klíčů a konfigurace coding agentů (Claude, Junie, Aider)."),
    GPG_CERTIFICATES("GPG Certifikáty", Icons.Default.Lock, "Správa GPG klíčů pro podepisování commitů coding agentů."),
    WHISPER("Whisper", Icons.Default.Mic, "Nastavení přepisu řeči na text a konfigurace modelu."),
}

@Composable
private fun GeneralSettings(repository: JervisRepository) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // System config state
    var isLoading by remember { mutableStateOf(true) }
    var config by remember { mutableStateOf(SystemConfigDto()) }
    var connections by remember { mutableStateOf<List<ConnectionResponseDto>>(emptyList()) }
    // Editable brain config state — single connection for both Jira and Confluence
    var selectedConnectionId by remember { mutableStateOf<String?>(null) }
    var brainProjectKey by remember { mutableStateOf("") }
    var brainIssueType by remember { mutableStateOf("") }
    var brainSpaceKey by remember { mutableStateOf("") }
    var brainRootPageId by remember { mutableStateOf("") }

    // Available resources from selected Atlassian connection
    var bugtrackerResources by remember { mutableStateOf<List<ConnectionResourceDto>>(emptyList()) }
    var wikiResources by remember { mutableStateOf<List<ConnectionResourceDto>>(emptyList()) }
    var loadingBugtrackerResources by remember { mutableStateOf(false) }
    var loadingWikiResources by remember { mutableStateOf(false) }

    fun applyConfig(dto: SystemConfigDto) {
        config = dto
        // Both use the same connection — prefer bugtracker, fallback to wiki
        selectedConnectionId = dto.brainBugtrackerConnectionId ?: dto.brainWikiConnectionId
        brainProjectKey = dto.brainBugtrackerProjectKey ?: ""
        brainIssueType = dto.brainBugtrackerIssueType ?: ""
        brainSpaceKey = dto.brainWikiSpaceKey ?: ""
        brainRootPageId = dto.brainWikiRootPageId ?: ""
    }

    LaunchedEffect(Unit) {
        try {
            val allConnections = repository.connections.getAllConnections()
            connections = allConnections
            val systemConfig = repository.systemConfig.getSystemConfig()
            applyConfig(systemConfig)
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("Chyba načítání: ${e.message}")
        } finally {
            isLoading = false
        }
    }

    // Filter Atlassian connections with both Jira and Confluence capabilities
    val atlassianConnections = remember(connections) {
        connections.filter {
            it.provider == ProviderEnum.ATLASSIAN && it.state == ConnectionStateEnum.VALID &&
                ConnectionCapability.BUGTRACKER in it.capabilities && ConnectionCapability.WIKI in it.capabilities
        }
    }

    // Load Jira projects and Confluence spaces when connection changes
    LaunchedEffect(selectedConnectionId) {
        val connId = selectedConnectionId ?: return@LaunchedEffect
        loadingBugtrackerResources = true
        loadingWikiResources = true
        try {
            bugtrackerResources = repository.connections.listAvailableResources(connId, ConnectionCapability.BUGTRACKER)
        } catch (_: Exception) {
            bugtrackerResources = emptyList()
        } finally {
            loadingBugtrackerResources = false
        }
        try {
            wikiResources = repository.connections.listAvailableResources(connId, ConnectionCapability.WIKI)
        } catch (_: Exception) {
            wikiResources = emptyList()
        } finally {
            loadingWikiResources = false
        }
    }

    Box {
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

            if (isLoading) {
                JCenteredLoading()
            } else {
                JSection(title = "Mozek Jervise") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Centrální Jira a Confluence pro orchestrátor. " +
                                "Slouží k plánování práce, sledování úkolů a ukládání dokumentace.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        // Atlassian connection dropdown
                        JDropdown(
                            items = atlassianConnections,
                            selectedItem = atlassianConnections.find { it.id == selectedConnectionId },
                            onItemSelected = { selectedConnectionId = it.id },
                            label = "Atlassian připojení",
                            itemLabel = { it.name },
                            placeholder = "Vyberte Atlassian připojení",
                        )

                        // Jira project selection
                        if (loadingBugtrackerResources) {
                            JCenteredLoading()
                        } else {
                            JDropdown(
                                items = bugtrackerResources,
                                selectedItem = bugtrackerResources.find { it.id == brainProjectKey },
                                onItemSelected = { brainProjectKey = it.id },
                                label = "Jira projekt",
                                itemLabel = { "${it.id} — ${it.name}" },
                                placeholder = if (selectedConnectionId == null) "Nejdřív vyberte připojení" else "Vyberte Jira projekt",
                            )
                        }

                        // Jira issue type for brain-created issues
                        JTextField(
                            value = brainIssueType,
                            onValueChange = { brainIssueType = it },
                            label = "Typ požadavku (Issue Type)",
                            placeholder = "Např. Task, Úkol, Story...",
                            enabled = selectedConnectionId != null,
                        )

                        // Confluence space selection
                        if (loadingWikiResources) {
                            JCenteredLoading()
                        } else {
                            JDropdown(
                                items = wikiResources,
                                selectedItem = wikiResources.find { it.id == brainSpaceKey },
                                onItemSelected = { brainSpaceKey = it.id },
                                label = "Confluence space",
                                itemLabel = { "${it.id} — ${it.name}" },
                                placeholder = if (selectedConnectionId == null) "Nejdřív vyberte připojení" else "Vyberte Confluence space",
                            )
                        }

                        // Root page ID (optional)
                        JTextField(
                            value = brainRootPageId,
                            onValueChange = { brainRootPageId = it },
                            label = "Kořenová stránka (ID)",
                            placeholder = "Volitelné – ID nadřazené stránky",
                            enabled = selectedConnectionId != null,
                        )

                        // Save button
                        JPrimaryButton(
                            onClick = {
                                scope.launch {
                                    try {
                                        val updated = repository.systemConfig.updateSystemConfig(
                                            UpdateSystemConfigRequest(
                                                brainBugtrackerConnectionId = selectedConnectionId,
                                                brainBugtrackerProjectKey = brainProjectKey.ifBlank { null },
                                                brainBugtrackerIssueType = brainIssueType.ifBlank { null },
                                                brainWikiConnectionId = selectedConnectionId,
                                                brainWikiSpaceKey = brainSpaceKey.ifBlank { null },
                                                brainWikiRootPageId = brainRootPageId.ifBlank { null },
                                            ),
                                        )
                                        applyConfig(updated)
                                        snackbarHostState.showSnackbar("Konfigurace mozku uložena")
                                    } catch (e: Exception) {
                                        snackbarHostState.showSnackbar("Chyba ukládání: ${e.message}")
                                    }
                                }
                            },
                        ) {
                            Text("Uložit")
                        }
                    }
                }
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
        SettingsCategory.INDEXING -> IndexingSettings(repository)
        SettingsCategory.ENVIRONMENTS -> EnvironmentsSettings(
            repository = repository,
            onOpenInManager = { environmentId ->
                onNavigate(Screen.EnvironmentManager(initialEnvironmentId = environmentId))
            },
        )
        SettingsCategory.CODING_AGENTS -> CodingAgentsSettings(repository)
        SettingsCategory.GPG_CERTIFICATES -> GpgCertificateSettings(repository)
        SettingsCategory.WHISPER -> WhisperSettings(repository)
    }
}
