package com.jervis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.email.CreateOrUpdateEmailAccountRequestDto
import com.jervis.dto.email.EmailAccountDto
import com.jervis.dto.jira.JiraSetupStatusDto
import com.jervis.domain.email.EmailProviderEnum
import com.jervis.domain.git.GitAuthTypeEnum
import com.jervis.domain.git.GitProviderEnum
import com.jervis.repository.JervisRepository
import kotlinx.coroutines.launch

/**
 * Settings screen for commonMain without Material3 dependencies.
 * Tabs implemented using foundation primitives to avoid platform-specific artifacts.
 */
@Composable
fun SettingsScreen(
    repository: JervisRepository,
    onBack: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(SettingsTab.Clients) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText("Settings", style = TextStyle.Default)
            Spacer(Modifier.weight(1f))
            TextButtonLike("Back", onClick = onBack)
        }
        Spacer(Modifier.size(12.dp))

        // Tabs
        TabRow(
            tabs = SettingsTab.values().toList(),
            selected = selectedTab,
            onSelect = { selectedTab = it },
        )

        Spacer(Modifier.size(12.dp))

        when (selectedTab) {
            SettingsTab.Clients -> ClientsTab(repository)
            SettingsTab.Projects -> ProjectsTab(repository)
            SettingsTab.Git -> GitTab(repository)
            SettingsTab.Integrations -> IntegrationsTab(repository)
            SettingsTab.Email -> EmailTab(repository)
        }
    }
}

private enum class SettingsTab { Clients, Projects, Git, Integrations, Email }

@Composable
private fun TabRow(
    tabs: List<SettingsTab>,
    selected: SettingsTab,
    onSelect: (SettingsTab) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        tabs.forEach { tab ->
            val isSelected = tab == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(2.dp)
                    .border(1.dp, if (isSelected) Color(0xFF3366FF) else Color.LightGray)
                    .background(if (isSelected) Color(0x113366FF) else Color.Transparent)
                    .clickable { onSelect(tab) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(tab.name)
            }
        }
    }
}

@Composable
private fun TextButtonLike(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .border(1.dp, Color.LightGray)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) { BasicText(text) }
}

// ————— Clients Tab —————
@Composable
private fun ClientsTab(repository: JervisRepository) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val clients = remember { mutableStateListOf<ClientDto>() }

    var name by remember { mutableStateOf("") }
    var monoRepoUrl by remember { mutableStateOf("") }
    var defaultBranch by remember { mutableStateOf("main") }
    var provider by remember { mutableStateOf<GitProviderEnum?>(null) }
    var authType by remember { mutableStateOf<GitAuthTypeEnum?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        error = try {
            clients.clear()
            clients += repository.clients.listClients()
            null
        } catch (t: Throwable) {
            t.message
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (loading) BasicText("Loading clients…")
        error?.let { BasicText("Error: $it", style = TextStyle(color = Color.Red)) }

        // Create new
        Section("Add Client") {
            LabeledField("Name", name) { name = it }
            LabeledField("Mono Repo URL", monoRepoUrl) { monoRepoUrl = it }
            LabeledField("Default Branch", defaultBranch) { defaultBranch = it }
            EnumSelector("Git Provider", provider, GitProviderEnum.entries) { provider = it }
            EnumSelector("Auth Type", authType, GitAuthTypeEnum.entries) { authType = it }
            Row { TextButtonLike("Create") {
                scope.launch {
                    try {
                        loading = true
                        val created = repository.clients.createClient(
                            ClientDto(
                                name = name.ifBlank { "Unnamed" },
                                monoRepoUrl = monoRepoUrl.ifBlank { null },
                                defaultBranch = defaultBranch.ifBlank { "main" },
                                gitProvider = provider,
                                gitAuthType = authType,
                            )
                        )
                        clients += created
                        name = ""; monoRepoUrl = ""; defaultBranch = "main"; provider = null; authType = null
                        error = null
                    } catch (t: Throwable) {
                        error = t.message
                    } finally { loading = false }
                }
            } }
        }

        Spacer(Modifier.size(8.dp))

        // List + basic inline edit/delete
        Section("Clients") {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(clients, key = { it.id }) { c ->
                    ClientRow(c,
                        onSave = { updated ->
                            scope.launch {
                                try {
                                    val res = repository.clients.updateClient(updated.id, updated)
                                    val idx = clients.indexOfFirst { it.id == res.id }
                                    if (idx >= 0) clients[idx] = res
                                    error = null
                                } catch (t: Throwable) { error = t.message }
                            }
                        },
                        onDelete = {
                            scope.launch {
                                try {
                                    repository.clients.deleteClient(c.id)
                                    clients.removeAll { it.id == c.id }
                                    error = null
                                } catch (t: Throwable) { error = t.message }
                            }
                        })
                }
            }
        }
    }
}

@Composable
private fun ClientRow(
    client: ClientDto,
    onSave: (ClientDto) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(client.id) { mutableStateOf(client.name) }
    var monoRepoUrl by remember(client.id) { mutableStateOf(client.monoRepoUrl ?: "") }
    var defaultBranch by remember(client.id) { mutableStateOf(client.defaultBranch) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .border(1.dp, Color.LightGray)
            .padding(8.dp)
    ) {
        BasicText("Client: ${client.id}")
        LabeledField("Name", name) { name = it }
        LabeledField("Mono Repo URL", monoRepoUrl) { monoRepoUrl = it }
        LabeledField("Default Branch", defaultBranch) { defaultBranch = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButtonLike("Save") { onSave(client.copy(name = name, monoRepoUrl = monoRepoUrl.ifBlank { null }, defaultBranch = defaultBranch.ifBlank { "main" })) }
            TextButtonLike("Delete") { onDelete() }
        }
    }
}

// ————— Projects Tab —————
@Composable
private fun ProjectsTab(repository: JervisRepository) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val clients = remember { mutableStateListOf<ClientDto>() }
    val projects = remember { mutableStateListOf<ProjectDto>() }
    var selectedClientId by remember { mutableStateOf<String?>(null) }
    var availableJiraProjects by remember { mutableStateOf<List<com.jervis.dto.jira.JiraProjectRefDto>>(emptyList()) }

    // New project form
    var projectName by remember { mutableStateOf("") }
    var projectDesc by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        loading = true
        error = try {
            clients.clear(); clients += repository.clients.listClients()
            null
        } catch (t: Throwable) { t.message } finally { loading = false }
    }

    LaunchedEffect(selectedClientId) {
        if (selectedClientId == null) return@LaunchedEffect
        loading = true
        error = try {
            projects.clear(); projects += repository.projects.listProjectsForClient(selectedClientId!!)
            // Preload Jira projects for dropdowns
            availableJiraProjects = runCatching { repository.jiraSetup.listProjects(selectedClientId!!) }.getOrDefault(emptyList())
            null
        } catch (t: Throwable) { t.message } finally { loading = false }
    }

    Column(Modifier.fillMaxSize()) {
        if (loading) BasicText("Loading…")
        error?.let { BasicText("Error: $it", style = TextStyle(color = Color.Red)) }

        Section("Select Client") {
            FlowWrap {
                clients.forEach { c ->
                    val selected = c.id == selectedClientId
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .border(1.dp, if (selected) Color(0xFF3366FF) else Color.LightGray)
                            .background(if (selected) Color(0x113366FF) else Color.Transparent)
                            .clickable { selectedClientId = c.id }
                            .padding(6.dp)
                    ) { BasicText(c.name) }
                }
            }
        }

        if (selectedClientId != null) {
            Section("Add Project") {
                LabeledField("Name", projectName) { projectName = it }
                LabeledField("Description", projectDesc) { projectDesc = it }
                TextButtonLike("Create") {
                    scope.launch {
                        try {
                            loading = true
                            val created = repository.projects.saveProject(
                                ProjectDto(
                                    clientId = selectedClientId,
                                    name = projectName.ifBlank { "New Project" },
                                    description = projectDesc.ifBlank { null },
                                )
                            )
                            projects += created
                            projectName = ""; projectDesc = ""
                            error = null
                        } catch (t: Throwable) { error = t.message } finally { loading = false }
                    }
                }
            }

            Section("Projects") {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(projects, key = { it.id }) { p ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            ProjectRow(
                                project = p,
                                onSave = { updated ->
                                    scope.launch {
                                        try {
                                            val saved = repository.projects.saveProject(updated)
                                            val idx = projects.indexOfFirst { it.id == saved.id }
                                            if (idx >= 0) projects[idx] = saved
                                            error = null
                                        } catch (t: Throwable) { error = t.message }
                                    }
                                },
                                onDelete = {
                                    scope.launch {
                                        try {
                                            repository.projects.deleteProject(p)
                                            projects.removeAll { it.id == p.id }
                                            error = null
                                        } catch (t: Throwable) { error = t.message }
                                    }
                                }
                            )

                            // Overrides section (Jira/Confluence/Git/Email)
                            ProjectOverridesSection(
                                clientId = selectedClientId!!,
                                project = p,
                                repository = repository,
                                availableJiraProjects = availableJiraProjects,
                                onError = { error = it }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectRow(
    project: ProjectDto,
    onSave: (ProjectDto) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(project.id) { mutableStateOf(project.name) }
    var desc by remember(project.id) { mutableStateOf(project.description ?: "") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .border(1.dp, Color.LightGray)
            .padding(8.dp)
    ) {
        BasicText("Project: ${project.id}")
        LabeledField("Name", name) { name = it }
        LabeledField("Description", desc) { desc = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButtonLike("Save") { onSave(project.copy(name = name, description = desc.ifBlank { null })) }
            TextButtonLike("Delete") { onDelete() }
        }
    }
}

// ————— Git Tab —————
@Composable
private fun GitTab(repository: JervisRepository) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val clients = remember { mutableStateListOf<ClientDto>() }
    var selectedClientId by remember { mutableStateOf<String?>(null) }

    var provider by remember { mutableStateOf(GitProviderEnum.GITHUB) }
    var auth by remember { mutableStateOf(GitAuthTypeEnum.HTTPS_PAT) }
    var repoUrl by remember { mutableStateOf("") }
    var defaultBranch by remember { mutableStateOf("main") }
    var httpsToken by remember { mutableStateOf("") }
    var sshPrivateKey by remember { mutableStateOf("") }

    var branches by remember { mutableStateOf<List<String>>(emptyList()) }
    var detectedDefault by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        error = try {
            clients.clear(); clients += repository.clients.listClients()
            null
        } catch (t: Throwable) { t.message } finally { loading = false }
    }

    Column(Modifier.fillMaxSize()) {
        error?.let { BasicText("Error: $it", style = TextStyle(color = Color.Red)) }
        Section("Select Client") {
            FlowWrap {
                clients.forEach { c ->
                    val selected = c.id == selectedClientId
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .border(1.dp, if (selected) Color(0xFF3366FF) else Color.LightGray)
                            .background(if (selected) Color(0x113366FF) else Color.Transparent)
                            .clickable { selectedClientId = c.id }
                            .padding(6.dp)
                    ) { BasicText(c.name) }
                }
            }
        }

        if (selectedClientId != null) {
            Section("Git Configuration") {
                EnumSelector("Provider", provider, GitProviderEnum.entries) { provider = it ?: provider }
                EnumSelector("Auth", auth, GitAuthTypeEnum.entries) { auth = it ?: auth }
                LabeledField("Repo URL", repoUrl) { repoUrl = it }
                LabeledField("Default Branch", defaultBranch) { defaultBranch = it }
                when (auth) {
                    GitAuthTypeEnum.HTTPS_PAT, GitAuthTypeEnum.HTTPS_BASIC -> LabeledField("HTTPS Token/Password", httpsToken) { httpsToken = it }
                    GitAuthTypeEnum.SSH_KEY -> LabeledField("SSH Private Key", sshPrivateKey) { sshPrivateKey = it }
                    GitAuthTypeEnum.NONE -> {}
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButtonLike("Save") {
                        scope.launch {
                            try {
                                loading = true
                                repository.gitConfiguration.setupGitConfiguration(
                                    selectedClientId!!,
                                    com.jervis.dto.GitSetupRequestDto(
                                        gitProvider = provider,
                                        monoRepoUrl = repoUrl,
                                        defaultBranch = defaultBranch,
                                        gitAuthType = auth,
                                        httpsToken = httpsToken.ifBlank { null },
                                        sshPrivateKey = sshPrivateKey.ifBlank { null },
                                    )
                                )
                                error = null
                            } catch (t: Throwable) { error = t.message } finally { loading = false }
                        }
                    }
                    TextButtonLike("Test Connection") {
                        scope.launch {
                            try {
                                loading = true
                                repository.gitConfiguration.testConnection(selectedClientId!!, com.jervis.dto.GitSetupRequestDto(
                                    gitProvider = provider,
                                    monoRepoUrl = repoUrl,
                                    defaultBranch = defaultBranch,
                                    gitAuthType = auth,
                                    httpsToken = httpsToken.ifBlank { null },
                                    sshPrivateKey = sshPrivateKey.ifBlank { null },
                                ))
                                error = null
                            } catch (t: Throwable) { error = t.message } finally { loading = false }
                        }
                    }
                }
            }

            Section("Branches") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButtonLike("Refresh") {
                        scope.launch {
                            try {
                                val resp = repository.gitConfiguration.listRemoteBranches(selectedClientId!!, if (repoUrl.isBlank()) null else repoUrl)
                                branches = resp.branches
                                detectedDefault = resp.defaultBranch
                                error = null
                            } catch (t: Throwable) { error = t.message }
                        }
                    }
                    TextButtonLike("Use Detected Default") {
                        detectedDefault?.let { dd ->
                            scope.launch { repository.gitConfiguration.setDefaultBranch(selectedClientId!!, dd); defaultBranch = dd }
                        }
                    }
                }
                FlowWrap { branches.forEach { b ->
                    val selected = b == defaultBranch
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .border(1.dp, if (selected) Color(0xFF3366FF) else Color.LightGray)
                            .clickable { defaultBranch = b }
                            .padding(6.dp)
                    ) { BasicText(b) }
                } }
            }
        }
    }
}

// ————— Integrations Tab —————
@Composable
private fun IntegrationsTab(repository: JervisRepository) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val clients = remember { mutableStateListOf<ClientDto>() }
    var selectedClientId by remember { mutableStateOf<String?>(null) }
    var jiraStatus by remember { mutableStateOf<JiraSetupStatusDto?>(null) }
    var jiraProjects by remember { mutableStateOf<List<com.jervis.dto.jira.JiraProjectRefDto>>(emptyList()) }
    var selectedJiraPrimary by remember { mutableStateOf<String?>(null) }
    var confluenceSpaceKey by remember { mutableStateOf("") }
    var confluenceRootPageId by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        loading = true
        error = try { clients.clear(); clients += repository.clients.listClients(); null } catch (t: Throwable) { t.message } finally { loading = false }
    }

    LaunchedEffect(selectedClientId) {
        if (selectedClientId == null) return@LaunchedEffect
        loading = true
        error = try {
            jiraStatus = repository.jiraSetup.getStatus(selectedClientId!!)
            jiraProjects = runCatching { repository.jiraSetup.listProjects(selectedClientId!!) }.getOrDefault(emptyList())
            // Initialize fields from status
            selectedJiraPrimary = jiraStatus?.primaryProject
            repository.integrationSettings.getClientStatus(selectedClientId!!).let { cs ->
                confluenceSpaceKey = cs.confluenceSpaceKey ?: ""
                confluenceRootPageId = cs.confluenceRootPageId ?: ""
            }
            null
        } catch (t: Throwable) { t.message } finally { loading = false }
    }

    Column(Modifier.fillMaxSize()) {
        error?.let { BasicText("Error: $it", style = TextStyle(color = Color.Red)) }
        Section("Select Client") {
            FlowWrap { clients.forEach { c ->
                val selected = c.id == selectedClientId
                Box(
                    modifier = Modifier
                        .padding(4.dp)
                        .border(1.dp, if (selected) Color(0xFF3366FF) else Color.LightGray)
                        .background(if (selected) Color(0x113366FF) else Color.Transparent)
                        .clickable { selectedClientId = c.id }
                        .padding(6.dp)
                ) { BasicText(c.name) }
            } }
        }

        jiraStatus?.let { st ->
            Section("Jira Status") {
                BasicText("Tenant: ${st.tenant ?: "-"}")
                BasicText("Email: ${st.email ?: "-"}")
                BasicText("Connected: ${st.connected}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButtonLike("Refresh") { scope.launch { jiraStatus = repository.jiraSetup.getStatus(selectedClientId!!) } }
                    TextButtonLike("Test Connection") {
                        scope.launch {
                            try { jiraStatus = repository.jiraSetup.testConnection(selectedClientId!!); error = null } catch (t: Throwable) { error = t.message }
                        }
                    }
                }
            }

            Section("Jira Primary Project") {
                // Dropdown like: simple selectable chips
                FlowWrap {
                    jiraProjects.forEach { jp ->
                        val selected = jp.key == selectedJiraPrimary
                        Box(
                            modifier = Modifier
                                .padding(4.dp)
                                .border(1.dp, if (selected) Color(0xFF3366FF) else Color.LightGray)
                                .background(if (selected) Color(0x113366FF) else Color.Transparent)
                                .clickable {
                                    selectedJiraPrimary = jp.key
                                    scope.launch {
                                        try {
                                            loading = true
                                            repository.jiraSetup.setPrimaryProject(selectedClientId!!, jp.key)
                                            jiraStatus = repository.jiraSetup.getStatus(selectedClientId!!)
                                            error = null
                                        } catch (t: Throwable) { error = t.message } finally { loading = false }
                                    }
                                }
                                .padding(6.dp)
                        ) { BasicText(text = "${jp.key}") }
                    }
                }
            }

            Section("Confluence Defaults (Client)") {
                LabeledField("Space Key", confluenceSpaceKey) { confluenceSpaceKey = it }
                LabeledField("Root Page ID", confluenceRootPageId) { confluenceRootPageId = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButtonLike("Save") {
                        scope.launch {
                            try {
                                loading = true
                                repository.integrationSettings.setClientConfluenceDefaults(
                                    com.jervis.dto.integration.ClientConfluenceDefaultsDto(
                                        clientId = selectedClientId!!,
                                        confluenceSpaceKey = confluenceSpaceKey.ifBlank { null },
                                        confluenceRootPageId = confluenceRootPageId.ifBlank { null },
                                    )
                                )
                                error = null
                            } catch (t: Throwable) { error = t.message } finally { loading = false }
                        }
                    }
                }
            }
        }

        // TODO: Confluence client status via repository.integrationSettings.getClientStatus(clientId)
    }
}

@Composable
private fun ProjectOverridesSection(
    clientId: String,
    project: ProjectDto,
    repository: JervisRepository,
    availableJiraProjects: List<com.jervis.dto.jira.JiraProjectRefDto>,
    onError: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var message by remember(project.id) { mutableStateOf<String?>(null) }

    // Integration overrides: Jira/Confluence
    var jiraProjectKey by remember(project.id) { mutableStateOf("") }
    var jiraBoardId by remember(project.id) { mutableStateOf("") }
    var confluenceSpaceKey by remember(project.id) { mutableStateOf("") }
    var confluenceRootPageId by remember(project.id) { mutableStateOf("") }

    // Git override
    var gitRemoteUrl by remember(project.id) { mutableStateOf("") }
    var gitAuthType by remember(project.id) { mutableStateOf<com.jervis.domain.git.GitAuthTypeEnum?>(null) }
    var httpsToken by remember(project.id) { mutableStateOf("") }
    var httpsUsername by remember(project.id) { mutableStateOf("") }
    var httpsPassword by remember(project.id) { mutableStateOf("") }
    var sshPrivateKey by remember(project.id) { mutableStateOf("") }
    var sshPassphrase by remember(project.id) { mutableStateOf("") }

    // Email override (project-scoped account)
    var displayName by remember(project.id) { mutableStateOf("") }
    var email by remember(project.id) { mutableStateOf("") }
    var projectEmailAccounts by remember(project.id) { mutableStateOf<List<EmailAccountDto>>(emptyList()) }

    Section("Integration Overrides") {
        // Jira project dropdown via chips
        BasicText("Jira Project (optional)")
        FlowWrap {
            availableJiraProjects.forEach { jp ->
                val selected = jp.key == jiraProjectKey
                Box(
                    modifier = Modifier
                        .padding(4.dp)
                        .border(1.dp, if (selected) Color(0xFF3366FF) else Color.LightGray)
                        .background(if (selected) Color(0x113366FF) else Color.Transparent)
                        .clickable { jiraProjectKey = if (selected) "" else jp.key }
                        .padding(6.dp)
                ) { BasicText(jp.key) }
            }
        }
        LabeledField("Jira Board ID (optional)", jiraBoardId) { jiraBoardId = it }
        LabeledField("Confluence Space Key (optional)", confluenceSpaceKey) { confluenceSpaceKey = it }
        LabeledField("Confluence Root Page ID (optional)", confluenceRootPageId) { confluenceRootPageId = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButtonLike("Save Overrides") {
                scope.launch {
                    runCatching {
                        repository.integrationSettings.setProjectOverrides(
                            com.jervis.dto.integration.ProjectIntegrationOverridesDto(
                                projectId = project.id,
                                jiraProjectKey = jiraProjectKey.ifBlank { "" },
                                jiraBoardId = jiraBoardId.ifBlank { null },
                                confluenceSpaceKey = confluenceSpaceKey.ifBlank { "" },
                                confluenceRootPageId = confluenceRootPageId.ifBlank { "" },
                            )
                        )
                    }.onSuccess { message = "Integration overrides saved"; onError(null) }
                        .onFailure { e -> onError(e.message); message = null }
                }
            }
        }
        message?.let { BasicText(it) }
    }

    Section("Git Override") {
        LabeledField("Remote URL", gitRemoteUrl) { gitRemoteUrl = it }
        EnumSelector("Auth Type", gitAuthType, com.jervis.domain.git.GitAuthTypeEnum.entries) { gitAuthType = it }
        when (gitAuthType) {
            com.jervis.domain.git.GitAuthTypeEnum.HTTPS_PAT, com.jervis.domain.git.GitAuthTypeEnum.HTTPS_BASIC -> {
                LabeledField("HTTPS Token", httpsToken) { httpsToken = it }
                LabeledField("HTTPS Username (optional)", httpsUsername) { httpsUsername = it }
                LabeledField("HTTPS Password (optional)", httpsPassword) { httpsPassword = it }
            }
            com.jervis.domain.git.GitAuthTypeEnum.SSH_KEY -> {
                LabeledField("SSH Private Key", sshPrivateKey) { sshPrivateKey = it }
                LabeledField("SSH Passphrase (optional)", sshPassphrase) { sshPassphrase = it }
            }
            com.jervis.domain.git.GitAuthTypeEnum.NONE, null -> {}
        }
        TextButtonLike("Save Git Override") {
            scope.launch {
                runCatching {
                    repository.gitConfiguration.setupGitOverrideForProject(
                        project.id,
                        com.jervis.dto.ProjectGitOverrideRequestDto(
                            gitRemoteUrl = gitRemoteUrl.ifBlank { null },
                            gitAuthType = gitAuthType,
                            httpsToken = httpsToken.ifBlank { null },
                            httpsUsername = httpsUsername.ifBlank { null },
                            httpsPassword = httpsPassword.ifBlank { null },
                            sshPrivateKey = sshPrivateKey.ifBlank { null },
                            sshPassphrase = sshPassphrase.ifBlank { null },
                        )
                    )
                }.onSuccess { message = "Git override saved"; onError(null) }
                    .onFailure { e -> onError(e.message); message = null }
            }
        }
    }

    Section("Email Override (Project)") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButtonLike("Refresh") {
                scope.launch {
                    runCatching { repository.emailAccounts.listEmailAccounts(clientId = clientId, projectId = project.id) }
                        .onSuccess { projectEmailAccounts = it; onError(null) }
                        .onFailure { e -> onError(e.message) }
                }
            }
        }
        projectEmailAccounts.forEach { acc ->
            BasicText("${acc.displayName} <${acc.email}>")
        }
        LabeledField("Display Name", displayName) { displayName = it }
        LabeledField("Email", email) { email = it }
        TextButtonLike("Create Project Email") {
            scope.launch {
                runCatching {
                    repository.emailAccounts.createEmailAccount(
                        CreateOrUpdateEmailAccountRequestDto(
                            clientId = clientId,
                            projectId = project.id,
                            provider = com.jervis.domain.email.EmailProviderEnum.IMAP,
                            displayName = displayName,
                            email = email,
                        )
                    )
                }.onSuccess {
                    displayName = ""; email = ""
                    onError(null)
                }.onFailure { e -> onError(e.message) }
            }
        }
    }
}

// ————— Email Tab —————
@Composable
private fun EmailTab(repository: JervisRepository) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val clients = remember { mutableStateListOf<ClientDto>() }
    var selectedClientId by remember { mutableStateOf<String?>(null) }
    val accounts = remember { mutableStateListOf<EmailAccountDto>() }

    var displayName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf(EmailProviderEnum.IMAP) }
    var serverHost by remember { mutableStateOf("") }
    var serverPort by remember { mutableStateOf("993") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var useSsl by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        error = try { clients.clear(); clients += repository.clients.listClients(); null } catch (t: Throwable) { t.message } finally { loading = false }
    }

    LaunchedEffect(selectedClientId) {
        if (selectedClientId == null) return@LaunchedEffect
        loading = true
        error = try { accounts.clear(); accounts += repository.emailAccounts.listEmailAccounts(clientId = selectedClientId); null } catch (t: Throwable) { t.message } finally { loading = false }
    }

    Column(Modifier.fillMaxSize()) {
        error?.let { BasicText("Error: $it", style = TextStyle(color = Color.Red)) }
        Section("Select Client") {
            FlowWrap { clients.forEach { c ->
                val selected = c.id == selectedClientId
                Box(
                    modifier = Modifier
                        .padding(4.dp)
                        .border(1.dp, if (selected) Color(0xFF3366FF) else Color.LightGray)
                        .background(if (selected) Color(0x113366FF) else Color.Transparent)
                        .clickable { selectedClientId = c.id }
                        .padding(6.dp)
                ) { BasicText(c.name) }
            } }
        }

        if (selectedClientId != null) {
            Section("Add Email Account") {
                LabeledField("Display Name", displayName) { displayName = it }
                LabeledField("Email", email) { email = it }
                EnumSelector("Provider", provider, EmailProviderEnum.entries) { provider = it ?: provider }
                LabeledField("Server Host", serverHost) { serverHost = it }
                LabeledField("Server Port", serverPort) { serverPort = it }
                LabeledField("Username", username) { username = it }
                LabeledField("Password", password) { password = it }
                Toggle("Use SSL", useSsl) { useSsl = it }
                TextButtonLike("Create") {
                    scope.launch {
                        try {
                            loading = true
                            val created = repository.emailAccounts.createEmailAccount(
                                CreateOrUpdateEmailAccountRequestDto(
                                    clientId = selectedClientId!!,
                                    provider = provider,
                                    displayName = displayName,
                                    email = email,
                                    username = username.ifBlank { null },
                                    password = password.ifBlank { null },
                                    serverHost = serverHost.ifBlank { null },
                                    serverPort = serverPort.toIntOrNull(),
                                    useSsl = useSsl,
                                )
                            )
                            accounts += created
                            displayName = ""; email = ""; serverHost = ""; serverPort = "993"; username = ""; password = ""; useSsl = true
                            error = null
                        } catch (t: Throwable) { error = t.message } finally { loading = false }
                    }
                }
            }

            Section("Accounts") {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(accounts, key = { it.id ?: it.email }) { acc ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp)
                                .border(1.dp, Color.LightGray)
                                .padding(8.dp)
                        ) {
                            BasicText("${acc.displayName} <${acc.email}>")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButtonLike("Validate") {
                                    scope.launch {
                                        try {
                                            acc.id?.let { repository.emailAccounts.validateEmailAccount(it) }
                                            error = null
                                        } catch (t: Throwable) { error = t.message }
                                    }
                                }
                                TextButtonLike("Delete") {
                                    scope.launch {
                                        try {
                                            acc.id?.let { repository.emailAccounts.deleteEmailAccount(it) }
                                            accounts.removeAll { it.id == acc.id }
                                            error = null
                                        } catch (t: Throwable) { error = t.message }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ————— Common small UI helpers (foundation-only) —————
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .border(1.dp, Color.LightGray)
            .padding(8.dp)
    ) {
        BasicText(title)
        Spacer(Modifier.size(6.dp))
        content()
    }
}

@Composable
private fun <T : Enum<T>> EnumSelector(
    label: String,
    value: T?,
    options: List<T>,
    onChange: (T?) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        BasicText(label)
        FlowWrap {
            options.forEach { opt ->
                val selected = opt == value
                Box(
                    modifier = Modifier
                        .padding(4.dp)
                        .border(1.dp, if (selected) Color(0xFF3366FF) else Color.LightGray)
                        .background(if (selected) Color(0x113366FF) else Color.Transparent)
                        .clickable { onChange(if (selected) null else opt) }
                        .padding(6.dp)
                ) { BasicText(opt.name) }
            }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(label)
        Spacer(Modifier.size(8.dp))
        Box(
            modifier = Modifier
                .border(1.dp, Color.LightGray)
                .clickable { onChange(!checked) }
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) { BasicText(if (checked) "ON" else "OFF") }
    }
}

@Composable
private fun LabeledField(label: String, value: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        BasicText(label)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle.Default,
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp)
                .border(1.dp, Color.LightGray)
                .padding(6.dp)
        )
    }
}

@Composable
private fun FlowWrap(content: @Composable () -> Unit) {
    // Simple row; for brevity not a full flow layout – good enough for small sets
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        content()
    }
}
