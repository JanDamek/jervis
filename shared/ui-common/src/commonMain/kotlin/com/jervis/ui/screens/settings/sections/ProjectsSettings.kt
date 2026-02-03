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
import com.jervis.dto.ProjectDto
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
            onSave = { updated ->
                scope.launch {
                    try {
                        repository.projects.updateProject(updated)
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

@Composable
private fun ProjectEditForm(
    project: ProjectDto,
    onSave: (ProjectDto) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf(project.name) }
    var description by remember { mutableStateOf(project.description ?: "") }

    // Resource identifiers from client's connections
    var gitRepositoryConnectionId by remember { mutableStateOf(project.gitRepositoryConnectionId) }
    var gitRepositoryIdentifier by remember { mutableStateOf(project.gitRepositoryIdentifier ?: "") }
    var bugtrackerConnectionId by remember { mutableStateOf(project.bugtrackerConnectionId) }
    var bugtrackerProjectKey by remember { mutableStateOf(project.bugtrackerProjectKey ?: "") }
    var wikiConnectionId by remember { mutableStateOf(project.wikiConnectionId) }
    var wikiSpaceKey by remember { mutableStateOf(project.wikiSpaceKey ?: "") }

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

    val scrollState = rememberScrollState()

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

            JSection(title = "Zdroje z připojení klienta") {
                Text(
                    "Přiřaďte konkrétní zdroje z connections dostupných pro klienta projektu",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))

                Text("Git Repository", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                if (gitRepositoryConnectionId != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Connection: $gitRepositoryConnectionId",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "Repository: ${gitRepositoryIdentifier.ifBlank { "neuvedeno" }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                            IconButton(
                                onClick = {
                                    gitRepositoryConnectionId = null
                                    gitRepositoryIdentifier = ""
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Text("✕", style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = gitRepositoryIdentifier,
                        onValueChange = { gitRepositoryIdentifier = it },
                        label = { Text("Repository identifier") },
                        placeholder = { Text("owner/repo nebo URL") },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        "Žádné Git repository nepřiřazeno. Přiřaďte connection ID ručně nebo použijte dropdown (TODO).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(12.dp))

                Text("BugTracker Project", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                if (bugtrackerConnectionId != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Connection: $bugtrackerConnectionId",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "Project Key: ${bugtrackerProjectKey.ifBlank { "neuvedeno" }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                            IconButton(
                                onClick = {
                                    bugtrackerConnectionId = null
                                    bugtrackerProjectKey = ""
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Text("✕", style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = bugtrackerProjectKey,
                        onValueChange = { bugtrackerProjectKey = it },
                        label = { Text("BugTracker Project Key") },
                        placeholder = { Text("např. PROJ") },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        "Žádný BugTracker Project nepřiřazen (volitelné).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(12.dp))

                Text("Wiki Space", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                if (wikiConnectionId != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Connection: $wikiConnectionId",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "Space Key: ${wikiSpaceKey.ifBlank { "neuvedeno" }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                            IconButton(
                                onClick = {
                                    wikiConnectionId = null
                                    wikiSpaceKey = ""
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Text("✕", style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = wikiSpaceKey,
                        onValueChange = { wikiSpaceKey = it },
                        label = { Text("Wiki Space Key") },
                        placeholder = { Text("např. DOCS") },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        "Žádný Wiki Space nepřiřazen (volitelné).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
