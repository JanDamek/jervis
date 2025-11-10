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
fun SettingsWindow(repository: JervisRepository) {
    var selectedTabIndex by remember { mutableStateOf(0) }
    var clients by remember { mutableStateOf<List<com.jervis.dto.ClientDto>>(emptyList()) }
    var selectedClient by remember { mutableStateOf<com.jervis.dto.ClientDto?>(null) }
    var isLoadingClients by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var clientDropdownExpanded by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val tabs = listOf("Git", "Jira", "Confluence", "Email")

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
                title = { Text("Settings") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Client selector
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Select Client",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    ExposedDropdownMenuBox(
                        expanded = clientDropdownExpanded,
                        onExpandedChange = { clientDropdownExpanded = !clientDropdownExpanded }
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
                            enabled = !isLoadingClients && clients.isNotEmpty()
                        )
                        ExposedDropdownMenu(
                            expanded = clientDropdownExpanded,
                            onDismissRequest = { clientDropdownExpanded = false }
                        ) {
                            clients.forEach { client ->
                                DropdownMenuItem(
                                    text = { Text(client.name) },
                                    onClick = {
                                        selectedClient = client
                                        clientDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (errorMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = errorMessage!!,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
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
                        text = { Text(title) }
                    )
                }
            }

            // Tab content
            if (selectedClient != null) {
                when (selectedTabIndex) {
                    0 -> GitSettingsTab(selectedClient!!, repository, scope)
                    1 -> JiraSettingsTab(selectedClient!!, repository, scope)
                    2 -> ConfluenceSettingsTab(selectedClient!!, repository, scope)
                    3 -> EmailSettingsTab(selectedClient!!, repository, scope)
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoadingClients) {
                        CircularProgressIndicator()
                    } else {
                        Text(
                            "No client selected or available",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GitSettingsTab(
    client: com.jervis.dto.ClientDto,
    repository: JervisRepository,
    scope: kotlinx.coroutines.CoroutineScope
) {
    var gitProvider by remember { mutableStateOf(client.gitProvider?.name ?: "NONE") }
    var monoRepoUrl by remember { mutableStateOf(client.monoRepoUrl ?: "") }
    var defaultBranch by remember { mutableStateOf(client.defaultBranch ?: "main") }
    var authType by remember { mutableStateOf("SSH_KEY") }

    // SSH fields
    var sshPrivateKey by remember { mutableStateOf("") }
    var sshPublicKey by remember { mutableStateOf("") }
    var sshPassphrase by remember { mutableStateOf("") }

    // HTTPS PAT fields
    var httpsToken by remember { mutableStateOf("") }

    // HTTPS Basic fields
    var httpsUsername by remember { mutableStateOf("") }
    var httpsPassword by remember { mutableStateOf("") }

    // Git config
    var gitUserName by remember { mutableStateOf(client.gitConfig?.gitUserName ?: "") }
    var gitUserEmail by remember { mutableStateOf(client.gitConfig?.gitUserEmail ?: "") }

    // GPG fields
    var gpgPrivateKey by remember { mutableStateOf("") }
    var gpgPublicKey by remember { mutableStateOf("") }
    var gpgKeyId by remember { mutableStateOf(client.gitConfig?.gpgKeyId ?: "") }
    var gpgPassphrase by remember { mutableStateOf("") }

    // Workflow options
    var requireGpgSign by remember { mutableStateOf(client.gitConfig?.requireGpgSign ?: false) }
    var requireLinearHistory by remember { mutableStateOf(client.gitConfig?.requireLinearHistory ?: false) }
    var conventionalCommits by remember { mutableStateOf(client.gitConfig?.conventionalCommits ?: true) }
    var commitMessageTemplate by remember { mutableStateOf(client.gitConfig?.commitMessageTemplate ?: "") }

    var isSaving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }

    val providerOptions = listOf("NONE", "GITHUB", "GITLAB", "BITBUCKET", "AZURE_DEVOPS", "GITEA", "CUSTOM")
    val authTypeOptions = listOf("SSH_KEY", "HTTPS_PAT", "HTTPS_BASIC", "NONE")

    var providerDropdownExpanded by remember { mutableStateOf(false) }
    var authTypeDropdownExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Repository Section
        Text("Repository Configuration", style = MaterialTheme.typography.titleMedium)

        ExposedDropdownMenuBox(
            expanded = providerDropdownExpanded,
            onExpandedChange = { providerDropdownExpanded = !providerDropdownExpanded }
        ) {
            OutlinedTextField(
                value = gitProvider,
                onValueChange = {},
                readOnly = true,
                label = { Text("Git Provider *") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerDropdownExpanded) },
                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true).fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = providerDropdownExpanded,
                onDismissRequest = { providerDropdownExpanded = false }
            ) {
                providerOptions.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            gitProvider = option
                            providerDropdownExpanded = false
                        }
                    )
                }
            }
        }

        if (gitProvider != "NONE") {
            OutlinedTextField(
                value = monoRepoUrl,
                onValueChange = { monoRepoUrl = it },
                label = { Text("Mono-Repo URL (Optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = defaultBranch,
                onValueChange = { defaultBranch = it },
                label = { Text("Default Branch *") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            HorizontalDivider()

            // Authentication Section
            Text("Authentication", style = MaterialTheme.typography.titleMedium)

            ExposedDropdownMenuBox(
                expanded = authTypeDropdownExpanded,
                onExpandedChange = { authTypeDropdownExpanded = !authTypeDropdownExpanded }
            ) {
                OutlinedTextField(
                    value = authType,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Auth Type") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = authTypeDropdownExpanded) },
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true).fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = authTypeDropdownExpanded,
                    onDismissRequest = { authTypeDropdownExpanded = false }
                ) {
                    authTypeOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                authType = option
                                authTypeDropdownExpanded = false
                            }
                        )
                    }
                }
            }

            when (authType) {
                "SSH_KEY" -> {
                    OutlinedTextField(
                        value = sshPrivateKey,
                        onValueChange = { sshPrivateKey = it },
                        label = { Text("SSH Private Key *") },
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                        minLines = 6,
                        maxLines = 10
                    )

                    OutlinedTextField(
                        value = sshPublicKey,
                        onValueChange = { sshPublicKey = it },
                        label = { Text("SSH Public Key") },
                        modifier = Modifier.fillMaxWidth().height(100.dp),
                        minLines = 3,
                        maxLines = 5
                    )

                    OutlinedTextField(
                        value = sshPassphrase,
                        onValueChange = { sshPassphrase = it },
                        label = { Text("Passphrase") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                "HTTPS_PAT" -> {
                    OutlinedTextField(
                        value = httpsToken,
                        onValueChange = { httpsToken = it },
                        label = { Text("Personal Access Token *") },
                        modifier = Modifier.fillMaxWidth().height(100.dp),
                        minLines = 3,
                        maxLines = 5
                    )
                }
                "HTTPS_BASIC" -> {
                    OutlinedTextField(
                        value = httpsUsername,
                        onValueChange = { httpsUsername = it },
                        label = { Text("Username *") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = httpsPassword,
                        onValueChange = { httpsPassword = it },
                        label = { Text("Password *") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }

            HorizontalDivider()

            // Global Settings Section
            Text("Global Git Settings", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = gitUserName,
                onValueChange = { gitUserName = it },
                label = { Text("Git User Name *") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = gitUserEmail,
                onValueChange = { gitUserEmail = it },
                label = { Text("Git User Email *") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            HorizontalDivider()

            // GPG Signing Section
            Text("GPG Signing (Optional)", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = gpgPrivateKey,
                onValueChange = { gpgPrivateKey = it },
                label = { Text("GPG Private Key") },
                modifier = Modifier.fillMaxWidth().height(150.dp),
                minLines = 6,
                maxLines = 10
            )

            OutlinedTextField(
                value = gpgPublicKey,
                onValueChange = { gpgPublicKey = it },
                label = { Text("GPG Public Key") },
                modifier = Modifier.fillMaxWidth().height(100.dp),
                minLines = 3,
                maxLines = 5
            )

            OutlinedTextField(
                value = gpgKeyId,
                onValueChange = { gpgKeyId = it },
                label = { Text("GPG Key ID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = gpgPassphrase,
                onValueChange = { gpgPassphrase = it },
                label = { Text("GPG Passphrase") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            HorizontalDivider()

            // Workflow Section
            Text("Workflow Options", style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = requireGpgSign,
                    onCheckedChange = { requireGpgSign = it }
                )
                Text("Require GPG Signatures")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = requireLinearHistory,
                    onCheckedChange = { requireLinearHistory = it }
                )
                Text("Require Linear History")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = conventionalCommits,
                    onCheckedChange = { conventionalCommits = it }
                )
                Text("Use Conventional Commits")
            }

            OutlinedTextField(
                value = commitMessageTemplate,
                onValueChange = { commitMessageTemplate = it },
                label = { Text("Commit Message Template") },
                modifier = Modifier.fillMaxWidth().height(100.dp),
                minLines = 3,
                maxLines = 5
            )
        }

        // Save/Cancel buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    scope.launch {
                        isSaving = true
                        saveMessage = null
                        try {
                            // TODO: Implement Git configuration save
                            // This requires creating a GitConfigurationRepository wrapping IGitConfigurationService
                            saveMessage = "Git configuration save not yet implemented in repository"
                        } catch (e: Exception) {
                            saveMessage = "Failed to save: ${e.message}"
                        } finally {
                            isSaving = false
                        }
                    }
                },
                enabled = !isSaving,
                modifier = Modifier.weight(1f)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Save")
                }
            }

            OutlinedButton(
                onClick = {
                    // Reset to original values
                    gitProvider = client.gitProvider?.name ?: "NONE"
                    monoRepoUrl = client.monoRepoUrl ?: ""
                    defaultBranch = client.defaultBranch ?: "main"
                    gitUserName = client.gitConfig?.gitUserName ?: ""
                    gitUserEmail = client.gitConfig?.gitUserEmail ?: ""
                    saveMessage = null
                },
                enabled = !isSaving,
                modifier = Modifier.weight(1f)
            ) {
                Text("Cancel")
            }
        }

        if (saveMessage != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (saveMessage!!.contains("Failed") || saveMessage!!.contains("not yet"))
                        MaterialTheme.colorScheme.errorContainer
                    else
                        MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Text(
                    text = saveMessage!!,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

@Composable
private fun JiraSettingsTab(
    client: com.jervis.dto.ClientDto,
    repository: JervisRepository,
    scope: kotlinx.coroutines.CoroutineScope
) {
    var tenant by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var apiToken by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("Loading status...") }
    var isSaving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Jira Configuration", style = MaterialTheme.typography.titleMedium)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(
                text = statusText,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Text(
            "Atlassian Cloud Connection",
            style = MaterialTheme.typography.titleSmall
        )

        OutlinedTextField(
            value = tenant,
            onValueChange = { tenant = it },
            label = { Text("Tenant (e.g., your-domain.atlassian.net)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            supportingText = { Text("Enter host only, no https://, no path") }
        )

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = apiToken,
            onValueChange = { apiToken = it },
            label = { Text("API Token") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    scope.launch {
                        isSaving = true
                        saveMessage = null
                        try {
                            // TODO: Implement Jira setup save
                            // This requires creating a JiraSetupRepository wrapping IJiraSetupService
                            saveMessage = "Jira setup save not yet implemented in repository"
                        } catch (e: Exception) {
                            saveMessage = "Failed to save: ${e.message}"
                        } finally {
                            isSaving = false
                        }
                    }
                },
                enabled = !isSaving && tenant.isNotBlank() && email.isNotBlank() && apiToken.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary
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
                modifier = Modifier.weight(1f)
            ) {
                Text("Clear")
            }
        }

        TextButton(
            onClick = {
                // TODO: Open browser to Atlassian API token page
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Where to get API token")
        }

        if (saveMessage != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (saveMessage!!.contains("Failed") || saveMessage!!.contains("not yet"))
                        MaterialTheme.colorScheme.errorContainer
                    else
                        MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Text(
                    text = saveMessage!!,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

@Composable
private fun ConfluenceSettingsTab(
    client: com.jervis.dto.ClientDto,
    repository: JervisRepository,
    scope: kotlinx.coroutines.CoroutineScope
) {
    var spaceKey by remember { mutableStateOf("") }
    var rootPageId by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("Loading status...") }
    var isSaving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Confluence Configuration", style = MaterialTheme.typography.titleMedium)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(
                text = statusText,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Text(
            "Default Confluence Settings",
            style = MaterialTheme.typography.titleSmall
        )

        OutlinedTextField(
            value = spaceKey,
            onValueChange = { spaceKey = it },
            label = { Text("Space Key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = rootPageId,
            onValueChange = { rootPageId = it },
            label = { Text("Root Page ID") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    scope.launch {
                        isSaving = true
                        saveMessage = null
                        try {
                            // TODO: Implement Confluence settings save
                            // This requires creating a ConfluenceRepository wrapping IConfluenceService
                            saveMessage = "Confluence settings save not yet implemented in repository"
                        } catch (e: Exception) {
                            saveMessage = "Failed to save: ${e.message}"
                        } finally {
                            isSaving = false
                        }
                    }
                },
                enabled = !isSaving,
                modifier = Modifier.weight(1f)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary
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
                modifier = Modifier.weight(1f)
            ) {
                Text("Clear")
            }
        }

        if (saveMessage != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (saveMessage!!.contains("Failed") || saveMessage!!.contains("not yet"))
                        MaterialTheme.colorScheme.errorContainer
                    else
                        MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Text(
                    text = saveMessage!!,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

@Composable
private fun EmailSettingsTab(
    client: com.jervis.dto.ClientDto,
    repository: JervisRepository,
    scope: kotlinx.coroutines.CoroutineScope
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

    // Load accounts on mount
    LaunchedEffect(Unit) {
        isLoadingAccounts = true
        try {
            // TODO: Load email accounts when repository is available
            // emailAccounts = repository.emailAccounts.listAccounts(clientId = client.id)
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
        modifier = Modifier.fillMaxSize()
    ) {
        // Form section (top half)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.6f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Email Account Configuration", style = MaterialTheme.typography.titleMedium)

            ExposedDropdownMenuBox(
                expanded = providerDropdownExpanded,
                onExpandedChange = { providerDropdownExpanded = !providerDropdownExpanded }
            ) {
                OutlinedTextField(
                    value = provider,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Provider") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerDropdownExpanded) },
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true).fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = providerDropdownExpanded,
                    onDismissRequest = { providerDropdownExpanded = false }
                ) {
                    providerOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                provider = option
                                providerDropdownExpanded = false
                            }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("Display Name *") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 3
            )

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email Address *") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = serverHost,
                onValueChange = { serverHost = it },
                label = { Text("IMAP Server") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = serverPort,
                    onValueChange = { serverPort = it },
                    label = { Text("Port") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Checkbox(
                        checked = useSsl,
                        onCheckedChange = { useSsl = it }
                    )
                    Text("Use SSL/TLS")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            isSaving = true
                            saveMessage = null
                            try {
                                // TODO: Implement email account save
                                // This requires creating an EmailAccountRepository wrapping IEmailAccountService
                                saveMessage = "Email account save not yet implemented in repository"
                            } catch (e: Exception) {
                                saveMessage = "Failed to save: ${e.message}"
                            } finally {
                                isSaving = false
                            }
                        }
                    },
                    enabled = !isSaving && displayName.isNotBlank() && email.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text("Save Account")
                    }
                }

                OutlinedButton(
                    onClick = {
                        // TODO: Test connection
                        saveMessage = "Test connection not yet implemented"
                    },
                    enabled = !isSaving,
                    modifier = Modifier.weight(1f)
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
                        serverHost = when(provider) {
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
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Clear")
                }
            }

            if (saveMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (saveMessage!!.contains("Failed") || saveMessage!!.contains("not yet"))
                            MaterialTheme.colorScheme.errorContainer
                        else
                            MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Text(
                        text = saveMessage!!,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        HorizontalDivider()

        // Accounts list (bottom half)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.4f)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Configured Accounts (${emailAccounts.size})",
                    style = MaterialTheme.typography.titleMedium
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = {
                            // TODO: Refresh accounts
                        }
                    ) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }

                    IconButton(
                        onClick = {
                            selectedAccount?.let { account ->
                                // TODO: Delete account
                            }
                        },
                        enabled = selectedAccount != null
                    ) {
                        Icon(Icons.Default.Delete, "Delete")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (isLoadingAccounts) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (emailAccounts.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No email accounts configured",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(emailAccounts) { account ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { selectedAccount = account },
                            colors = CardDefaults.cardColors(
                                containerColor = if (selectedAccount?.id == account.id)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    account.displayName,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    account.email,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        account.provider.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        if (account.hasPassword) "Configured" else "Not Configured",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
