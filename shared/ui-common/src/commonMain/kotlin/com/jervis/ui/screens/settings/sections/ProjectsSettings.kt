package com.jervis.ui.screens.settings.sections

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
import androidx.compose.ui.unit.dp
import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionResourceDto
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.*
import com.jervis.ui.util.*
import kotlinx.coroutines.launch

@Composable
fun ProjectsSettings(repository: JervisRepository) {
    var projects by remember { mutableStateOf<List<ProjectDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var selectedProject by remember { mutableStateOf<ProjectDto?>(null) }
    val scope = rememberCoroutineScope()

    fun loadData() {
        scope.launch {
            isLoading = true
            try {
                projects = repository.projects.getAllProjects()
            } catch (e: Exception) {
                // Error handling
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadData() }

    if (selectedProject != null) {
        ProjectEditForm(
            project = selectedProject!!,
            repository = repository,
            onSave = { updated ->
                scope.launch {
                     try {
                         repository.projects.updateProject(updated.id ?: "", updated)
                         selectedProject = null
                         loadData()
                     } catch (e: Exception) {
                        // Error handling
                    }
                }
            },
            onCancel = { selectedProject = null }
        )
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            JActionBar {
                RefreshIconButton(onClick = { loadData() })
            }

            Spacer(Modifier.height(8.dp))

            if (isLoading && projects.isEmpty()) {
                JCenteredLoading()
            } else if (projects.isEmpty()) {
                JEmptyState(message = "Žádné projekty nenalezeny")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(projects) { project ->
                        JTableRowCard(
                            selected = false,
                            modifier = Modifier.clickable { selectedProject = project }
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(project.name, style = MaterialTheme.typography.titleMedium)
                                    Text(project.description ?: "Bez popisu", style = MaterialTheme.typography.bodySmall)
                                    if (project.gitCommitAuthorName != null) {
                                        Text(
                                            "Git: ${project.gitCommitAuthorName} <${project.gitCommitAuthorEmail}>",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectEditForm(
    project: ProjectDto,
    repository: JervisRepository,
    onSave: (ProjectDto) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf(project.name) }
    var description by remember { mutableStateOf(project.description ?: "") }

    // Client and connections
    var client by remember { mutableStateOf<ClientDto?>(null) }
    var clientConnections by remember { mutableStateOf<List<ConnectionResponseDto>>(emptyList()) }

    // Resource identifiers from client's connections
    var gitRepositoryConnectionId by remember { mutableStateOf(project.gitRepositoryConnectionId) }
    var gitRepositoryIdentifier by remember { mutableStateOf(project.gitRepositoryIdentifier ?: "") }
    var bugtrackerConnectionId by remember { mutableStateOf(project.bugtrackerConnectionId) }
    var bugtrackerProjectKey by remember { mutableStateOf(project.bugtrackerProjectKey ?: "") }
    var wikiConnectionId by remember { mutableStateOf(project.wikiConnectionId) }
    var wikiSpaceKey by remember { mutableStateOf(project.wikiSpaceKey ?: "") }

    // Available resources for each connection type
    var gitRepositories by remember { mutableStateOf<List<ConnectionResourceDto>>(emptyList()) }
    var bugtrackerProjects by remember { mutableStateOf<List<ConnectionResourceDto>>(emptyList()) }
    var wikiSpaces by remember { mutableStateOf<List<ConnectionResourceDto>>(emptyList()) }
    var loadingGitRepos by remember { mutableStateOf(false) }
    var loadingBugtrackerProjects by remember { mutableStateOf(false) }
    var loadingWikiSpaces by remember { mutableStateOf(false) }

    // Git commit configuration (can override client's config)
    var useCustomGitConfig by remember { mutableStateOf(
        project.gitCommitAuthorName != null || project.gitCommitMessageFormat != null
    ) }
    var gitCommitMessageFormat by remember { mutableStateOf(project.gitCommitMessageFormat ?: "") }
    var gitCommitAuthorName by remember { mutableStateOf(project.gitCommitAuthorName ?: "") }
    var gitCommitAuthorEmail by remember { mutableStateOf(project.gitCommitAuthorEmail ?: "") }
    var gitCommitCommitterName by remember { mutableStateOf(project.gitCommitCommitterName ?: "") }
    var gitCommitCommitterEmail by remember { mutableStateOf(project.gitCommitCommitterEmail ?: "") }
    var gitCommitGpgSign by remember { mutableStateOf(project.gitCommitGpgSign ?: false) }
    var gitCommitGpgKeyId by remember { mutableStateOf(project.gitCommitGpgKeyId ?: "") }

    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // Load client and connections
    LaunchedEffect(project.clientId) {
        try {
            val cid = project.clientId
            if (cid != null) {
                client = repository.clients.getClientById(cid)
                val allConnections = repository.connections.getAllConnections()
                clientConnections = allConnections.filter { conn ->
                    client?.connectionIds?.contains(conn.id) == true
                }
            }
        } catch (e: Exception) {
            // Error handling
        }
    }

    // Load resources when connection changes
    fun loadGitRepositories(connectionId: String) {
        scope.launch {
            loadingGitRepos = true
            try {
                gitRepositories = repository.connections.listAvailableResources(
                    connectionId, ConnectionCapability.REPOSITORY
                )
            } catch (e: Exception) {
                gitRepositories = emptyList()
            } finally {
                loadingGitRepos = false
            }
        }
    }

    fun loadBugtrackerProjects(connectionId: String) {
        scope.launch {
            loadingBugtrackerProjects = true
            try {
                bugtrackerProjects = repository.connections.listAvailableResources(
                    connectionId, ConnectionCapability.BUGTRACKER
                )
            } catch (e: Exception) {
                bugtrackerProjects = emptyList()
            } finally {
                loadingBugtrackerProjects = false
            }
        }
    }

    fun loadWikiSpaces(connectionId: String) {
        scope.launch {
            loadingWikiSpaces = true
            try {
                wikiSpaces = repository.connections.listAvailableResources(
                    connectionId, ConnectionCapability.WIKI
                )
            } catch (e: Exception) {
                wikiSpaces = emptyList()
            } finally {
                loadingWikiSpaces = false
            }
        }
    }

    // Load resources when initial connection IDs are set
    LaunchedEffect(gitRepositoryConnectionId) {
        gitRepositoryConnectionId?.let { loadGitRepositories(it) }
    }
    LaunchedEffect(bugtrackerConnectionId) {
        bugtrackerConnectionId?.let { loadBugtrackerProjects(it) }
    }
    LaunchedEffect(wikiConnectionId) {
        wikiConnectionId?.let { loadWikiSpaces(it) }
    }

    // Filter connections by capability
    val gitConnections = clientConnections.filter { conn ->
        conn.capabilities.any { it == ConnectionCapability.REPOSITORY || it == ConnectionCapability.GIT }
    }
    val bugtrackerConnections = clientConnections.filter { conn ->
        conn.capabilities.contains(ConnectionCapability.BUGTRACKER)
    }
    val wikiConnections = clientConnections.filter { conn ->
        conn.capabilities.contains(ConnectionCapability.WIKI)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(scrollState).padding(end = 16.dp)
        ) {
            JSection(title = "Základní informace") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Název projektu") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Popis") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
            }

            Spacer(Modifier.height(16.dp))

            JSection(title = "Zdroje projektu") {
                Text(
                    "Přiřaďte konkrétní zdroje z connections dostupných pro klienta projektu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (clientConnections.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Načítám connections klienta...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Spacer(Modifier.height(16.dp))

                    // Git Repository Section
                    Text("Git Repository", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))

                    if (gitConnections.isEmpty()) {
                        Text(
                            "Klient nemá žádné Git/Repository connections.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        var gitConnectionExpanded by remember { mutableStateOf(false) }
                        var gitRepoExpanded by remember { mutableStateOf(false) }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Connection dropdown
                            ExposedDropdownMenuBox(
                                expanded = gitConnectionExpanded,
                                onExpandedChange = { gitConnectionExpanded = it },
                                modifier = Modifier.weight(1f)
                            ) {
                                OutlinedTextField(
                                    value = gitConnections.find { it.id == gitRepositoryConnectionId }?.name ?: "",
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Connection") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = gitConnectionExpanded) },
                                    modifier = Modifier.menuAnchor()
                                )
                                ExposedDropdownMenu(
                                    expanded = gitConnectionExpanded,
                                    onDismissRequest = { gitConnectionExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("-- Žádné --") },
                                        onClick = {
                                            gitRepositoryConnectionId = null
                                            gitRepositoryIdentifier = ""
                                            gitRepositories = emptyList()
                                            gitConnectionExpanded = false
                                        }
                                    )
                                    gitConnections.forEach { conn ->
                                        DropdownMenuItem(
                                            text = { Text(conn.name) },
                                            onClick = {
                                                gitRepositoryConnectionId = conn.id
                                                gitRepositoryIdentifier = ""
                                                loadGitRepositories(conn.id)
                                                gitConnectionExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            // Repository dropdown (when connection selected)
                            if (gitRepositoryConnectionId != null) {
                                ExposedDropdownMenuBox(
                                    expanded = gitRepoExpanded,
                                    onExpandedChange = { gitRepoExpanded = it },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    OutlinedTextField(
                                        value = gitRepositoryIdentifier,
                                        onValueChange = { gitRepositoryIdentifier = it },
                                        label = { Text("Repository") },
                                        trailingIcon = {
                                            if (loadingGitRepos) {
                                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                            } else {
                                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = gitRepoExpanded)
                                            }
                                        },
                                        modifier = Modifier.menuAnchor()
                                    )
                                    ExposedDropdownMenu(
                                        expanded = gitRepoExpanded && gitRepositories.isNotEmpty(),
                                        onDismissRequest = { gitRepoExpanded = false }
                                    ) {
                                        gitRepositories.forEach { repo ->
                                            DropdownMenuItem(
                                                text = {
                                                    Column {
                                                        Text(repo.name)
                                                        repo.description?.let {
                                                            Text(it, style = MaterialTheme.typography.labelSmall)
                                                        }
                                                    }
                                                },
                                                onClick = {
                                                    gitRepositoryIdentifier = repo.id
                                                    gitRepoExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // BugTracker Project Section
                    Text("BugTracker Project", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))

                    if (bugtrackerConnections.isEmpty()) {
                        Text(
                            "Klient nemá žádné BugTracker connections.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        var btConnectionExpanded by remember { mutableStateOf(false) }
                        var btProjectExpanded by remember { mutableStateOf(false) }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Connection dropdown
                            ExposedDropdownMenuBox(
                                expanded = btConnectionExpanded,
                                onExpandedChange = { btConnectionExpanded = it },
                                modifier = Modifier.weight(1f)
                            ) {
                                OutlinedTextField(
                                    value = bugtrackerConnections.find { it.id == bugtrackerConnectionId }?.name ?: "",
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Connection") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = btConnectionExpanded) },
                                    modifier = Modifier.menuAnchor()
                                )
                                ExposedDropdownMenu(
                                    expanded = btConnectionExpanded,
                                    onDismissRequest = { btConnectionExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("-- Žádné --") },
                                        onClick = {
                                            bugtrackerConnectionId = null
                                            bugtrackerProjectKey = ""
                                            bugtrackerProjects = emptyList()
                                            btConnectionExpanded = false
                                        }
                                    )
                                    bugtrackerConnections.forEach { conn ->
                                        DropdownMenuItem(
                                            text = { Text(conn.name) },
                                            onClick = {
                                                bugtrackerConnectionId = conn.id
                                                bugtrackerProjectKey = ""
                                                loadBugtrackerProjects(conn.id)
                                                btConnectionExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            // Project dropdown (when connection selected)
                            if (bugtrackerConnectionId != null) {
                                ExposedDropdownMenuBox(
                                    expanded = btProjectExpanded,
                                    onExpandedChange = { btProjectExpanded = it },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    OutlinedTextField(
                                        value = bugtrackerProjectKey,
                                        onValueChange = { bugtrackerProjectKey = it },
                                        label = { Text("Project Key") },
                                        trailingIcon = {
                                            if (loadingBugtrackerProjects) {
                                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                            } else {
                                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = btProjectExpanded)
                                            }
                                        },
                                        modifier = Modifier.menuAnchor()
                                    )
                                    ExposedDropdownMenu(
                                        expanded = btProjectExpanded && bugtrackerProjects.isNotEmpty(),
                                        onDismissRequest = { btProjectExpanded = false }
                                    ) {
                                        bugtrackerProjects.forEach { proj ->
                                            DropdownMenuItem(
                                                text = {
                                                    Column {
                                                        Text("${proj.id} - ${proj.name}")
                                                        proj.description?.let {
                                                            Text(it, style = MaterialTheme.typography.labelSmall)
                                                        }
                                                    }
                                                },
                                                onClick = {
                                                    bugtrackerProjectKey = proj.id
                                                    btProjectExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Wiki Space Section
                    Text("Wiki Space", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))

                    if (wikiConnections.isEmpty()) {
                        Text(
                            "Klient nemá žádné Wiki connections.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        var wikiConnectionExpanded by remember { mutableStateOf(false) }
                        var wikiSpaceExpanded by remember { mutableStateOf(false) }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Connection dropdown
                            ExposedDropdownMenuBox(
                                expanded = wikiConnectionExpanded,
                                onExpandedChange = { wikiConnectionExpanded = it },
                                modifier = Modifier.weight(1f)
                            ) {
                                OutlinedTextField(
                                    value = wikiConnections.find { it.id == wikiConnectionId }?.name ?: "",
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Connection") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = wikiConnectionExpanded) },
                                    modifier = Modifier.menuAnchor()
                                )
                                ExposedDropdownMenu(
                                    expanded = wikiConnectionExpanded,
                                    onDismissRequest = { wikiConnectionExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("-- Žádné --") },
                                        onClick = {
                                            wikiConnectionId = null
                                            wikiSpaceKey = ""
                                            wikiSpaces = emptyList()
                                            wikiConnectionExpanded = false
                                        }
                                    )
                                    wikiConnections.forEach { conn ->
                                        DropdownMenuItem(
                                            text = { Text(conn.name) },
                                            onClick = {
                                                wikiConnectionId = conn.id
                                                wikiSpaceKey = ""
                                                loadWikiSpaces(conn.id)
                                                wikiConnectionExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            // Space dropdown (when connection selected)
                            if (wikiConnectionId != null) {
                                ExposedDropdownMenuBox(
                                    expanded = wikiSpaceExpanded,
                                    onExpandedChange = { wikiSpaceExpanded = it },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    OutlinedTextField(
                                        value = wikiSpaceKey,
                                        onValueChange = { wikiSpaceKey = it },
                                        label = { Text("Space Key") },
                                        trailingIcon = {
                                            if (loadingWikiSpaces) {
                                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                            } else {
                                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = wikiSpaceExpanded)
                                            }
                                        },
                                        modifier = Modifier.menuAnchor()
                                    )
                                    ExposedDropdownMenu(
                                        expanded = wikiSpaceExpanded && wikiSpaces.isNotEmpty(),
                                        onDismissRequest = { wikiSpaceExpanded = false }
                                    ) {
                                        wikiSpaces.forEach { space ->
                                            DropdownMenuItem(
                                                text = {
                                                    Column {
                                                        Text("${space.id} - ${space.name}")
                                                        space.description?.let {
                                                            Text(it, style = MaterialTheme.typography.labelSmall)
                                                        }
                                                    }
                                                },
                                                onClick = {
                                                    wikiSpaceKey = space.id
                                                    wikiSpaceExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            JSection(title = "Přepsání Git Commit Konfigurace") {
                Text(
                    "Standardně se používá konfigurace z klienta. Zde můžete přepsat pro tento projekt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = useCustomGitConfig,
                        onCheckedChange = { useCustomGitConfig = it }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Přepsat konfiguraci klienta")
                }

                if (useCustomGitConfig) {
                    Spacer(Modifier.height(12.dp))

                    Text(
                        "Vlastní konfigurace pro tento projekt",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = gitCommitMessageFormat,
                        onValueChange = { gitCommitMessageFormat = it },
                        label = { Text("Formát commit message (volitelné)") },
                        placeholder = { Text("[{project}] {message}") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = gitCommitAuthorName,
                        onValueChange = { gitCommitAuthorName = it },
                        label = { Text("Jméno autora") },
                        placeholder = { Text("Agent Name") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = gitCommitAuthorEmail,
                        onValueChange = { gitCommitAuthorEmail = it },
                        label = { Text("Email autora") },
                        placeholder = { Text("agent@example.com") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(12.dp))

                    Text(
                        "Committer (ponechte prázdné pro použití autora)",
                        style = MaterialTheme.typography.labelMedium
                    )

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = gitCommitCommitterName,
                        onValueChange = { gitCommitCommitterName = it },
                        label = { Text("Jméno committera (volitelné)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = gitCommitCommitterEmail,
                        onValueChange = { gitCommitCommitterEmail = it },
                        label = { Text("Email committera (volitelné)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = gitCommitGpgSign,
                            onCheckedChange = { gitCommitGpgSign = it }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("GPG podpis commitů")
                    }

                    if (gitCommitGpgSign) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = gitCommitGpgKeyId,
                            onValueChange = { gitCommitGpgKeyId = it },
                            label = { Text("GPG Key ID") },
                            placeholder = { Text("např. ABCD1234") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        JActionBar {
            TextButton(onClick = onCancel) {
                Text("Zrušit")
            }
            Button(
                onClick = {
                    onSave(
                        project.copy(
                            name = name,
                            description = description.ifBlank { null },
                            gitRepositoryConnectionId = gitRepositoryConnectionId?.ifBlank { null },
                            gitRepositoryIdentifier = gitRepositoryIdentifier.ifBlank { null },
                            bugtrackerConnectionId = bugtrackerConnectionId?.ifBlank { null },
                            bugtrackerProjectKey = bugtrackerProjectKey.ifBlank { null },
                            wikiConnectionId = wikiConnectionId?.ifBlank { null },
                            wikiSpaceKey = wikiSpaceKey.ifBlank { null },
                            gitCommitMessageFormat = if (useCustomGitConfig) gitCommitMessageFormat.ifBlank { null } else null,
                            gitCommitAuthorName = if (useCustomGitConfig) gitCommitAuthorName.ifBlank { null } else null,
                            gitCommitAuthorEmail = if (useCustomGitConfig) gitCommitAuthorEmail.ifBlank { null } else null,
                            gitCommitCommitterName = if (useCustomGitConfig) gitCommitCommitterName.ifBlank { null } else null,
                            gitCommitCommitterEmail = if (useCustomGitConfig) gitCommitCommitterEmail.ifBlank { null } else null,
                            gitCommitGpgSign = if (useCustomGitConfig) gitCommitGpgSign else null,
                            gitCommitGpgKeyId = if (useCustomGitConfig) gitCommitGpgKeyId.ifBlank { null } else null
                        )
                    )
                },
                enabled = name.isNotBlank()
            ) {
                Text("Uložit")
            }
        }
    }
}
