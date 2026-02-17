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
import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.SystemConfigDto
import com.jervis.dto.UpdateSystemConfigRequest
import com.jervis.dto.connection.ConnectionCapability
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
    var allProjects by remember { mutableStateOf<List<ProjectDto>>(emptyList()) }
    var allClients by remember { mutableStateOf<List<ClientDto>>(emptyList()) }

    // Editable state — internal project
    var selectedInternalProjectId by remember { mutableStateOf<String?>(null) }

    // Editable brain config state
    var selectedBugtrackerConnectionId by remember { mutableStateOf<String?>(null) }
    var brainProjectKey by remember { mutableStateOf("") }
    var selectedWikiConnectionId by remember { mutableStateOf<String?>(null) }
    var brainSpaceKey by remember { mutableStateOf("") }
    var brainRootPageId by remember { mutableStateOf("") }

    fun applyConfig(dto: SystemConfigDto) {
        config = dto
        selectedInternalProjectId = dto.jervisInternalProjectId
        selectedBugtrackerConnectionId = dto.brainBugtrackerConnectionId
        brainProjectKey = dto.brainBugtrackerProjectKey ?: ""
        selectedWikiConnectionId = dto.brainWikiConnectionId
        brainSpaceKey = dto.brainWikiSpaceKey ?: ""
        brainRootPageId = dto.brainWikiRootPageId ?: ""
    }

    // Build client name map for dropdown labels
    val clientNameMap = remember(allClients) {
        allClients.associate { it.id to it.name }
    }

    LaunchedEffect(Unit) {
        try {
            val allConnections = repository.connections.getAllConnections()
            connections = allConnections
            allClients = repository.clients.getAllClients()
            allProjects = repository.projects.getAllProjects()
            val systemConfig = repository.systemConfig.getSystemConfig()
            applyConfig(systemConfig)
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("Chyba načítání: ${e.message}")
        } finally {
            isLoading = false
        }
    }

    // Filter Atlassian connections with valid state
    val atlassianConnections = remember(connections) {
        connections.filter {
            it.provider == ProviderEnum.ATLASSIAN && it.state == ConnectionStateEnum.VALID
        }
    }
    val bugtrackerConnections = remember(atlassianConnections) {
        atlassianConnections.filter { ConnectionCapability.BUGTRACKER in it.capabilities }
    }
    val wikiConnections = remember(atlassianConnections) {
        atlassianConnections.filter { ConnectionCapability.WIKI in it.capabilities }
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
                JSection(title = "Interní projekt") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Projekt pro orchestrátor — plánování práce a interní dokumentace. " +
                                "Tento projekt nebude zobrazen v přehledech.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        JDropdown(
                            items = allProjects,
                            selectedItem = allProjects.find { it.id == selectedInternalProjectId },
                            onItemSelected = { selectedInternalProjectId = it.id },
                            label = "Projekt",
                            itemLabel = { project ->
                                val clientName = clientNameMap[project.clientId] ?: "?"
                                "$clientName / ${project.name}"
                            },
                            placeholder = "Vyberte interní projekt",
                        )

                        JPrimaryButton(
                            onClick = {
                                scope.launch {
                                    try {
                                        val updated = repository.systemConfig.updateSystemConfig(
                                            UpdateSystemConfigRequest(
                                                jervisInternalProjectId = selectedInternalProjectId,
                                            ),
                                        )
                                        applyConfig(updated)
                                        // Reload projects to reflect isJervisInternal flag change
                                        allProjects = repository.projects.getAllProjects()
                                        snackbarHostState.showSnackbar("Interní projekt uložen")
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

                JSection(title = "Mozek Jervise") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Centrální Jira a Confluence pro orchestrátor. " +
                                "Slouží k plánování práce, sledování úkolů a ukládání dokumentace.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        // Bugtracker connection dropdown
                        JDropdown(
                            items = bugtrackerConnections,
                            selectedItem = bugtrackerConnections.find { it.id == selectedBugtrackerConnectionId },
                            onItemSelected = { selectedBugtrackerConnectionId = it.id },
                            label = "Bugtracker (Jira)",
                            itemLabel = { it.name },
                            placeholder = "Vyberte Atlassian připojení",
                        )

                        // Jira project key
                        JTextField(
                            value = brainProjectKey,
                            onValueChange = { brainProjectKey = it },
                            label = "Jira Project Key",
                            placeholder = "Např. JERVIS",
                            enabled = selectedBugtrackerConnectionId != null,
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        // Wiki connection dropdown
                        JDropdown(
                            items = wikiConnections,
                            selectedItem = wikiConnections.find { it.id == selectedWikiConnectionId },
                            onItemSelected = { selectedWikiConnectionId = it.id },
                            label = "Wiki (Confluence)",
                            itemLabel = { it.name },
                            placeholder = "Vyberte Atlassian připojení",
                        )

                        // Confluence space key
                        JTextField(
                            value = brainSpaceKey,
                            onValueChange = { brainSpaceKey = it },
                            label = "Confluence Space Key",
                            placeholder = "Např. JERVIS",
                            enabled = selectedWikiConnectionId != null,
                        )

                        // Root page ID (optional)
                        JTextField(
                            value = brainRootPageId,
                            onValueChange = { brainRootPageId = it },
                            label = "Kořenová stránka (ID)",
                            placeholder = "Volitelné – ID nadřazené stránky",
                            enabled = selectedWikiConnectionId != null,
                        )

                        // Save button
                        JPrimaryButton(
                            onClick = {
                                scope.launch {
                                    try {
                                        val updated = repository.systemConfig.updateSystemConfig(
                                            UpdateSystemConfigRequest(
                                                brainBugtrackerConnectionId = selectedBugtrackerConnectionId,
                                                brainBugtrackerProjectKey = brainProjectKey.ifBlank { null },
                                                brainWikiConnectionId = selectedWikiConnectionId,
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
