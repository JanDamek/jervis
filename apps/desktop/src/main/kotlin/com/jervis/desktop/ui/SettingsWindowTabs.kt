@file:OptIn(ExperimentalMaterial3Api::class)

package com.jervis.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.repository.JervisRepository
import kotlinx.coroutines.launch

@Composable
fun SettingsWindow(
    repository: JervisRepository,
    initialTabIndex: Int = 0,
) {
    var selectedTabIndex by remember { mutableStateOf(initialTabIndex) }
    var clients by remember { mutableStateOf<List<com.jervis.dto.ClientDto>>(emptyList()) }
    var selectedClient by remember { mutableStateOf<com.jervis.dto.ClientDto?>(null) }
    var isLoadingClients by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var clientDropdownExpanded by remember { mutableStateOf(false) }
    var showClientManagement by remember { mutableStateOf(false) }
    var showProjectManagement by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val tabs = listOf("Settings", "Git", "Atlassian", "Jira", "Confluence", "Email")

    // Load clients on mount
    LaunchedEffect(Unit) {
        isLoadingClients = true
        try {
            clients = repository.clients.listClients()
            if (clients.isNotEmpty()) {
                selectedClient = clients[0]
            }
        } catch (e: Exception) {
            errorMessage = "Failed to load clients: ${e.message}"
        } finally {
            isLoadingClients = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            // Client and Project selector
            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Client & Project",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )

                        ExposedDropdownMenuBox(
                            expanded = clientDropdownExpanded,
                            onExpandedChange = { clientDropdownExpanded = !clientDropdownExpanded },
                        ) {
                            OutlinedTextField(
                                value = selectedClient?.name ?: "No clients available",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Client") },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = clientDropdownExpanded)
                                },
                                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true).fillMaxWidth(),
                                enabled = !isLoadingClients && clients.isNotEmpty(),
                            )
                            ExposedDropdownMenu(
                                expanded = clientDropdownExpanded,
                                onDismissRequest = { clientDropdownExpanded = false },
                            ) {
                                clients.forEach { client ->
                                    DropdownMenuItem(
                                        text = { Text(client.name) },
                                        onClick = {
                                            selectedClient = client
                                            clientDropdownExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }

                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        OutlinedButton(
                            onClick = { showClientManagement = true },
                            modifier = Modifier.width(140.dp),
                        ) {
                            Icon(Icons.Default.Build, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Clients")
                        }
                        OutlinedButton(
                            onClick = { showProjectManagement = true },
                            enabled = selectedClient != null,
                            modifier = Modifier.width(140.dp),
                        ) {
                            Icon(Icons.Default.Folder, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Projects")
                        }
                    }
                }
            }

            if (errorMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                ) {
                    Text(
                        text = errorMessage!!,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Tabs
            PrimaryTabRow(selectedTabIndex = selectedTabIndex) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(title) },
                    )
                }
            }

            // Tab content
            when (selectedTabIndex) {
                0 ->
                    if (selectedClient != null) {
                        GeneralSettingsTab(selectedClient!!, repository, scope)
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Select a client to view settings", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                1 ->
                    if (selectedClient != null) {
                        GitSettingsTab(selectedClient!!, repository, scope)
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Select a client to configure Git", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                2 ->
                    if (selectedClient != null) {
                        AtlassianSettingsTab(selectedClient!!, repository, scope)
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Select a client to configure Atlassian", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                3 ->
                    if (selectedClient != null) {
                        JiraSettingsTab(selectedClient!!, repository, scope)
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Select a client to configure Jira", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                4 ->
                    if (selectedClient != null) {
                        ConfluenceSettingsTab(selectedClient!!, repository, scope)
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Select a client to configure Confluence", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                5 ->
                    if (selectedClient != null) {
                        EmailSettingsTab(selectedClient!!, repository, scope)
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Select a client to configure Email", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
            }
        }
    }

    // Client Management Dialog
    if (showClientManagement) {
        ClientManagementDialog(
            clients = clients,
            onDismiss = { showClientManagement = false },
            onClientsChanged = { newClients ->
                clients = newClients
                if (selectedClient == null && newClients.isNotEmpty()) {
                    selectedClient = newClients.first()
                } else if (selectedClient != null && newClients.none { it.id == selectedClient!!.id }) {
                    selectedClient = newClients.firstOrNull()
                }
            },
            onSelectClient = { sel ->
                selectedClient = sel
                showClientManagement = false
            },
            repository = repository,
        )
    }

    // Project Management Dialog
    if (showProjectManagement && selectedClient != null) {
        ProjectManagementDialog(
            client = selectedClient!!,
            onDismiss = { showProjectManagement = false },
            repository = repository,
        )
    }
}

@Composable
private fun GeneralSettingsTab(
    client: com.jervis.dto.ClientDto,
    repository: JervisRepository,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    var clientName by remember { mutableStateOf(client.name) }
    var isSaving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("General Client Settings", style = MaterialTheme.typography.titleMedium)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Client Information", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Client ID:", style = MaterialTheme.typography.bodyMedium)
                    Text(client.id, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Git Provider:", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        client.gitProvider?.name ?: "Not configured",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Default Branch:", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        client.defaultBranch ?: "Not configured",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        HorizontalDivider()

        Text("Edit Client Name", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = clientName,
            onValueChange = { clientName = it },
            label = { Text("Client Name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    scope.launch {
                        isSaving = true
                        saveMessage = null
                        try {
                            repository.clients.updateClient(client.id, client.copy(name = clientName))
                            saveMessage = "Client name updated successfully"
                        } catch (e: Exception) {
                            saveMessage = "Failed to update: ${e.message}"
                        } finally {
                            isSaving = false
                        }
                    }
                },
                enabled = !isSaving && clientName.isNotBlank() && clientName != client.name,
                modifier = Modifier.weight(1f),
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Update Name")
                }
            }

            OutlinedButton(
                onClick = { clientName = client.name },
                enabled = !isSaving && clientName != client.name,
                modifier = Modifier.weight(1f),
            ) {
                Text("Reset")
            }
        }

        if (saveMessage != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor =
                            if (saveMessage!!.contains("Failed")) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                    ),
            ) {
                Text(
                    text = saveMessage!!,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun GitSettingsTab(
    client: com.jervis.dto.ClientDto,
    repository: JervisRepository,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val gitProvider = remember(client.id) { mutableStateOf(client.gitProvider?.name ?: "NONE") }
    val monoRepoUrl = remember(client.id) { mutableStateOf(client.monoRepoUrl ?: "") }
    val defaultBranch = remember(client.id) { mutableStateOf(client.defaultBranch ?: "main") }
    val authType = remember(client.id) { mutableStateOf("SSH_KEY") }

    // SSH fields
    val sshPrivateKey = remember(client.id) { mutableStateOf("") }
    val sshPublicKey = remember(client.id) { mutableStateOf("") }
    val sshPassphrase = remember(client.id) { mutableStateOf("") }

    // HTTPS PAT fields
    val httpsToken = remember(client.id) { mutableStateOf("") }

    // HTTPS Basic fields
    val httpsUsername = remember(client.id) { mutableStateOf("") }
    val httpsPassword = remember(client.id) { mutableStateOf("") }

    // Git config
    val gitUserName = remember(client.id) { mutableStateOf(client.gitConfig?.gitUserName ?: "") }
    val gitUserEmail = remember(client.id) { mutableStateOf(client.gitConfig?.gitUserEmail ?: "") }

    // GPG fields
    val gpgPrivateKey = remember(client.id) { mutableStateOf("") }
    val gpgPublicKey = remember(client.id) { mutableStateOf("") }
    val gpgKeyId = remember(client.id) { mutableStateOf(client.gitConfig?.gpgKeyId ?: "") }
    val gpgPassphrase = remember(client.id) { mutableStateOf("") }

    // Workflow options
    val requireGpgSign = remember(client.id) { mutableStateOf(client.gitConfig?.requireGpgSign ?: false) }
    val requireLinearHistory = remember(client.id) { mutableStateOf(client.gitConfig?.requireLinearHistory ?: false) }
    val conventionalCommits = remember(client.id) { mutableStateOf(client.gitConfig?.conventionalCommits ?: true) }
    val commitMessageTemplate = remember(client.id) { mutableStateOf(client.gitConfig?.commitMessageTemplate ?: "") }

    var isSaving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }

    // Load Git credentials when client changes
    LaunchedEffect(client.id) {
        try {
            val credentials = repository.gitConfiguration.getGitCredentials(client.id) ?: return@LaunchedEffect

            // Load credential values (authType is determined by which fields are populated)
            credentials.sshPrivateKey?.let { sshPrivateKey.value = it }
            credentials.sshPublicKey?.let { sshPublicKey.value = it }
            credentials.sshPassphrase?.let { sshPassphrase.value = it }
            credentials.httpsToken?.let { httpsToken.value = it }
            credentials.httpsUsername?.let { httpsUsername.value = it }
            credentials.httpsPassword?.let { httpsPassword.value = it }
            credentials.gpgPrivateKey?.let { gpgPrivateKey.value = it }
            credentials.gpgPublicKey?.let { gpgPublicKey.value = it }
            credentials.gpgPassphrase?.let { gpgPassphrase.value = it }

            // Infer auth type from populated fields
            when {
                credentials.sshPrivateKey != null -> authType.value = "SSH_KEY"
                credentials.httpsToken != null -> authType.value = "HTTPS_PAT"
                credentials.httpsUsername != null -> authType.value = "HTTPS_BASIC"
            }
        } catch (e: Exception) {
            // Silently fail - credentials might not be set yet
        }
    }

    val providerOptions = listOf("NONE", "GITHUB", "GITLAB", "BITBUCKET", "AZURE_DEVOPS", "GITEA", "CUSTOM")
    val authTypeOptions = listOf("SSH_KEY", "HTTPS_PAT", "HTTPS_BASIC", "NONE")

    var providerDropdownExpanded by remember { mutableStateOf(false) }
    var authTypeDropdownExpanded by remember { mutableStateOf(false) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Repository Section
        Text("Repository Configuration", style = MaterialTheme.typography.titleMedium)

        ExposedDropdownMenuBox(
            expanded = providerDropdownExpanded,
            onExpandedChange = { providerDropdownExpanded = !providerDropdownExpanded },
        ) {
            OutlinedTextField(
                value = gitProvider.value,
                onValueChange = {},
                readOnly = true,
                label = { Text("Git Provider *") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerDropdownExpanded) },
                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true).fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = providerDropdownExpanded,
                onDismissRequest = { providerDropdownExpanded = false },
            ) {
                providerOptions.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            gitProvider.value = option
                            providerDropdownExpanded = false
                        },
                    )
                }
            }
        }

        if (gitProvider.value != "NONE") {
            OutlinedTextField(
                value = monoRepoUrl.value,
                onValueChange = { monoRepoUrl.value = it },
                label = { Text("Mono-Repo URL (Optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedTextField(
                value = defaultBranch.value,
                onValueChange = { defaultBranch.value = it },
                label = { Text("Default Branch *") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            HorizontalDivider()

            // Authentication Section
            Text("Authentication", style = MaterialTheme.typography.titleMedium)

            ExposedDropdownMenuBox(
                expanded = authTypeDropdownExpanded,
                onExpandedChange = { authTypeDropdownExpanded = !authTypeDropdownExpanded },
            ) {
                OutlinedTextField(
                    value = authType.value,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Auth Type") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = authTypeDropdownExpanded) },
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true).fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = authTypeDropdownExpanded,
                    onDismissRequest = { authTypeDropdownExpanded = false },
                ) {
                    authTypeOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                authType.value = option
                                authTypeDropdownExpanded = false
                            },
                        )
                    }
                }
            }

            when (authType.value) {
                "SSH_KEY" -> {
                    OutlinedTextField(
                        value = sshPrivateKey.value,
                        onValueChange = { sshPrivateKey.value = it },
                        label = { Text("SSH Private Key *") },
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                        minLines = 6,
                        maxLines = 10,
                    )

                    OutlinedTextField(
                        value = sshPublicKey.value,
                        onValueChange = { sshPublicKey.value = it },
                        label = { Text("SSH Public Key") },
                        modifier = Modifier.fillMaxWidth().height(100.dp),
                        minLines = 3,
                        maxLines = 5,
                    )

                    OutlinedTextField(
                        value = sshPassphrase.value,
                        onValueChange = { sshPassphrase.value = it },
                        label = { Text("Passphrase") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                "HTTPS_PAT" -> {
                    OutlinedTextField(
                        value = httpsToken.value,
                        onValueChange = { httpsToken.value = it },
                        label = { Text("Personal Access Token *") },
                        modifier = Modifier.fillMaxWidth().height(100.dp),
                        minLines = 3,
                        maxLines = 5,
                    )
                }
                "HTTPS_BASIC" -> {
                    OutlinedTextField(
                        value = httpsUsername.value,
                        onValueChange = { httpsUsername.value = it },
                        label = { Text("Username *") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    OutlinedTextField(
                        value = httpsPassword.value,
                        onValueChange = { httpsPassword.value = it },
                        label = { Text("Password *") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }

            HorizontalDivider()

            // Global Settings Section
            Text("Global Git Settings", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = gitUserName.value,
                onValueChange = { gitUserName.value = it },
                label = { Text("Git User Name *") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedTextField(
                value = gitUserEmail.value,
                onValueChange = { gitUserEmail.value = it },
                label = { Text("Git User Email *") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            HorizontalDivider()

            // GPG Signing Section
            Text("GPG Signing (Optional)", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = gpgPrivateKey.value,
                onValueChange = { gpgPrivateKey.value = it },
                label = { Text("GPG Private Key") },
                modifier = Modifier.fillMaxWidth().height(150.dp),
                minLines = 6,
                maxLines = 10,
            )

            OutlinedTextField(
                value = gpgPublicKey.value,
                onValueChange = { gpgPublicKey.value = it },
                label = { Text("GPG Public Key") },
                modifier = Modifier.fillMaxWidth().height(100.dp),
                minLines = 3,
                maxLines = 5,
            )

            OutlinedTextField(
                value = gpgKeyId.value,
                onValueChange = { gpgKeyId.value = it },
                label = { Text("GPG Key ID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedTextField(
                value = gpgPassphrase.value,
                onValueChange = { gpgPassphrase.value = it },
                label = { Text("GPG Passphrase") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            HorizontalDivider()

            // Workflow Section
            Text("Workflow Options", style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = requireGpgSign.value,
                    onCheckedChange = { requireGpgSign.value = it },
                )
                Text("Require GPG Signatures")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = requireLinearHistory.value,
                    onCheckedChange = { requireLinearHistory.value = it },
                )
                Text("Require Linear History")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = conventionalCommits.value,
                    onCheckedChange = { conventionalCommits.value = it },
                )
                Text("Use Conventional Commits")
            }

            OutlinedTextField(
                value = commitMessageTemplate.value,
                onValueChange = { commitMessageTemplate.value = it },
                label = { Text("Commit Message Template") },
                modifier = Modifier.fillMaxWidth().height(100.dp),
                minLines = 3,
                maxLines = 5,
            )
        }

        // Save/Cancel buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    scope.launch {
                        isSaving = true
                        saveMessage = null
                        try {
                            val clientId = client.id
                            require(clientId.isNotEmpty()) { "Client ID is required" }

                            val providerEnum =
                                com.jervis.domain.git.GitProviderEnum
                                    .valueOf(gitProvider.value)
                            val authEnum =
                                com.jervis.domain.git.GitAuthTypeEnum
                                    .valueOf(authType.value)

                            val request =
                                com.jervis.dto.GitSetupRequestDto(
                                    gitProvider = providerEnum,
                                    monoRepoUrl = monoRepoUrl.value,
                                    defaultBranch = defaultBranch.value,
                                    gitAuthType = authEnum,
                                    sshPrivateKey =
                                        if (authEnum ==
                                            com.jervis.domain.git.GitAuthTypeEnum.SSH_KEY
                                        ) {
                                            sshPrivateKey.value.ifBlank { null }
                                        } else {
                                            null
                                        },
                                    sshPublicKey =
                                        if (authEnum ==
                                            com.jervis.domain.git.GitAuthTypeEnum.SSH_KEY
                                        ) {
                                            sshPublicKey.value.ifBlank { null }
                                        } else {
                                            null
                                        },
                                    sshPassphrase =
                                        if (authEnum ==
                                            com.jervis.domain.git.GitAuthTypeEnum.SSH_KEY
                                        ) {
                                            sshPassphrase.value.ifBlank { null }
                                        } else {
                                            null
                                        },
                                    httpsToken =
                                        if (authEnum ==
                                            com.jervis.domain.git.GitAuthTypeEnum.HTTPS_PAT
                                        ) {
                                            httpsToken.value.ifBlank { null }
                                        } else {
                                            null
                                        },
                                    httpsUsername =
                                        if (authEnum ==
                                            com.jervis.domain.git.GitAuthTypeEnum.HTTPS_BASIC
                                        ) {
                                            httpsUsername.value.ifBlank { null }
                                        } else {
                                            null
                                        },
                                    httpsPassword =
                                        if (authEnum ==
                                            com.jervis.domain.git.GitAuthTypeEnum.HTTPS_BASIC
                                        ) {
                                            httpsPassword.value.ifBlank { null }
                                        } else {
                                            null
                                        },
                                    gpgPrivateKey = gpgPrivateKey.value.ifBlank { null },
                                    gpgPublicKey = gpgPublicKey.value.ifBlank { null },
                                    gpgKeyId = gpgKeyId.value.ifBlank { null },
                                    gpgPassphrase = gpgPassphrase.value.ifBlank { null },
                                    gitConfig =
                                        com.jervis.dto.GitConfigDto(
                                            gitUserName = gitUserName.value.ifBlank { null },
                                            gitUserEmail = gitUserEmail.value.ifBlank { null },
                                            commitMessageTemplate = commitMessageTemplate.value.ifBlank { null },
                                            requireGpgSign = requireGpgSign.value,
                                            gpgKeyId = gpgKeyId.value.ifBlank { null },
                                            requireLinearHistory = requireLinearHistory.value,
                                            conventionalCommits = conventionalCommits.value,
                                        ),
                                )

                            repository.gitConfiguration.setupGitConfiguration(clientId, request)
                            saveMessage = "Saved successfully"
                        } catch (e: Exception) {
                            saveMessage = "Failed to save: ${e.message}"
                        } finally {
                            isSaving = false
                        }
                    }
                },
                enabled =
                    !isSaving && gitProvider.value != "NONE" && monoRepoUrl.value.isNotBlank() && defaultBranch.value.isNotBlank() &&
                        when (authType.value) {
                            "SSH_KEY" -> sshPrivateKey.value.isNotBlank()
                            "HTTPS_PAT" -> httpsToken.value.isNotBlank()
                            "HTTPS_BASIC" -> httpsUsername.value.isNotBlank() && httpsPassword.value.isNotBlank()
                            else -> true
                        },
                modifier = Modifier.weight(1f),
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Save")
                }
            }

            OutlinedButton(
                onClick = {
                    // Reset to original values
                    gitProvider.value = client.gitProvider?.name ?: "NONE"
                    monoRepoUrl.value = client.monoRepoUrl ?: ""
                    defaultBranch.value = client.defaultBranch ?: "main"
                    gitUserName.value = client.gitConfig?.gitUserName ?: ""
                    gitUserEmail.value = client.gitConfig?.gitUserEmail ?: ""
                    saveMessage = null
                },
                enabled = !isSaving,
                modifier = Modifier.weight(1f),
            ) {
                Text("Cancel")
            }
        }

        if (saveMessage != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor =
                            if (saveMessage!!.contains("Failed") || saveMessage!!.contains("not yet")) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                    ),
            ) {
                Text(
                    text = saveMessage!!,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun AtlassianSettingsTab(
    client: com.jervis.dto.ClientDto,
    repository: JervisRepository,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    var tenant by remember(client.id) { mutableStateOf("") }
    var email by remember(client.id) { mutableStateOf("") }
    var apiToken by remember(client.id) { mutableStateOf("") }
    var statusText by remember { mutableStateOf("Loading status...") }
    var isSaving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }

    // Load current Atlassian connection status
    LaunchedEffect(client.id) {
        val clientId = client.id
        if (clientId.isNotEmpty()) {
            runCatching { repository.jiraSetup.getStatus(clientId) }
                .onSuccess { status ->
                    val connected = if (status.connected) "Connected" else "Not connected"
                    statusText = "$connected to ${status.tenant ?: "?"}"
                    if (!status.tenant.isNullOrBlank()) tenant = status.tenant ?: ""
                    if (!status.email.isNullOrBlank()) email = status.email ?: ""
                    if (!status.apiToken.isNullOrBlank()) apiToken = status.apiToken ?: ""
                }.onFailure { e ->
                    statusText = "Failed to load status: ${'$'}{e.message}"
                }
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Atlassian Cloud Connection", style = MaterialTheme.typography.titleMedium)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
        ) {
            Text(
                text = statusText,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Text(
            "Connection Details",
            style = MaterialTheme.typography.titleSmall,
        )

        OutlinedTextField(
            value = tenant,
            onValueChange = { tenant = it },
            label = { Text("Tenant (e.g., your-domain.atlassian.net)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            supportingText = { Text("Enter host only, no https://, no path") },
        )

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        OutlinedTextField(
            value = apiToken,
            onValueChange = { apiToken = it },
            label = { Text("API Token") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            supportingText = { Text("Starting page for project docs") },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    scope.launch {
                        isSaving = true
                        saveMessage = null
                        try {
                            val clientId = client.id
                            require(clientId.isNotEmpty()) { "Client ID is required" }
                            // First test the token
                            val test = repository.jiraSetup.testApiToken(tenant = tenant, email = email, apiToken = apiToken)
                            if (!test.success) {
                                saveMessage = test.message ?: "Token test failed"
                            } else {
                                val status =
                                    repository.jiraSetup.saveApiToken(
                                        clientId = clientId,
                                        tenant = tenant,
                                        email = email,
                                        apiToken = apiToken,
                                    )
                                val connected = if (status.connected) "Connected" else "Not connected"
                                statusText = "$connected to ${status.tenant ?: "?"}"
                                saveMessage = test.message ?: "Saved successfully"
                            }
                        } catch (e: Exception) {
                            saveMessage = "Failed to save: ${e.message}"
                        } finally {
                            isSaving = false
                        }
                    }
                },
                enabled = !isSaving && tenant.isNotBlank() && email.isNotBlank() && apiToken.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Test & Save")
                }
            }

            OutlinedButton(
                onClick = {
                    tenant = ""
                    email = ""
                    apiToken = ""
                    saveMessage = null
                },
                enabled = !isSaving,
                modifier = Modifier.weight(1f),
            ) {
                Text("Clear")
            }
        }

        TextButton(
            onClick = {
                // TODO: Open browser to Atlassian API token page
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Where to get API token")
        }

        if (saveMessage != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor =
                            if (saveMessage!!.contains("Failed") || saveMessage!!.contains("not yet")) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                    ),
            ) {
                Text(
                    text = saveMessage!!,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun JiraSettingsTab(
    client: com.jervis.dto.ClientDto,
    repository: JervisRepository,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    var statusText by remember { mutableStateOf("Loading...") }
    var projects by remember { mutableStateOf<List<com.jervis.dto.jira.JiraProjectRefDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    // Load available Jira projects
    LaunchedEffect(client.id) {
        isLoading = true
        runCatching { repository.jiraSetup.listProjects(client.id) }
            .onSuccess { projectList ->
                projects = projectList
                statusText = "Found ${projectList.size} Jira projects"
            }.onFailure { e ->
                statusText = "Failed to load projects: ${e.message}"
            }
        isLoading = false
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Jira Project Settings", style = MaterialTheme.typography.titleMedium)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
        ) {
            Text(
                text = statusText,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Text(
            "Indexing & Project Mapping",
            style = MaterialTheme.typography.titleSmall,
        )

        Text(
            "Jervis will index all Jira projects, issues, comments, and attachments. " +
                "You can map Jira projects to Jervis projects below for better context.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (projects.isNotEmpty()) {
            Text(
                "Available Projects (${projects.size})",
                style = MaterialTheme.typography.titleSmall,
            )

            projects.forEach { project ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "${project.key}: ${project.name}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Will be indexed automatically",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Text(
            "Note: Project mapping UI will be added in future updates",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun ConfluenceSettingsTab(
    client: com.jervis.dto.ClientDto,
    repository: JervisRepository,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    var spaceKey by remember { mutableStateOf("") }
    var rootPageId by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("Loading status...") }
    var isSaving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }

    // Load current client status
    LaunchedEffect(client.id) {
        val clientId = client.id ?: return@LaunchedEffect
        runCatching { repository.integrationSettings.getClientStatus(clientId) }
            .onSuccess { status ->
                spaceKey = status.confluenceSpaceKey.orEmpty()
                rootPageId = status.confluenceRootPageId.orEmpty()
                val jira =
                    if (status.jiraConnected) {
                        "Jira connected (tenant=${status.jiraTenant ?: "?"}, project=${status.jiraPrimaryProject ?: "?"})"
                    } else {
                        "Jira not connected"
                    }
                val conf = "Confluence defaults: space='${status.confluenceSpaceKey ?: ""}', root='${status.confluenceRootPageId ?: ""}'"
                statusText = "$jira | $conf"
            }.onFailure { e ->
                statusText = "Failed to load status: ${e.message}"
            }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Confluence Settings", style = MaterialTheme.typography.titleMedium)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
        ) {
            Text(
                text = statusText,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Text(
            "Indexing & Space Mapping",
            style = MaterialTheme.typography.titleSmall,
        )

        Text(
            "Jervis will index all Confluence spaces, pages, attachments, and comments. " +
                "You can optionally set default space/page for new content below.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            "Optional Defaults",
            style = MaterialTheme.typography.titleSmall,
        )

        OutlinedTextField(
            value = spaceKey,
            onValueChange = { spaceKey = it },
            label = { Text("Default Space Key (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            supportingText = { Text("e.g., PROJ for project documentation") },
        )

        OutlinedTextField(
            value = rootPageId,
            onValueChange = { rootPageId = it },
            label = { Text("Default Root Page ID (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            supportingText = { Text("Starting page for project docs") },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    scope.launch {
                        isSaving = true
                        saveMessage = null
                        try {
                            val clientId = client.id ?: error("Client ID is required")
                            val updated =
                                repository.integrationSettings.setClientConfluenceDefaults(
                                    com.jervis.dto.integration.ClientConfluenceDefaultsDto(
                                        clientId = clientId,
                                        confluenceSpaceKey = spaceKey.ifBlank { null },
                                        confluenceRootPageId = rootPageId.ifBlank { null },
                                    ),
                                )
                            spaceKey = updated.confluenceSpaceKey.orEmpty()
                            rootPageId = updated.confluenceRootPageId.orEmpty()
                            val jira =
                                if (updated.jiraConnected) {
                                    "Jira connected (tenant=${updated.jiraTenant ?: "?"}, project=${updated.jiraPrimaryProject ?: "?"})"
                                } else {
                                    "Jira not connected"
                                }
                            val conf = "Confluence defaults: space='${updated.confluenceSpaceKey ?: ""}', root='${updated.confluenceRootPageId ?: ""}'"
                            statusText = "$jira | $conf"
                            saveMessage = "Saved successfully"
                        } catch (e: Exception) {
                            saveMessage = "Failed to save: ${e.message}"
                        } finally {
                            isSaving = false
                        }
                    }
                },
                enabled = !isSaving,
                modifier = Modifier.weight(1f),
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Save")
                }
            }

            OutlinedButton(
                onClick = {
                    spaceKey = ""
                    rootPageId = ""
                    saveMessage = null
                },
                enabled = !isSaving,
                modifier = Modifier.weight(1f),
            ) {
                Text("Clear")
            }
        }

        if (saveMessage != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor =
                            if (saveMessage!!.contains("Failed") || saveMessage!!.contains("not yet")) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                    ),
            ) {
                Text(
                    text = saveMessage!!,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun ClientManagementDialog(
    clients: List<com.jervis.dto.ClientDto>,
    onDismiss: () -> Unit,
    onClientsChanged: (List<com.jervis.dto.ClientDto>) -> Unit,
    onSelectClient: (com.jervis.dto.ClientDto) -> Unit,
    repository: JervisRepository,
) {
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<com.jervis.dto.ClientDto?>(null) }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                val loaded = repository.clients.listClients()
                onClientsChanged(loaded)
            } catch (e: Exception) {
                errorMessage = "Failed to load clients: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage Clients") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().height(500.dp)) {
                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${clients.size} clients", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = { reload() }, enabled = !isLoading) {
                            Icon(Icons.Default.Refresh, "Refresh")
                        }
                        IconButton(onClick = { showCreateDialog = true }) {
                            Icon(Icons.Default.Add, "Add Client")
                        }
                    }
                }

                if (errorMessage != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Text(errorMessage!!, modifier = Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        clients.isEmpty() ->
                            Column(
                                modifier = Modifier.align(Alignment.Center).padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("No clients yet")
                                TextButton(onClick = { showCreateDialog = true }) { Text("Create your first client") }
                            }
                        else ->
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(clients) { client ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(client.name, style = MaterialTheme.typography.titleSmall)
                                                Text(
                                                    text = "ID: ${client.id}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                IconButton(
                                                    onClick = { onSelectClient(client) },
                                                ) { Icon(Icons.Default.CheckCircle, "Select", modifier = Modifier.size(20.dp)) }
                                                IconButton(
                                                    onClick = { editTarget = client },
                                                ) { Icon(Icons.Default.Edit, "Edit", modifier = Modifier.size(20.dp)) }
                                                IconButton(onClick = {
                                                    scope.launch {
                                                        try {
                                                            repository.clients.deleteClient(client.id)
                                                            reload()
                                                        } catch (e: Exception) {
                                                            errorMessage = "Failed to delete: ${e.message}"
                                                        }
                                                    }
                                                }) { Icon(Icons.Default.Delete, "Delete", modifier = Modifier.size(20.dp)) }
                                            }
                                        }
                                    }
                                }
                            }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )

    if (showCreateDialog || editTarget != null) {
        var name by remember(showCreateDialog, editTarget) { mutableStateOf(editTarget?.name ?: "") }
        AlertDialog(
            onDismissRequest = {
                showCreateDialog = false
                editTarget = null
            },
            title = { Text(if (editTarget == null) "Create Client" else "Edit Client") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Client Name") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                if (editTarget == null) {
                                    repository.clients.createClient(com.jervis.dto.ClientDto(name = name))
                                } else {
                                    repository.clients.updateClient(editTarget!!.id, editTarget!!.copy(name = name))
                                }
                                showCreateDialog = false
                                editTarget = null
                                reload()
                            } catch (e: Exception) {
                                errorMessage = "Failed to save: ${e.message}"
                            }
                        }
                    },
                    enabled = name.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCreateDialog = false
                    editTarget = null
                }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ProjectManagementDialog(
    client: com.jervis.dto.ClientDto,
    onDismiss: () -> Unit,
    repository: JervisRepository,
) {
    var projects by remember { mutableStateOf<List<com.jervis.dto.ProjectDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<com.jervis.dto.ProjectDto?>(null) }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                val clientId = client.id
                require(clientId.isNotEmpty()) { "Client ID is required" }
                projects = repository.projects.listProjectsForClient(clientId)
            } catch (e: Exception) {
                errorMessage = "Failed to load projects: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(client.id) { reload() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage Projects - ${client.name}") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().height(500.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${projects.size} projects", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = { reload() }, enabled = !isLoading) {
                            Icon(Icons.Default.Refresh, "Refresh")
                        }
                        IconButton(onClick = { showCreateDialog = true }) {
                            Icon(Icons.Default.Add, "Add Project")
                        }
                    }
                }

                if (errorMessage != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) { Text(errorMessage!!, modifier = Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onErrorContainer) }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        projects.isEmpty() ->
                            Column(
                                modifier = Modifier.align(Alignment.Center).padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("No projects yet")
                                TextButton(onClick = { showCreateDialog = true }) { Text("Create your first project") }
                            }
                        else ->
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(projects) { project ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(project.name, style = MaterialTheme.typography.titleSmall)
                                                if (!project.clientId.isNullOrEmpty()) {
                                                    Text(
                                                        text = "Client: ${project.clientId}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                IconButton(onClick = {
                                                    scope.launch {
                                                        try {
                                                            repository.projects.setDefaultProject(project)
                                                            reload()
                                                        } catch (e: Exception) {
                                                            errorMessage = "Failed to set default: ${e.message}"
                                                        }
                                                    }
                                                }) { Icon(Icons.Default.Star, "Set as Default", modifier = Modifier.size(20.dp)) }
                                                IconButton(
                                                    onClick = { editTarget = project },
                                                ) { Icon(Icons.Default.Edit, "Edit", modifier = Modifier.size(20.dp)) }
                                                IconButton(onClick = {
                                                    scope.launch {
                                                        try {
                                                            repository.projects.deleteProject(project)
                                                            reload()
                                                        } catch (e: Exception) {
                                                            errorMessage = "Failed to delete: ${e.message}"
                                                        }
                                                    }
                                                }) { Icon(Icons.Default.Delete, "Delete", modifier = Modifier.size(20.dp)) }
                                            }
                                        }
                                    }
                                }
                            }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )

    if (showCreateDialog || editTarget != null) {
        var name by remember(showCreateDialog, editTarget) { mutableStateOf(editTarget?.name ?: "") }
        AlertDialog(
            onDismissRequest = {
                showCreateDialog = false
                editTarget = null
            },
            title = { Text(if (editTarget == null) "Create Project" else "Edit Project") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Project Name") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                val saved =
                                    repository.projects.saveProject(
                                        (editTarget ?: com.jervis.dto.ProjectDto(name = "", clientId = client.id)).copy(
                                            name = name,
                                            clientId = client.id,
                                        ),
                                    )
                                showCreateDialog = false
                                editTarget = null
                                reload()
                            } catch (e: Exception) {
                                errorMessage = "Failed to save: ${e.message}"
                            }
                        }
                    },
                    enabled = name.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCreateDialog = false
                    editTarget = null
                }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EmailSettingsTab(
    client: com.jervis.dto.ClientDto,
    repository: JervisRepository,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    var provider by remember { mutableStateOf("GMAIL") }
    var displayName by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var serverHost by remember { mutableStateOf("imap.gmail.com") }
    var serverPort by remember { mutableStateOf("993") }
    var useSsl by remember { mutableStateOf(true) }

    var emailAccounts by remember { mutableStateOf<List<com.jervis.dto.email.EmailAccountDto>>(emptyList()) }
    var selectedAccount by remember { mutableStateOf<com.jervis.dto.email.EmailAccountDto?>(null) }
    var isLoadingAccounts by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }

    var providerDropdownExpanded by remember { mutableStateOf(false) }
    val providerOptions = listOf("GMAIL", "SEZNAM", "MICROSOFT", "IMAP")

    // Load accounts when client changes
    LaunchedEffect(client.id) {
        isLoadingAccounts = true
        try {
            emailAccounts = repository.emailAccounts.listEmailAccounts(clientId = client.id)
        } catch (e: Exception) {
            saveMessage = "Failed to load accounts: ${e.message}"
        } finally {
            isLoadingAccounts = false
        }
    }

    // Update server settings based on provider
    LaunchedEffect(provider) {
        when (provider) {
            "GMAIL" -> {
                serverHost = "imap.gmail.com"
                serverPort = "993"
            }
            "SEZNAM" -> {
                serverHost = "imap.seznam.cz"
                serverPort = "993"
            }
            "MICROSOFT" -> {
                serverHost = "outlook.office365.com"
                serverPort = "993"
            }
            "IMAP" -> {
                if (serverHost.isEmpty()) serverHost = ""
                if (serverPort.isEmpty()) serverPort = "993"
            }
        }
        useSsl = true
    }

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        // Form section (top half)
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(0.6f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Email Account Configuration", style = MaterialTheme.typography.titleMedium)

            ExposedDropdownMenuBox(
                expanded = providerDropdownExpanded,
                onExpandedChange = { providerDropdownExpanded = !providerDropdownExpanded },
            ) {
                OutlinedTextField(
                    value = provider,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Provider") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerDropdownExpanded) },
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true).fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = providerDropdownExpanded,
                    onDismissRequest = { providerDropdownExpanded = false },
                ) {
                    providerOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                provider = option
                                providerDropdownExpanded = false
                            },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("Display Name *") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 3,
            )

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email Address *") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedTextField(
                value = serverHost,
                onValueChange = { serverHost = it },
                label = { Text("IMAP Server") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = serverPort,
                    onValueChange = { serverPort = it },
                    label = { Text("Port") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Checkbox(
                        checked = useSsl,
                        onCheckedChange = { useSsl = it },
                    )
                    Text("Use SSL/TLS")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            isSaving = true
                            saveMessage = null
                            try {
                                val providerEnum =
                                    com.jervis.domain.email.EmailProviderEnum
                                        .valueOf(provider)
                                val request =
                                    com.jervis.dto.email.CreateOrUpdateEmailAccountRequestDto(
                                        clientId = client.id,
                                        provider = providerEnum,
                                        displayName = displayName,
                                        description = description.ifBlank { null },
                                        email = email,
                                        username = username.ifBlank { null },
                                        password = password.ifBlank { null },
                                        serverHost = serverHost.ifBlank { null },
                                        serverPort = serverPort.toIntOrNull(),
                                        useSsl = useSsl,
                                    )

                                if (selectedAccount != null) {
                                    val accountId = selectedAccount!!.id ?: throw IllegalStateException("Account ID is null")
                                    repository.emailAccounts.updateEmailAccount(accountId, request)
                                    saveMessage = "Email account updated successfully"
                                } else {
                                    repository.emailAccounts.createEmailAccount(request)
                                    saveMessage = "Email account created successfully"
                                }

                                // Reload accounts
                                emailAccounts = repository.emailAccounts.listEmailAccounts(clientId = client.id)
                            } catch (e: Exception) {
                                saveMessage = "Failed to save: ${e.message}"
                            } finally {
                                isSaving = false
                            }
                        }
                    },
                    enabled = !isSaving && displayName.isNotBlank() && email.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(if (selectedAccount != null) "Update Account" else "Save Account")
                    }
                }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            isSaving = true
                            saveMessage = null
                            try {
                                if (selectedAccount != null) {
                                    val accountId = selectedAccount!!.id ?: throw IllegalStateException("Account ID is null")
                                    val result = repository.emailAccounts.validateEmailAccount(accountId)
                                    saveMessage =
                                        if (result.ok) {
                                            "Connection successful: ${result.message ?: "Connected"}"
                                        } else {
                                            "Connection failed: ${result.message ?: "Unknown error"}"
                                        }
                                } else {
                                    saveMessage = "Please save the account first before testing"
                                }
                            } catch (e: Exception) {
                                saveMessage = "Test failed: ${e.message}"
                            } finally {
                                isSaving = false
                            }
                        }
                    },
                    enabled = !isSaving && selectedAccount != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Test Connection")
                }

                OutlinedButton(
                    onClick = {
                        displayName = ""
                        description = ""
                        email = ""
                        username = ""
                        password = ""
                        serverHost =
                            when (provider) {
                                "GMAIL" -> "imap.gmail.com"
                                "SEZNAM" -> "imap.seznam.cz"
                                "MICROSOFT" -> "outlook.office365.com"
                                else -> ""
                            }
                        serverPort = "993"
                        useSsl = true
                        selectedAccount = null
                        saveMessage = null
                    },
                    enabled = !isSaving,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Clear")
                }
            }

            if (saveMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor =
                                if (saveMessage!!.contains("Failed") || saveMessage!!.contains("not yet")) {
                                    MaterialTheme.colorScheme.errorContainer
                                } else {
                                    MaterialTheme.colorScheme.primaryContainer
                                },
                        ),
                ) {
                    Text(
                        text = saveMessage!!,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }

        HorizontalDivider()

        // Accounts list (bottom half)
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(0.4f)
                    .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Configured Accounts (${emailAccounts.size})",
                    style = MaterialTheme.typography.titleMedium,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = {
                            scope.launch {
                                isLoadingAccounts = true
                                try {
                                    emailAccounts = repository.emailAccounts.listEmailAccounts(clientId = client.id)
                                } catch (e: Exception) {
                                    saveMessage = "Failed to refresh: ${e.message}"
                                } finally {
                                    isLoadingAccounts = false
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }

                    IconButton(
                        onClick = {
                            selectedAccount?.let { account ->
                                scope.launch {
                                    try {
                                        val accountId = account.id ?: throw IllegalStateException("Account ID is null")
                                        repository.emailAccounts.deleteEmailAccount(accountId)
                                        emailAccounts = repository.emailAccounts.listEmailAccounts(clientId = client.id)
                                        selectedAccount = null
                                        // Clear form
                                        displayName = ""
                                        description = ""
                                        email = ""
                                        username = ""
                                        password = ""
                                        saveMessage = "Account deleted successfully"
                                    } catch (e: Exception) {
                                        saveMessage = "Failed to delete: ${e.message}"
                                    }
                                }
                            }
                        },
                        enabled = selectedAccount != null,
                    ) {
                        Icon(Icons.Default.Delete, "Delete")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (isLoadingAccounts) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (emailAccounts.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No email accounts configured",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(emailAccounts) { account ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                selectedAccount = account
                                // Load account data into form
                                provider = account.provider.name
                                displayName = account.displayName
                                description = account.description ?: ""
                                email = account.email
                                username = account.username ?: ""
                                password = "" // Don't load password for security
                                serverHost = account.serverHost ?: when (account.provider.name) {
                                    "GMAIL" -> "imap.gmail.com"
                                    "SEZNAM" -> "imap.seznam.cz"
                                    "MICROSOFT" -> "outlook.office365.com"
                                    else -> ""
                                }
                                serverPort = account.serverPort?.toString() ?: "993"
                                useSsl = account.useSsl
                            },
                            colors =
                                CardDefaults.cardColors(
                                    containerColor =
                                        if (selectedAccount?.id == account.id) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surface
                                        },
                                ),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    account.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    account.email,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        account.provider.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        if (account.hasPassword) "Configured" else "Not Configured",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
