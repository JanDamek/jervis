package com.jervis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.jervis.domain.email.EmailProviderEnum
import com.jervis.domain.git.GitAuthTypeEnum
import com.jervis.domain.git.GitProviderEnum
import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.email.CreateOrUpdateEmailAccountRequestDto
import com.jervis.dto.email.EmailAccountDto
import com.jervis.dto.jira.JiraSetupStatusDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.util.pickTextFileContent
import kotlinx.coroutines.launch

/**
 * Settings screen for commonMain without Material3 dependencies.
 * Tabs implemented using foundation primitives to avoid platform-specific artifacts.
 */
@Composable
fun SettingsScreen(
    repository: JervisRepository,
    onBack: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Back") }
        }
        Spacer(Modifier.size(12.dp))

        // Navigation simplified: everything starts from Clients list → Client detail
        ClientsTab(repository)
    }
}

@Composable
private fun TextButtonLike(
    text: String,
    onClick: () -> Unit,
) {
    Button(onClick = onClick) { Text(text) }
}

// ————— Clients Tab —————
private sealed interface ClientsMode {
    data object List : ClientsMode

    data object Create : ClientsMode

    data class Edit(
        val clientId: String,
        val tab: ClientEditTab = ClientEditTab.Basic,
    ) : ClientsMode
}

private enum class ClientEditTab { Basic, Git, Integrations, Email, Projects }

@Composable
private fun ClientsTab(repository: JervisRepository) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val clients = remember { mutableStateListOf<ClientDto>() }

    var mode by remember { mutableStateOf<ClientsMode>(ClientsMode.List) }

    LaunchedEffect(Unit) {
        loading = true
        error =
            try {
                clients.clear()
                clients += repository.clients.listClients()
                null
            } catch (t: Throwable) {
                t.message
            } finally {
                loading = false
            }
    }

    fun refreshClients() {
        scope.launch {
            try {
                loading = true
                clients.clear()
                clients += repository.clients.listClients()
                error = null
            } catch (t: Throwable) {
                error = t.message
            } finally {
                loading = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (loading) Text("Loading clients…")
        error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }

        when (val m = mode) {
            is ClientsMode.List ->
                ClientsList(
                    clients = clients,
                    onNew = { mode = ClientsMode.Create },
                    onEdit = { clientId -> mode = ClientsMode.Edit(clientId) },
                    onDelete = { clientId ->
                        scope.launch {
                            try {
                                repository.clients.deleteClient(clientId)
                                clients.removeAll { it.id == clientId }
                                error = null
                            } catch (t: Throwable) {
                                error = t.message
                            }
                        }
                    },
                    onRefresh = { refreshClients() },
                )

            ClientsMode.Create ->
                ClientCreateScreen(
                    repository = repository,
                    onBack = { mode = ClientsMode.List },
                    onCreated = { created ->
                        clients += created
                        mode = ClientsMode.Edit(created.id)
                    },
                )

            is ClientsMode.Edit ->
                ClientEditScreen(
                    repository = repository,
                    clientId = m.clientId,
                    initialTab = m.tab,
                    onBack = {
                        mode = ClientsMode.List
                        refreshClients()
                    },
                )
        }
    }
}

@Composable
private fun ClientsList(
    clients: List<ClientDto>,
    onNew: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var clientIdToDelete by remember { mutableStateOf<String?>(null) }
    Section("Clients") {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(clients, key = { it.id }) { c ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onEdit(c.id) }
                            .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.titleMedium)
                        val desc =
                            buildString {
                                append("Default branch: ")
                                append(c.defaultBranch)
                                c.monoRepoUrl?.let {
                                    append(" · Repo: ")
                                    append(it)
                                }
                            }
                        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = {
                            clientIdToDelete = c.id
                            showDeleteConfirm = true
                        }) { Text("🗑️") }
                    }
                }
                Divider()
            }
        }
        Spacer(Modifier.size(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButtonLike("New Client") { onNew() }
            TextButtonLike("Refresh") { onRefresh() }
        }
    }

    if (showDeleteConfirm && clientIdToDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Client") },
            text = { Text("Are you sure you want to delete this client?") },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete(requireNotNull(clientIdToDelete))
                        clientIdToDelete = null
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { OutlinedButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ClientCreateScreen(
    repository: JervisRepository,
    onBack: () -> Unit,
    onCreated: (ClientDto) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var monoRepoUrl by remember { mutableStateOf("") }
    var defaultBranch by remember { mutableStateOf("main") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("Back to Clients") }
            Spacer(Modifier.size(8.dp))
            Text("Create Client", style = MaterialTheme.typography.titleLarge)
        }
        error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }

        Section("Basic") {
            LabeledField("Name", name) { name = it }
            LabeledField("Mono Repo URL", monoRepoUrl) { monoRepoUrl = it }
            LabeledField("Default Branch", defaultBranch) { defaultBranch = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButtonLike("Create") {
                    scope.launch {
                        runCatching {
                            repository.clients.createClient(
                                ClientDto(
                                    name = name.ifBlank { "Unnamed" },
                                    monoRepoUrl = monoRepoUrl.ifBlank { null },
                                    defaultBranch = defaultBranch.ifBlank { "main" },
                                ),
                            )
                        }.onSuccess { created ->
                            onCreated(created)
                        }.onFailure { e -> error = e.message }
                    }
                }
                TextButtonLike("Cancel") { onBack() }
            }
        }
    }
}

@Composable
private fun ClientEditScreen(
    repository: JervisRepository,
    clientId: String,
    initialTab: ClientEditTab = ClientEditTab.Basic,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var client by remember(clientId) { mutableStateOf<ClientDto?>(null) }
    var selectedTab by remember(clientId) { mutableStateOf(initialTab) }

    // Basic fields
    var name by remember(clientId) { mutableStateOf("") }
    var monoRepoUrl by remember(clientId) { mutableStateOf("") }
    var defaultBranch by remember(clientId) { mutableStateOf("main") }

    LaunchedEffect(clientId) {
        loading = true
        error =
            try {
                val c = repository.clients.getClientById(clientId)
                client = c
                if (c != null) {
                    name = c.name
                    monoRepoUrl = c.monoRepoUrl ?: ""
                    defaultBranch = c.defaultBranch
                }
                null
            } catch (t: Throwable) {
                t.message
            } finally {
                loading = false
            }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("Back to Clients") }
            Spacer(Modifier.size(8.dp))
            Column {
                val titleName =
                    when {
                        name.isNotBlank() -> name
                        client?.name?.isNotBlank() == true -> client?.name ?: ""
                        else -> "(loading…)"
                    }
                Text("Client: $titleName", style = MaterialTheme.typography.titleLarge)
                Text("ID: $clientId", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }

        // Tabs inside client edit
        val tabs = ClientEditTab.values().toList()
        val idx = tabs.indexOf(selectedTab).coerceAtLeast(0)
        androidx.compose.material3.TabRow(selectedTabIndex = idx) {
            tabs.forEachIndexed { i, t ->
                Tab(selected = i == idx, onClick = { selectedTab = t }, text = { Text(t.name) })
            }
        }

        Spacer(Modifier.size(12.dp))

        when (selectedTab) {
            ClientEditTab.Basic -> {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Section("Basic") {
                        LabeledField("Name", name) { name = it }
                        LabeledField("Mono Repo URL", monoRepoUrl) { monoRepoUrl = it }
                        LabeledField("Default Branch", defaultBranch) { defaultBranch = it }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButtonLike("Save") {
                                val current = client ?: return@TextButtonLike
                                scope.launch {
                                    try {
                                        loading = true
                                        val updated =
                                            repository.clients.updateClient(
                                                current.id,
                                                current.copy(
                                                    name = name,
                                                    monoRepoUrl = monoRepoUrl.ifBlank { null },
                                                    defaultBranch = defaultBranch.ifBlank { "main" },
                                                ),
                                            )
                                        client = updated
                                        error = null
                                    } catch (t: Throwable) {
                                        error = t.message
                                    } finally {
                                        loading = false
                                    }
                                }
                            }
                        }
                    }
                }
            }
            ClientEditTab.Git -> {
                var provider by remember(clientId) { mutableStateOf(client?.gitProvider ?: com.jervis.domain.git.GitProviderEnum.GITHUB) }
                var auth by remember(clientId) { mutableStateOf(client?.gitAuthType ?: com.jervis.domain.git.GitAuthTypeEnum.HTTPS_PAT) }
                var repoUrl by remember(clientId) { mutableStateOf(client?.monoRepoUrl ?: "") }
                var defBranch by remember(clientId) { mutableStateOf(client?.defaultBranch ?: "main") }
                var httpsToken by remember(clientId) { mutableStateOf("") }
                var httpsUsername by remember(clientId) { mutableStateOf("") }
                var httpsPassword by remember(clientId) { mutableStateOf("") }
                var sshPrivateKey by remember(clientId) { mutableStateOf("") }
                var sshPassphrase by remember(clientId) { mutableStateOf("") }
                var branches by remember(clientId) { mutableStateOf<List<String>>(emptyList()) }
                var detectedDefault by remember(clientId) { mutableStateOf<String?>(null) }
                var savingGit by remember(clientId) { mutableStateOf(false) }

                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Section("Git Configuration") {
                        EnumSelector("Provider", provider, com.jervis.domain.git.GitProviderEnum.entries) { it?.let { provider = it } }
                        EnumSelector("Auth", auth, com.jervis.domain.git.GitAuthTypeEnum.entries) { it?.let { auth = it } }
                        LabeledField("Repo URL", repoUrl) { repoUrl = it }
                        LabeledField("Default Branch", defBranch) { defBranch = it }
                        when (auth) {
                            com.jervis.domain.git.GitAuthTypeEnum.HTTPS_PAT, com.jervis.domain.git.GitAuthTypeEnum.HTTPS_BASIC -> {
                                LabeledField("HTTPS Token", httpsToken) { httpsToken = it }
                                LabeledField("HTTPS Username (optional)", httpsUsername) { httpsUsername = it }
                                LabeledField("HTTPS Password (optional)", httpsPassword) { httpsPassword = it }
                            }
                            com.jervis.domain.git.GitAuthTypeEnum.SSH_KEY -> {
                                LabeledField("SSH Private Key", sshPrivateKey) { sshPrivateKey = it }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButtonLike("Load from File…") {
                                        // Desktop: opens file chooser; Mobile: TODO returns null
                                        val picked = pickTextFileContent("Select SSH Private Key")
                                        if (picked != null) {
                                            sshPrivateKey = picked
                                        }
                                    }
                                }
                                LabeledField("SSH Passphrase (optional)", sshPassphrase) { sshPassphrase = it }
                            }
                            com.jervis.domain.git.GitAuthTypeEnum.NONE -> {}
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButtonLike(if (savingGit) "Saving…" else "Save") {
                                scope.launch {
                                    try {
                                        loading = true
                                        savingGit = true
                                        repository.gitConfiguration.setupGitConfiguration(
                                            clientId,
                                            com.jervis.dto.GitSetupRequestDto(
                                                gitProvider = provider,
                                                monoRepoUrl = repoUrl,
                                                defaultBranch = defBranch,
                                                gitAuthType = auth,
                                                httpsToken = httpsToken.ifBlank { null },
                                                httpsUsername = httpsUsername.ifBlank { null },
                                                httpsPassword = httpsPassword.ifBlank { null },
                                                sshPrivateKey = sshPrivateKey.ifBlank { null },
                                                sshPassphrase = sshPassphrase.ifBlank { null },
                                            ),
                                        )
                                        error = null
                                    } catch (t: Throwable) {
                                        error = t.message
                                    } finally {
                                        loading = false
                                        savingGit = false
                                    }
                                }
                            }
                            TextButtonLike(if (loading) "Testing…" else "Test Connection") {
                                scope.launch {
                                    try {
                                        loading = true
                                        repository.gitConfiguration.testConnection(
                                            clientId,
                                            com.jervis.dto.GitSetupRequestDto(
                                                gitProvider = provider,
                                                monoRepoUrl = repoUrl,
                                                defaultBranch = defBranch,
                                                gitAuthType = auth,
                                                httpsToken = httpsToken.ifBlank { null },
                                                httpsUsername = httpsUsername.ifBlank { null },
                                                httpsPassword = httpsPassword.ifBlank { null },
                                                sshPrivateKey = sshPrivateKey.ifBlank { null },
                                                sshPassphrase = sshPassphrase.ifBlank { null },
                                            ),
                                        )
                                        error = null
                                    } catch (t: Throwable) {
                                        error = t.message
                                    } finally {
                                        loading = false
                                    }
                                }
                            }
                        }
                    }

                    Section("Branches") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButtonLike("Refresh") {
                                scope.launch {
                                    try {
                                        val resp =
                                            repository.gitConfiguration.listRemoteBranches(
                                                clientId,
                                                if (repoUrl.isBlank()) null else repoUrl,
                                            )
                                        branches = resp.branches
                                        detectedDefault = resp.defaultBranch
                                        error = null
                                    } catch (t: Throwable) {
                                        error = t.message
                                    }
                                }
                            }
                            TextButtonLike("Use Detected Default") {
                                detectedDefault?.let { dd ->
                                    scope.launch {
                                        try {
                                            repository.gitConfiguration.setDefaultBranch(clientId, dd)
                                            defBranch = dd
                                        } catch (t: Throwable) {
                                            error = t.message
                                        }
                                    }
                                }
                            }
                        }
                        FlowWrap {
                            branches.forEach { b ->
                                val selected = b == defBranch
                                SelectableChip(text = b, selected = selected) { defBranch = b }
                            }
                        }
                    }
                }
            }
            ClientEditTab.Integrations -> {
                var jiraStatus by remember(clientId) { mutableStateOf<com.jervis.dto.jira.JiraSetupStatusDto?>(null) }
                var jiraProjects by remember(clientId) { mutableStateOf<List<com.jervis.dto.jira.JiraProjectRefDto>>(emptyList()) }
                var selectedJiraPrimary by remember(clientId) { mutableStateOf<String?>(null) }
                var confluenceSpaceKey by remember(clientId) { mutableStateOf("") }
                var confluenceRootPageId by remember(clientId) { mutableStateOf("") }

                LaunchedEffect(clientId) {
                    loading = true
                    try {
                        val st = repository.jiraSetup.getStatus(clientId)
                        jiraStatus = st
                        jiraProjects = if (st.connected) repository.jiraSetup.listProjects(clientId) else emptyList()
                        selectedJiraPrimary = st.primaryProject
                        repository.integrationSettings.getClientStatus(clientId).let { cs ->
                            confluenceSpaceKey = cs.confluenceSpaceKey ?: ""
                            confluenceRootPageId = cs.confluenceRootPageId ?: ""
                        }
                        error = null
                    } catch (t: Throwable) {
                        error = t.message
                        jiraProjects = emptyList()
                    } finally {
                        loading = false
                    }
                }

                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    jiraStatus?.let { st ->
                        Section("Jira Status") {
                            Text("Tenant: ${st.tenant ?: "-"}")
                            Text("Email: ${st.email ?: "-"}")
                            Text("Connected: ${st.connected}")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButtonLike("Refresh") {
                                    scope.launch {
                                        try {
                                            jiraStatus = repository.jiraSetup.getStatus(clientId)
                                        } catch (t: Throwable) {
                                            error =
                                                t.message
                                        }
                                    }
                                }
                                TextButtonLike("Test Connection") {
                                    scope.launch {
                                        try {
                                            jiraStatus = repository.jiraSetup.testConnection(clientId)
                                            error = null
                                        } catch (
                                            t: Throwable,
                                        ) {
                                            error =
                                                t.message
                                        }
                                    }
                                }
                            }
                        }

                        Section("Jira Primary Project") {
                            if (st.connected) {
                                FlowWrap {
                                    jiraProjects.forEach { jp ->
                                        val selected = jp.key == selectedJiraPrimary
                                        SelectableChip(
                                            text = jp.key,
                                            selected = selected,
                                            onClick = {
                                                selectedJiraPrimary = jp.key
                                                scope.launch {
                                                    try {
                                                        loading = true
                                                        repository.jiraSetup.setPrimaryProject(clientId, jp.key)
                                                        jiraStatus = repository.jiraSetup.getStatus(clientId)
                                                        error = null
                                                    } catch (t: Throwable) {
                                                        error = t.message
                                                    } finally {
                                                        loading = false
                                                    }
                                                }
                                            },
                                        )
                                    }
                                }
                            } else {
                                InfoBanner("Atlassian account is not connected or invalid. Autocomplete is disabled.", isWarning = true)
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
                                                    clientId = clientId,
                                                    confluenceSpaceKey = confluenceSpaceKey.ifBlank { null },
                                                    confluenceRootPageId = confluenceRootPageId.ifBlank { null },
                                                ),
                                            )
                                            error = null
                                        } catch (t: Throwable) {
                                            error = t.message
                                        } finally {
                                            loading = false
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            ClientEditTab.Email -> {
                // List-first UX with click-through edit screen
                val accounts = remember { mutableStateListOf<com.jervis.dto.email.EmailAccountDto>() }
                var emailMode by remember(clientId) { mutableStateOf<EmailMode>(EmailMode.List) }

                LaunchedEffect(clientId) {
                    loading = true
                    error =
                        try {
                            accounts.clear()
                            accounts += repository.emailAccounts.listEmailAccounts(clientId = clientId)
                            null
                        } catch (t: Throwable) {
                            t.message
                        } finally {
                            loading = false
                        }
                }

                when (val m = emailMode) {
                    EmailMode.List -> {
                        // Avoid nesting LazyColumn inside a vertically scrollable container
                        // to prevent infinite height constraints. The list itself is scrollable.
                        Column(Modifier.fillMaxWidth()) {
                            var showDeleteConfirm by remember { mutableStateOf(false) }
                            var accountIdToDelete by remember { mutableStateOf<String?>(null) }
                            Section("Accounts") {
                                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                                    items(accounts, key = { it.id ?: it.email }) { acc ->
                                        Row(
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text("${acc.displayName} <${acc.email}>")
                                                Text(
                                                    "Provider: ${acc.provider}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                TextButtonLike("Edit") { acc.id?.let { emailMode = EmailMode.Edit(it) } }
                                                IconButton(onClick = {
                                                    if (acc.id != null) {
                                                        accountIdToDelete = acc.id
                                                        showDeleteConfirm = true
                                                    }
                                                }) { Text("🗑️") }
                                            }
                                        }
                                        Divider()
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButtonLike("Refresh") {
                                        scope.launch {
                                            try {
                                                accounts.clear()
                                                accounts +=
                                                    repository.emailAccounts.listEmailAccounts(clientId = clientId)
                                                error = null
                                            } catch (
                                                t: Throwable,
                                            ) {
                                                error =
                                                    t.message
                                            }
                                        }
                                    }
                                    TextButtonLike("New Account") { emailMode = EmailMode.Create }
                                }
                            }

                            if (showDeleteConfirm && accountIdToDelete != null) {
                                AlertDialog(
                                    onDismissRequest = { showDeleteConfirm = false },
                                    title = { Text("Delete Email Account") },
                                    text = { Text("Are you sure you want to delete this email account?") },
                                    confirmButton = {
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    runCatching { repository.emailAccounts.deleteEmailAccount(requireNotNull(accountIdToDelete)) }
                                                        .onSuccess {
                                                            accounts.removeAll { it.id == accountIdToDelete }
                                                            accountIdToDelete = null
                                                            showDeleteConfirm = false
                                                            error = null
                                                        }
                                                        .onFailure { e ->
                                                            error = e.message
                                                            showDeleteConfirm = false
                                                        }
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                        ) { Text("Delete") }
                                    },
                                    dismissButton = { OutlinedButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
                                )
                            }
                        }
                    }

                    is EmailMode.Edit -> {
                        EmailAccountEditScreen(
                            repository = repository,
                            clientId = clientId,
                            accountId = m.accountId,
                            onBack = {
                                emailMode = EmailMode.List
                                scope.launch {
                                    runCatching {
                                        accounts.clear()
                                        accounts +=
                                            repository.emailAccounts.listEmailAccounts(clientId = clientId)
                                    }.onFailure { e -> error = e.message }
                                }
                            },
                            onSaved = {
                                emailMode = EmailMode.List
                                scope.launch {
                                    runCatching {
                                        accounts.clear()
                                        accounts +=
                                            repository.emailAccounts.listEmailAccounts(clientId = clientId)
                                    }
                                }
                            },
                            onDeleted = {
                                emailMode = EmailMode.List
                                scope.launch {
                                    runCatching {
                                        accounts.clear()
                                        accounts +=
                                            repository.emailAccounts.listEmailAccounts(clientId = clientId)
                                    }
                                }
                            },
                        )
                    }

                    EmailMode.Create -> {
                        EmailAccountEditScreen(
                            repository = repository,
                            clientId = clientId,
                            accountId = null,
                            onBack = { emailMode = EmailMode.List },
                            onSaved = {
                                emailMode = EmailMode.List
                                scope.launch {
                                    runCatching {
                                        accounts.clear()
                                        accounts +=
                                            repository.emailAccounts.listEmailAccounts(clientId = clientId)
                                    }
                                }
                            },
                            onDeleted = { emailMode = EmailMode.List },
                        )
                    }
                }
            }
            ClientEditTab.Projects -> {
                ClientProjectsSection(repository = repository, clientId = clientId)
            }
        }
    }
}

// Inline ClientRow edit removed in favor of clearer master–detail flow

// ————— Client-scoped Projects section (inside Client Edit) —————
@Composable
private fun ClientProjectsSection(
    repository: JervisRepository,
    clientId: String,
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val projects = remember(clientId) { mutableStateListOf<ProjectDto>() }

    var creatingNew by remember(clientId) { mutableStateOf(false) }
    var openProjectId by remember(clientId) { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember(clientId) { mutableStateOf(false) }
    var projectIdToDelete by remember(clientId) { mutableStateOf<String?>(null) }

    LaunchedEffect(clientId) {
        loading = true
        error =
            try {
                projects.clear()
                projects += repository.projects.listProjectsForClient(clientId)
                null
            } catch (t: Throwable) {
                t.message
            } finally {
                loading = false
            }
    }

    Column(Modifier.fillMaxWidth()) {
        if (loading) Text("Loading projects…")
        error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
        if (!creatingNew && openProjectId == null) {
            Section("Projects") {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(projects, key = { it.id }) { p ->
                        ProjectRow(
                            project = p,
                            onOpen = { openProjectId = p.id },
                            onDelete = {
                                projectIdToDelete = p.id
                                showDeleteConfirm = true
                            },
                            onSave = { /* no inline save in list view */ },
                        )
                        Divider()
                    }
                }
                Spacer(Modifier.size(8.dp))
                // Action: create new project (refresh is handled after edits)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButtonLike("New Project") { creatingNew = true }
                }
            }
            if (showDeleteConfirm && projectIdToDelete != null) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirm = false },
                    title = { Text("Delete Project") },
                    text = { Text("Are you sure you want to delete this project?") },
                    confirmButton = {
                        Button(
                            onClick = {
                                scope.launch {
                                    val pid = requireNotNull(projectIdToDelete)
                                    val proj = projects.firstOrNull { it.id == pid }
                                    if (proj != null) {
                                        runCatching { repository.projects.deleteProject(proj) }
                                            .onSuccess {
                                                projects.removeAll { it.id == pid }
                                                projectIdToDelete = null
                                                showDeleteConfirm = false
                                                error = null
                                            }
                                            .onFailure { e ->
                                                error = e.message
                                                showDeleteConfirm = false
                                            }
                                    } else {
                                        projectIdToDelete = null
                                        showDeleteConfirm = false
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) { Text("Delete") }
                    },
                    dismissButton = { OutlinedButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
                )
            }
        } else if (creatingNew) {
            ProjectCreateScreen(
                repository = repository,
                clientId = clientId,
                onBack = { creatingNew = false },
                onCreated = { created ->
                    creatingNew = false
                    projects += created
                    openProjectId = created.id
                },
            )
        } else {
            // Project detail sub-page
            val project = projects.firstOrNull { it.id == openProjectId }
            if (project == null) {
                Text("Project not found")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButtonLike("Back to Projects") { openProjectId = null }
                }
            } else {
                ProjectEditScreen(
                    repository = repository,
                    clientId = clientId,
                    project = project,
                    onBack = {
                        openProjectId = null
                        // Refresh projects to reflect possible changes
                        scope.launch {
                            runCatching {
                                loading = true
                                projects.clear()
                                projects += repository.projects.listProjectsForClient(clientId)
                                error = null
                            }.onFailure { e -> error = e.message }.also { loading = false }
                        }
                    },
                    onProjectSaved = { updated ->
                        val idx = projects.indexOfFirst { it.id == updated.id }
                        if (idx >= 0) projects[idx] = updated
                    },
                    onProjectDeleted = {
                        projects.removeAll { it.id == project.id }
                        openProjectId = null
                    },
                )
            }
        }
    }
}

@Composable
private fun ProjectRow(
    project: ProjectDto,
    onSave: (ProjectDto) -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .border(1.dp, Color.LightGray)
                .clickable { onOpen() }
                .padding(8.dp),
    ) {
        Text(project.name, style = MaterialTheme.typography.titleMedium)
        project.description?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = { onDelete() }) { Text("🗑️") }
        }
    }
}

// ————— Project Edit sub-screen —————
@Composable
private fun ProjectEditScreen(
    repository: JervisRepository,
    clientId: String,
    project: ProjectDto,
    onBack: () -> Unit,
    onProjectSaved: (ProjectDto) -> Unit,
    onProjectDeleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var error by remember(project.id) { mutableStateOf<String?>(null) }
    var selectedTab by remember(project.id) { mutableStateOf(ProjectEditTab.Basic) }

    // Basic fields
    var name by remember(project.id) { mutableStateOf(project.name) }
    var desc by remember(project.id) { mutableStateOf(project.description ?: "") }

    // Context labels
    var clientName by remember(clientId) { mutableStateOf("") }

    // Integration effective values for info
    var integrationInfo by remember(project.id) { mutableStateOf<IntegrationInfo?>(null) }
    var jiraStatus by remember(clientId) { mutableStateOf<JiraSetupStatusDto?>(null) }
    var availableJiraProjects by remember(clientId) { mutableStateOf<List<com.jervis.dto.jira.JiraProjectRefDto>>(emptyList()) }

    LaunchedEffect(project.id) {
        runCatching { repository.integrationSettings.getProjectStatus(project.id) }
            .onSuccess {
                integrationInfo =
                    IntegrationInfo(
                        effectiveJiraProjectKey = it.effectiveJiraProjectKey,
                        overrideJiraProjectKey = it.overrideJiraProjectKey,
                        effectiveJiraBoardId = it.effectiveJiraBoardId?.toString(),
                        overrideJiraBoardId = it.overrideJiraBoardId?.toString(),
                        effectiveConfluenceSpaceKey = it.effectiveConfluenceSpaceKey,
                        overrideConfluenceSpaceKey = it.overrideConfluenceSpaceKey,
                        effectiveConfluenceRootPageId = it.effectiveConfluenceRootPageId,
                        overrideConfluenceRootPageId = it.overrideConfluenceRootPageId,
                    )
                error = null
            }.onFailure { e -> error = e.message }
    }

    LaunchedEffect(clientId) {
        // Load client name for breadcrumb/header context
        runCatching { repository.clients.getClientById(clientId) }
            .onSuccess { clientName = it?.name ?: "" }
            .onFailure {
                // fail fast into UI below via error
                error = it.message
            }

        runCatching { repository.jiraSetup.getStatus(clientId) }
            .onSuccess { st ->
                jiraStatus = st
                availableJiraProjects =
                    if (st.connected) runCatching { repository.jiraSetup.listProjects(clientId) }.getOrDefault(emptyList()) else emptyList()
            }.onFailure { e ->
                jiraStatus = null
                availableJiraProjects = emptyList()
                error = e.message
            }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("Back to Projects") }
            Spacer(Modifier.size(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Project: ${name.ifBlank { project.name }}", style = MaterialTheme.typography.titleLarge)
                val clientLabel = if (clientName.isNotBlank()) clientName else "(loading…)"
                Text(
                    "Client: $clientLabel • Project ID: ${project.id}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }

        // Tabs
        val tabs = ProjectEditTab.values().toList()
        val idx = tabs.indexOf(selectedTab).coerceAtLeast(0)
        androidx.compose.material3.TabRow(selectedTabIndex = idx) {
            tabs.forEachIndexed { i, t ->
                Tab(selected = i == idx, onClick = { selectedTab = t }, text = { Text(t.name) })
            }
        }

        Spacer(Modifier.size(12.dp))

        when (selectedTab) {
            ProjectEditTab.Basic -> {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    var showDeleteConfirm by remember(project.id) { mutableStateOf(false) }
                    Section("Basic") {
                        LabeledField("Name", name) { name = it }
                        LabeledField("Description", desc) { desc = it }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButtonLike("Save") {
                                scope.launch {
                                    runCatching {
                                        repository.projects.saveProject(project.copy(name = name, description = desc.ifBlank { null }))
                                    }.onSuccess { saved ->
                                        onProjectSaved(saved)
                                        error = null
                                    }.onFailure { e -> error = e.message }
                                }
                            }
                            IconButton(onClick = { showDeleteConfirm = true }) { Text("🗑️") }
                        }
                        if (showDeleteConfirm) {
                            AlertDialog(
                                onDismissRequest = { showDeleteConfirm = false },
                                title = { Text("Delete Project") },
                                text = { Text("Are you sure you want to delete this project?") },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                runCatching { repository.projects.deleteProject(project) }
                                                    .onSuccess { showDeleteConfirm = false; onProjectDeleted() }
                                                    .onFailure { e -> error = e.message; showDeleteConfirm = false }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                    ) { Text("Delete") }
                                },
                                dismissButton = { OutlinedButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
                            )
                        }
                    }
                }
            }
            ProjectEditTab.Overrides -> {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    // Local breadcrumb to keep context visible while scrolling
                    val crumbClient = if (clientName.isNotBlank()) clientName else "(loading…)"
                    InfoBanner(text = "Client: $crumbClient • Project: ${name.ifBlank { project.name }}")

                    // Show effective info first
                    integrationInfo?.let { info ->
                        Section("Effective Values (read-only)") {
                            Text("Jira Project: ${info.effectiveJiraProjectKey ?: "-"}")
                            Text("Jira Board ID: ${info.effectiveJiraBoardId ?: "-"}")
                            Text("Confluence Space: ${info.effectiveConfluenceSpaceKey ?: "-"}")
                            Text("Confluence Root Page: ${info.effectiveConfluenceRootPageId ?: "-"}")
                        }
                    }

                    // Editable overrides
                    ProjectOverridesSection(
                        clientId = clientId,
                        project = project,
                        repository = repository,
                        availableJiraProjects = availableJiraProjects,
                        jiraSuggestionsEnabled = jiraStatus?.connected == true,
                        onError = { error = it },
                        initialJiraProjectKey = integrationInfo?.overrideJiraProjectKey,
                        initialJiraBoardId = integrationInfo?.overrideJiraBoardId,
                        initialConfluenceSpaceKey = integrationInfo?.overrideConfluenceSpaceKey,
                        initialConfluenceRootPageId = integrationInfo?.overrideConfluenceRootPageId,
                        onSaved = {
                            // Refresh effective values immediately after successful save
                            scope.launch {
                                runCatching { repository.integrationSettings.getProjectStatus(project.id) }
                                    .onSuccess {
                                        integrationInfo =
                                            IntegrationInfo(
                                                effectiveJiraProjectKey = it.effectiveJiraProjectKey,
                                                overrideJiraProjectKey = it.overrideJiraProjectKey,
                                                effectiveJiraBoardId = it.effectiveJiraBoardId?.toString(),
                                                overrideJiraBoardId = it.overrideJiraBoardId?.toString(),
                                                effectiveConfluenceSpaceKey = it.effectiveConfluenceSpaceKey,
                                                overrideConfluenceSpaceKey = it.overrideConfluenceSpaceKey,
                                                effectiveConfluenceRootPageId = it.effectiveConfluenceRootPageId,
                                                overrideConfluenceRootPageId = it.overrideConfluenceRootPageId,
                                            )
                                        error = null
                                    }.onFailure { e -> error = e.message }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectCreateScreen(
    repository: JervisRepository,
    clientId: String,
    onBack: () -> Unit,
    onCreated: (ProjectDto) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("Back to Projects") }
            Spacer(Modifier.size(8.dp))
            Text("Create Project", style = MaterialTheme.typography.titleLarge)
        }
        error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }

        Section("Basic") {
            LabeledField("Name", name) { name = it }
            LabeledField("Description", desc) { desc = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButtonLike("Create") {
                    scope.launch {
                        runCatching {
                            repository.projects.saveProject(
                                ProjectDto(
                                    clientId = clientId,
                                    name = name.ifBlank { "New Project" },
                                    description = desc.ifBlank { null },
                                ),
                            )
                        }.onSuccess { created ->
                            onCreated(created)
                        }.onFailure { e -> error = e.message }
                    }
                }
                TextButtonLike("Cancel") { onBack() }
            }
        }
    }
}

// — Email edit flow —
private sealed interface EmailMode {
    data object List : EmailMode

    data object Create : EmailMode

    data class Edit(
        val accountId: String,
    ) : EmailMode
}

@Composable
private fun EmailAccountEditScreen(
    repository: JervisRepository,
    clientId: String,
    accountId: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onDeleted: () -> Unit,
    projectId: String? = null,
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var displayName by remember(accountId) { mutableStateOf("") }
    var email by remember(accountId) { mutableStateOf("") }
    var provider by remember(accountId) { mutableStateOf(EmailProviderEnum.IMAP) }
    var serverHost by remember(accountId) { mutableStateOf("") }
    var serverPort by remember(accountId) { mutableStateOf("993") }
    var username by remember(accountId) { mutableStateOf("") }
    var password by remember(accountId) { mutableStateOf("") }
    var useSsl by remember(accountId) { mutableStateOf(true) }

    fun applyPreset(p: EmailProviderEnum) {
        when (p) {
            EmailProviderEnum.GMAIL -> {
                serverHost = "imap.gmail.com"
                serverPort = "993"
                useSsl = true
            }
            EmailProviderEnum.SEZNAM -> {
                serverHost = "imap.seznam.cz"
                serverPort = "993"
                useSsl = true
            }
            EmailProviderEnum.MICROSOFT -> {
                serverHost = "outlook.office365.com"
                serverPort = "993"
                useSsl = true
            }
            EmailProviderEnum.IMAP -> {
                serverHost = serverHost
                serverPort = serverPort.ifBlank { "993" }
                useSsl = true
            }
        }
    }

    LaunchedEffect(accountId) {
        if (accountId == null) return@LaunchedEffect
        loading = true
        error =
            try {
                repository.emailAccounts.getEmailAccount(accountId)?.let { acc ->
                    displayName = acc.displayName
                    email = acc.email
                    provider = acc.provider
                    serverHost = acc.serverHost ?: ""
                    serverPort = acc.serverPort?.toString() ?: "993"
                    username = acc.username ?: ""
                    useSsl = acc.useSsl
                }
                null
            } catch (t: Throwable) {
                t.message
            } finally {
                loading = false
            }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("Back to Accounts") }
            Spacer(Modifier.size(8.dp))
            Text(if (accountId == null) "New Email Account" else "Edit Email Account", style = MaterialTheme.typography.titleLarge)
        }
        error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }

        Section("Account") {
            LabeledField("Display Name", displayName) { displayName = it }
            LabeledField("Email", email) { email = it }
            EnumSelector("Provider", provider, EmailProviderEnum.entries) { new ->
                val p = new ?: provider
                provider = p
                applyPreset(p)
            }
            LabeledField("Server Host", serverHost) { serverHost = it }
            LabeledField("Server Port", serverPort) { serverPort = it }
            LabeledField("Username", username) { username = it }
            LabeledField("Password", password) { password = it }
            Toggle("Use SSL", useSsl) { useSsl = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButtonLike("Save") {
                    scope.launch {
                        try {
                            loading = true
                            if (accountId == null) {
                                repository.emailAccounts.createEmailAccount(
                                    CreateOrUpdateEmailAccountRequestDto(
                                        clientId = clientId,
                                        projectId = projectId,
                                        provider = provider,
                                        displayName = displayName,
                                        email = email,
                                        username = username.ifBlank { null },
                                        password = password.ifBlank { null },
                                        serverHost = serverHost.ifBlank { null },
                                        serverPort = serverPort.toIntOrNull(),
                                        useSsl = useSsl,
                                    ),
                                )
                            } else {
                                repository.emailAccounts.updateEmailAccount(
                                    accountId,
                                    CreateOrUpdateEmailAccountRequestDto(
                                        clientId = clientId,
                                        projectId = projectId,
                                        provider = provider,
                                        displayName = displayName,
                                        email = email,
                                        username = username.ifBlank { null },
                                        password = password.ifBlank { null },
                                        serverHost = serverHost.ifBlank { null },
                                        serverPort = serverPort.toIntOrNull(),
                                        useSsl = useSsl,
                                    ),
                                )
                            }
                            error = null
                            onSaved()
                        } catch (t: Throwable) {
                            error = t.message
                        } finally {
                            loading = false
                        }
                    }
                }
                if (accountId != null) {
                    TextButtonLike("Validate") {
                        scope.launch {
                            runCatching { repository.emailAccounts.validateEmailAccount(accountId) }
                                .onSuccess { error = null }
                                .onFailure { e -> error = e.message }
                        }
                    }
                    var showDeleteConfirm by remember(accountId) { mutableStateOf(false) }
                    IconButton(onClick = { showDeleteConfirm = true }) { Text("🗑️") }
                    if (showDeleteConfirm) {
                        AlertDialog(
                            onDismissRequest = { showDeleteConfirm = false },
                            title = { Text("Delete Email Account") },
                            text = { Text("Are you sure you want to delete this email account?") },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            runCatching { repository.emailAccounts.deleteEmailAccount(accountId) }
                                                .onSuccess { showDeleteConfirm = false; onDeleted() }
                                                .onFailure { e -> error = e.message; showDeleteConfirm = false }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) { Text("Delete") }
                            },
                            dismissButton = { OutlinedButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
                        )
                    }
                }
            }
        }
    }
}

private enum class ProjectEditTab { Basic, Overrides }

private data class IntegrationInfo(
    val effectiveJiraProjectKey: String?,
    val overrideJiraProjectKey: String?,
    val effectiveJiraBoardId: String?,
    val overrideJiraBoardId: String?,
    val effectiveConfluenceSpaceKey: String?,
    val overrideConfluenceSpaceKey: String?,
    val effectiveConfluenceRootPageId: String?,
    val overrideConfluenceRootPageId: String?,
)

@Composable
private fun ProjectOverridesSection(
    clientId: String,
    project: ProjectDto,
    repository: JervisRepository,
    availableJiraProjects: List<com.jervis.dto.jira.JiraProjectRefDto>,
    jiraSuggestionsEnabled: Boolean,
    onError: (String?) -> Unit,
    // Initial override values (null = currently not overridden)
    initialJiraProjectKey: String? = null,
    initialJiraBoardId: String? = null,
    initialConfluenceSpaceKey: String? = null,
    initialConfluenceRootPageId: String? = null,
    onSaved: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var message by remember(project.id) { mutableStateOf<String?>(null) }

    // Integration overrides: Jira/Confluence
    var useJiraProjectOverride by remember(project.id, initialJiraProjectKey) { mutableStateOf(initialJiraProjectKey != null) }
    var useJiraBoardOverride by remember(project.id, initialJiraBoardId) { mutableStateOf(initialJiraBoardId != null) }
    var useConfluenceSpaceOverride by remember(project.id, initialConfluenceSpaceKey) { mutableStateOf(initialConfluenceSpaceKey != null) }
    var useConfluenceRootPageOverride by remember(project.id, initialConfluenceRootPageId) {
        mutableStateOf(initialConfluenceRootPageId != null)
    }

    var jiraProjectKey by remember(project.id, initialJiraProjectKey) { mutableStateOf(initialJiraProjectKey ?: "") }
    var jiraBoardId by remember(project.id, initialJiraBoardId) { mutableStateOf(initialJiraBoardId ?: "") }
    var confluenceSpaceKey by remember(project.id, initialConfluenceSpaceKey) { mutableStateOf(initialConfluenceSpaceKey ?: "") }
    var confluenceRootPageId by remember(project.id, initialConfluenceRootPageId) { mutableStateOf(initialConfluenceRootPageId ?: "") }

    // Git override
    var gitRemoteUrl by remember(project.id) { mutableStateOf("") }
    var gitAuthType by remember(project.id) { mutableStateOf<com.jervis.domain.git.GitAuthTypeEnum?>(null) }
    var httpsToken by remember(project.id) { mutableStateOf("") }
    var httpsUsername by remember(project.id) { mutableStateOf("") }
    var httpsPassword by remember(project.id) { mutableStateOf("") }
    var sshPrivateKey by remember(project.id) { mutableStateOf("") }
    var sshPassphrase by remember(project.id) { mutableStateOf("") }

    // Project-scoped email accounts management (full accounts, can be multiple)
    var projectEmailAccounts by remember(project.id) { mutableStateOf<List<EmailAccountDto>>(emptyList()) }

    // Git commit identity override (username + email)
    var useCommitIdentityOverride by remember(project.id) { mutableStateOf(false) }
    var commitUserName by remember(project.id) { mutableStateOf("") }
    var commitUserEmail by remember(project.id) { mutableStateOf("") }

    Section("Integration Overrides") {
        // Jira project dropdown via chips – enabled only if Atlassian account is connected and valid
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Jira Project (override)")
            Spacer(Modifier.size(8.dp))
            Switch(checked = useJiraProjectOverride, onCheckedChange = { useJiraProjectOverride = it })
        }
        if (jiraSuggestionsEnabled && useJiraProjectOverride) {
            FlowWrap {
                availableJiraProjects.forEach { jp ->
                    val selected = jp.key == jiraProjectKey
                    Box(
                        modifier =
                            Modifier
                                .padding(4.dp)
                                .border(1.dp, if (selected) Color(0xFF3366FF) else Color.LightGray)
                                .background(if (selected) Color(0x113366FF) else Color.Transparent)
                                .clickable { jiraProjectKey = if (selected) "" else jp.key }
                                .padding(6.dp),
                    ) { Text(jp.key) }
                }
            }
        } else if (!jiraSuggestionsEnabled && useJiraProjectOverride) {
            InfoBanner("Jira suggestions disabled – connect Atlassian to enable.", isWarning = true)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Jira Board ID (override)")
            Spacer(Modifier.size(8.dp))
            Switch(checked = useJiraBoardOverride, onCheckedChange = { useJiraBoardOverride = it })
        }
        if (useJiraBoardOverride) {
            // If Atlassian connected and project selected, offer board suggestions
            var jiraBoards by remember(project.id, jiraProjectKey, jiraSuggestionsEnabled) {
                mutableStateOf<List<com.jervis.dto.jira.JiraBoardRefDto>>(emptyList())
            }
            LaunchedEffect(jiraSuggestionsEnabled, useJiraBoardOverride, jiraProjectKey) {
                if (jiraSuggestionsEnabled && useJiraBoardOverride && jiraProjectKey.isNotBlank()) {
                    runCatching { repository.jiraSetup.listBoards(clientId = clientId, projectKey = jiraProjectKey) }
                        .onSuccess { jiraBoards = it }
                        .onFailure { /* fail fast into UI below by keeping empty */ }
                } else {
                    jiraBoards = emptyList()
                }
            }

            // Reset selected board when project key changes to avoid inconsistent state
            LaunchedEffect(jiraProjectKey) {
                if (useJiraBoardOverride) {
                    jiraBoardId = ""
                }
            }

            if (jiraSuggestionsEnabled && jiraProjectKey.isNotBlank() && jiraBoards.isNotEmpty()) {
                Text("Select Jira Board", style = MaterialTheme.typography.labelLarge)
                FlowWrap {
                    jiraBoards.forEach { board ->
                        val selected = jiraBoardId == board.id.toString()
                        Box(
                            modifier =
                                Modifier
                                    .padding(4.dp)
                                    .border(1.dp, if (selected) Color(0xFF3366FF) else Color.LightGray)
                                    .background(if (selected) Color(0x113366FF) else Color.Transparent)
                                    .clickable { jiraBoardId = board.id.toString() }
                                    .padding(6.dp),
                        ) { Text("${board.id}: ${board.name}") }
                    }
                }
                Text("or enter manually:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            LabeledField("Jira Board ID", jiraBoardId) { jiraBoardId = it }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Confluence Space Key (override)")
            Spacer(Modifier.size(8.dp))
            Switch(checked = useConfluenceSpaceOverride, onCheckedChange = { useConfluenceSpaceOverride = it })
        }
        if (useConfluenceSpaceOverride) {
            LabeledField("Confluence Space Key", confluenceSpaceKey) { confluenceSpaceKey = it }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Confluence Root Page ID (override)")
            Spacer(Modifier.size(8.dp))
            Switch(checked = useConfluenceRootPageOverride, onCheckedChange = { useConfluenceRootPageOverride = it })
        }
        if (useConfluenceRootPageOverride) {
            LabeledField("Confluence Root Page ID", confluenceRootPageId) { confluenceRootPageId = it }
        }
        // Git commit identity override
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Git Commit Identity (override)")
            Spacer(Modifier.size(8.dp))
            Switch(checked = useCommitIdentityOverride, onCheckedChange = { useCommitIdentityOverride = it })
        }
        if (useCommitIdentityOverride) {
            LabeledField("User Name", commitUserName) { commitUserName = it }
            LabeledField("User Email", commitUserEmail) { commitUserEmail = it }
        }

        // Commit message template override
        var useCommitMessageOverride by remember(project.id) { mutableStateOf(false) }
        var commitMessageTemplate by remember(project.id) { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Commit Message Template (override)")
            Spacer(Modifier.size(8.dp))
            Switch(checked = useCommitMessageOverride, onCheckedChange = { useCommitMessageOverride = it })
        }
        if (useCommitMessageOverride) {
            LabeledField("Template", commitMessageTemplate) { commitMessageTemplate = it }
        }

        var savingOverrides by remember(project.id) { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButtonLike(if (savingOverrides) "Saving…" else "Save Overrides") {
                scope.launch {
                    runCatching {
                        // Apply semantics: null = unchanged, "" = clear, non-empty = set
                        val jiraProjectField =
                            when {
                                !useJiraProjectOverride -> null
                                jiraProjectKey.isBlank() -> ""
                                else -> jiraProjectKey
                            }
                        val jiraBoardField =
                            when {
                                !useJiraBoardOverride -> null
                                jiraBoardId.isBlank() -> ""
                                else -> jiraBoardId
                            }
                        val confluenceSpaceField =
                            when {
                                !useConfluenceSpaceOverride -> null
                                confluenceSpaceKey.isBlank() -> ""
                                else -> confluenceSpaceKey
                            }
                        val confluenceRootPageField =
                            when {
                                !useConfluenceRootPageOverride -> null
                                confluenceRootPageId.isBlank() -> ""
                                else -> confluenceRootPageId
                            }
                        val commitUserNameField =
                            when {
                                !useCommitIdentityOverride -> null
                                commitUserName.isBlank() -> ""
                                else -> commitUserName
                            }
                        val commitUserEmailField =
                            when {
                                !useCommitIdentityOverride -> null
                                commitUserEmail.isBlank() -> ""
                                else -> commitUserEmail
                            }
                        val commitTemplateField =
                            when {
                                !useCommitMessageOverride -> null
                                commitMessageTemplate.isBlank() -> ""
                                else -> commitMessageTemplate
                            }
                        savingOverrides = true
                        repository.integrationSettings.setProjectOverrides(
                            com.jervis.dto.integration.ProjectIntegrationOverridesDto(
                                projectId = project.id,
                                jiraProjectKey = jiraProjectField,
                                jiraBoardId = jiraBoardField,
                                confluenceSpaceKey = confluenceSpaceField,
                                confluenceRootPageId = confluenceRootPageField,
                                gitCommitUserName = commitUserNameField,
                                gitCommitUserEmail = commitUserEmailField,
                                commitMessageTemplate = commitTemplateField,
                            ),
                        )
                    }.onSuccess {
                        message = "Integration overrides saved"
                        onError(null)
                        onSaved()
                    }.onFailure { e ->
                        onError(e.message)
                        message = null
                    }.also { savingOverrides = false }
                }
            }
            if (savingOverrides) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
        message?.let { Text(it) }
    }

    // Separate section: Project Email Accounts (full accounts CRUD for this project)
    run {
        var projEmailMode by remember(project.id) { mutableStateOf<EmailMode>(EmailMode.List) }
        LaunchedEffect(clientId, project.id) {
            runCatching { repository.emailAccounts.listEmailAccounts(clientId = clientId, projectId = project.id) }
                .onSuccess { projectEmailAccounts = it }
        }

        when (val mode = projEmailMode) {
            EmailMode.List -> {
                Section("Project Email Accounts") {
                    var showDeleteConfirm by remember(project.id) { mutableStateOf(false) }
                    var accountIdToDelete by remember(project.id) { mutableStateOf<String?>(null) }
                    if (projectEmailAccounts.isEmpty()) {
                        InfoBanner(
                            "No project email accounts yet. You can create multiple accounts scoped to this project.",
                            isWarning = true,
                        )
                    } else {
                        // Do NOT use LazyColumn here because the parent screen already scrolls vertically.
                        // Using a LazyColumn inside a vertically scrollable parent causes infinite height constraints.
                        Column(modifier = Modifier.fillMaxWidth()) {
                            projectEmailAccounts.forEach { acc ->
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text("${acc.displayName} <${acc.email}>")
                                        Text(
                                            "Provider: ${acc.provider}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButtonLike("Edit") { acc.id?.let { projEmailMode = EmailMode.Edit(it) } }
                                        IconButton(onClick = {
                                            acc.id?.let {
                                                accountIdToDelete = it
                                                showDeleteConfirm = true
                                            }
                                        }) { Text("🗑️") }
                                    }
                                }
                                Divider()
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButtonLike("Refresh") {
                            scope.launch {
                                runCatching { repository.emailAccounts.listEmailAccounts(clientId = clientId, projectId = project.id) }
                                    .onSuccess { projectEmailAccounts = it }
                            }
                        }
                        TextButtonLike("New Account") { projEmailMode = EmailMode.Create }
                    }

                    if (showDeleteConfirm && accountIdToDelete != null) {
                        AlertDialog(
                            onDismissRequest = { showDeleteConfirm = false },
                            title = { Text("Delete Email Account") },
                            text = { Text("Are you sure you want to delete this email account?") },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            val id = requireNotNull(accountIdToDelete)
                                            runCatching { repository.emailAccounts.deleteEmailAccount(id) }
                                                .onSuccess {
                                                    runCatching { repository.emailAccounts.listEmailAccounts(clientId = clientId, projectId = project.id) }
                                                        .onSuccess { projectEmailAccounts = it }
                                                    accountIdToDelete = null
                                                    showDeleteConfirm = false
                                                }
                                                .onFailure { e ->
                                                    onError(e.message)
                                                    showDeleteConfirm = false
                                                }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) { Text("Delete") }
                            },
                            dismissButton = { OutlinedButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
                        )
                    }
                }
            }
            is EmailMode.Edit -> {
                EmailAccountEditScreen(
                    repository = repository,
                    clientId = clientId,
                    accountId = mode.accountId,
                    onBack = {
                        projEmailMode = EmailMode.List
                        scope.launch {
                            runCatching { repository.emailAccounts.listEmailAccounts(clientId = clientId, projectId = project.id) }
                                .onSuccess { projectEmailAccounts = it }
                        }
                    },
                    onSaved = {
                        projEmailMode = EmailMode.List
                        scope.launch {
                            runCatching { repository.emailAccounts.listEmailAccounts(clientId = clientId, projectId = project.id) }
                                .onSuccess { projectEmailAccounts = it }
                        }
                    },
                    onDeleted = {
                        projEmailMode = EmailMode.List
                        scope.launch {
                            runCatching { repository.emailAccounts.listEmailAccounts(clientId = clientId, projectId = project.id) }
                                .onSuccess { projectEmailAccounts = it }
                        }
                    },
                    projectId = project.id,
                )
            }
            EmailMode.Create -> {
                EmailAccountEditScreen(
                    repository = repository,
                    clientId = clientId,
                    accountId = null,
                    onBack = { projEmailMode = EmailMode.List },
                    onSaved = {
                        projEmailMode = EmailMode.List
                        scope.launch {
                            runCatching { repository.emailAccounts.listEmailAccounts(clientId = clientId, projectId = project.id) }
                                .onSuccess { projectEmailAccounts = it }
                        }
                    },
                    onDeleted = { projEmailMode = EmailMode.List },
                    projectId = project.id,
                )
            }
        }
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButtonLike("Load from File…") {
                        val picked = pickTextFileContent("Select SSH Private Key")
                        if (picked != null) {
                            sshPrivateKey = picked
                        }
                    }
                }
                LabeledField("SSH Passphrase (optional)", sshPassphrase) { sshPassphrase = it }
            }
            com.jervis.domain.git.GitAuthTypeEnum.NONE, null -> {}
        }
        var savingProjectGit by remember(project.id) { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButtonLike(if (savingProjectGit) "Saving…" else "Save Git Override") {
                scope.launch {
                    runCatching {
                        savingProjectGit = true
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
                            ),
                        )
                    }.onSuccess {
                        message = "Git override saved"
                        onError(null)
                    }.onFailure { e ->
                        onError(e.message)
                        message = null
                    }.also { savingProjectGit = false }
                }
            }
            if (savingProjectGit) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
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
        error =
            try {
                clients.clear()
                clients += repository.clients.listClients()
                null
            } catch (
                t: Throwable,
            ) {
                t.message
            } finally {
                loading =
                    false
            }
    }

    LaunchedEffect(selectedClientId) {
        if (selectedClientId == null) return@LaunchedEffect
        loading = true
        error =
            try {
                accounts.clear()
                accounts += repository.emailAccounts.listEmailAccounts(clientId = selectedClientId)
                null
            } catch (
                t: Throwable,
            ) {
                t.message
            } finally {
                loading =
                    false
            }
    }

    Column(Modifier.fillMaxSize()) {
        error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
        Section("Select Client") {
            FlowWrap {
                clients.forEach { c ->
                    val selected = c.id == selectedClientId
                    Box(
                        modifier =
                            Modifier
                                .padding(4.dp)
                                .border(1.dp, if (selected) Color(0xFF3366FF) else Color.LightGray)
                                .background(if (selected) Color(0x113366FF) else Color.Transparent)
                                .clickable { selectedClientId = c.id }
                                .padding(6.dp),
                    ) { Text(c.name) }
                }
            }
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
                            val id = selectedClientId ?: return@launch
                            val created =
                                repository.emailAccounts.createEmailAccount(
                                    CreateOrUpdateEmailAccountRequestDto(
                                        clientId = id,
                                        provider = provider,
                                        displayName = displayName,
                                        email = email,
                                        username = username.ifBlank { null },
                                        password = password.ifBlank { null },
                                        serverHost = serverHost.ifBlank { null },
                                        serverPort = serverPort.toIntOrNull(),
                                        useSsl = useSsl,
                                    ),
                                )
                            accounts += created
                            displayName = ""
                            email = ""
                            serverHost = ""
                            serverPort = "993"
                            username = ""
                            password = ""
                            useSsl = true
                            error = null
                        } catch (t: Throwable) {
                            error = t.message
                        } finally {
                            loading = false
                        }
                    }
                }
            }

            Section("Accounts") {
                var showDeleteConfirm by remember { mutableStateOf(false) }
                var accountIdToDelete by remember { mutableStateOf<String?>(null) }
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(accounts, key = { it.id ?: it.email }) { acc ->
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp)
                                    .border(1.dp, Color.LightGray)
                                    .padding(8.dp),
                        ) {
                            Text("${acc.displayName} <${acc.email}>")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButtonLike("Validate") {
                                    scope.launch {
                                        try {
                                            acc.id?.let { repository.emailAccounts.validateEmailAccount(it) }
                                            error = null
                                        } catch (t: Throwable) {
                                            error = t.message
                                        }
                                    }
                                }
                                IconButton(onClick = {
                                    acc.id?.let {
                                        accountIdToDelete = it
                                        showDeleteConfirm = true
                                    }
                                }) { Text("🗑️") }
                            }
                        }
                    }
                }
                if (showDeleteConfirm && accountIdToDelete != null) {
                    AlertDialog(
                        onDismissRequest = { showDeleteConfirm = false },
                        title = { Text("Delete Email Account") },
                        text = { Text("Are you sure you want to delete this email account?") },
                        confirmButton = {
                            Button(
                                onClick = {
                                    scope.launch {
                                        val id = requireNotNull(accountIdToDelete)
                                        runCatching { repository.emailAccounts.deleteEmailAccount(id) }
                                            .onSuccess {
                                                accounts.removeAll { it.id == id }
                                                accountIdToDelete = null
                                                showDeleteConfirm = false
                                            }
                                            .onFailure { e ->
                                                error = e.message
                                                showDeleteConfirm = false
                                            }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) { Text("Delete") }
                        },
                        dismissButton = { OutlinedButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
                    )
                }
            }
        }
    }
}

// ————— Common small UI helpers (foundation-only) —————
@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(8.dp))
            content()
        }
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
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowWrap {
            options.forEach { opt ->
                val selected = opt == value
                Box(
                    modifier =
                        Modifier
                            .padding(4.dp)
                            .border(1.dp, if (selected) Color(0xFF3366FF) else Color.LightGray)
                            .background(if (selected) Color(0x113366FF) else Color.Transparent)
                            .clickable { onChange(if (selected) null else opt) }
                            .padding(6.dp),
                ) { Text(opt.name) }
            }
        }
    }
}

@Composable
private fun Toggle(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        Spacer(Modifier.size(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FlowWrap(content: @Composable () -> Unit) {
    // FlowRow for proper wrapping of selection chips on small screens
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

@Composable
private fun SelectableChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) Color(0xFF3366FF) else Color.LightGray
    val bgColor = if (selected) Color(0x113366FF) else Color.Transparent
    Box(
        modifier =
            Modifier
                .border(1.dp, borderColor)
                .background(bgColor)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(text)
    }
}

@Composable
private fun InfoBanner(
    text: String,
    isWarning: Boolean = false,
    isError: Boolean = false,
) {
    val bg =
        when {
            isError -> MaterialTheme.colorScheme.errorContainer
            isWarning -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.primaryContainer
        }
    val fg =
        when {
            isError -> MaterialTheme.colorScheme.onErrorContainer
            isWarning -> MaterialTheme.colorScheme.onTertiaryContainer
            else -> MaterialTheme.colorScheme.onPrimaryContainer
        }
    Card(colors = CardDefaults.cardColors(containerColor = bg)) {
        Text(
            text = text,
            color = fg,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(8.dp),
        )
    }
}
