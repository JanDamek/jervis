package com.jervis.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.repository.JervisRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: JervisRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    // Client selection state
    var clients by remember { mutableStateOf<List<com.jervis.dto.ClientDto>>(emptyList()) }
    var selectedClientId by remember { mutableStateOf<String?>(null) }
    var clientLoadError by remember { mutableStateOf<String?>(null) }

    var repoUrl by remember { mutableStateOf("") }
    var branches by remember { mutableStateOf<List<String>>(emptyList()) }
    var defaultBranch by remember { mutableStateOf<String?>(null) }
    var selectedBranch by remember { mutableStateOf("") }
    var loadError by remember { mutableStateOf<String?>(null) }

    // Load clients once
    LaunchedEffect(Unit) {
        runCatching {
            clients = repository.clients.listClients()
            selectedClientId = clients.firstOrNull()?.id
        }.onFailure { e ->
            clientLoadError = "Failed to load clients: ${e.message}"
        }
    }

    fun refreshBranches() {
        scope.launch {
            loadError = null
            runCatching {
                val clientId = selectedClientId ?: return@runCatching
                val result = repository.gitConfiguration.listRemoteBranches(clientId, repoUrl.ifBlank { null })
                branches = result.branches
                defaultBranch = result.defaultBranch
                if (selectedBranch.isBlank() || !branches.contains(selectedBranch)) {
                    selectedBranch = result.defaultBranch ?: branches.firstOrNull().orEmpty()
                }
            }.onFailure { e ->
                loadError = "Failed to load branches: ${e.message}"
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("← Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                "Configure clients, projects, and integrations",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // TODO: Add settings tabs (Clients, Projects, Git, Atlassian, Jira, Confluence, Email)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Clients", style = MaterialTheme.typography.titleMedium)
                    Text("Manage your clients", style = MaterialTheme.typography.bodySmall)
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Projects", style = MaterialTheme.typography.titleMedium)
                    Text("Manage your projects", style = MaterialTheme.typography.bodySmall)
                }
            }

            // Minimal Git setup section for selecting an existing branch only
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Git setup", style = MaterialTheme.typography.titleMedium)
                    if (clientLoadError != null) {
                        Text(clientLoadError!!, color = MaterialTheme.colorScheme.error)
                    }
                    // Client selector
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Client:")
                        var expandedClients by remember { mutableStateOf(false) }
                        Box {
                            Button(onClick = { expandedClients = true }) { Text(clients.firstOrNull { it.id == selectedClientId }?.name ?: "Select client") }
                            DropdownMenu(expanded = expandedClients, onDismissRequest = { expandedClients = false }) {
                                clients.forEach { c ->
                                    DropdownMenuItem(text = { Text(c.name) }, onClick = {
                                        selectedClientId = c.id
                                        expandedClients = false
                                        // Clear previous branches when client changes
                                        branches = emptyList()
                                        defaultBranch = null
                                        selectedBranch = ""
                                    })
                                }
                            }
                        }
                    }
                    if (loadError != null) {
                        Text(loadError!!, color = MaterialTheme.colorScheme.error)
                    }
                    OutlinedTextField(
                        value = repoUrl,
                        onValueChange = { repoUrl = it },
                        label = { Text("Repository URL (optional override)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Branch:")
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            Button(onClick = { expanded = true }) { Text(selectedBranch.ifBlank { "Select branch" }) }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                branches.forEach { b ->
                                    DropdownMenuItem(text = { Text(b) }, onClick = {
                                        selectedBranch = b
                                        expanded = false
                                    })
                                }
                            }
                        }
                        Button(onClick = { refreshBranches() }) { Text("Refresh branches") }
                        Button(
                            onClick = {
                                scope.launch {
                                    loadError = null
                                    val clientId = selectedClientId
                                    val branch = selectedBranch
                                    if (clientId.isNullOrBlank() || branch.isBlank()) {
                                        loadError = "Select client and branch first"
                                    } else {
                                        runCatching {
                                            repository.gitConfiguration.setDefaultBranch(clientId, branch)
                                        }.onFailure { e ->
                                            loadError = "Failed to save default branch: ${e.message}"
                                        }
                                    }
                                }
                            },
                            enabled = !selectedClientId.isNullOrBlank() && selectedBranch.isNotBlank()
                        ) { Text("Save default branch") }
                    }
                    if (!defaultBranch.isNullOrBlank()) {
                        Text("Detected default: ${defaultBranch}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
