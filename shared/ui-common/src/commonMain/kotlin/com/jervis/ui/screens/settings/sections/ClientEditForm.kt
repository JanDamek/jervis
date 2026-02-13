package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.ClientConnectionCapabilityDto
import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectConnectionCapabilityDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.ProjectResourceDto
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ConnectionResourceDto
import com.jervis.dto.connection.ConnectionResponseDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.*
import kotlinx.coroutines.launch

@Composable
internal fun ClientEditForm(
    client: ClientDto,
    repository: JervisRepository,
    onSave: (ClientDto) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf(client.name) }
    var description by remember { mutableStateOf(client.description ?: "") }
    var archived by remember { mutableStateOf(client.archived) }

    // Git commit configuration
    var gitCommitMessageFormat by remember { mutableStateOf(client.gitCommitMessageFormat ?: "") }
    var gitCommitMessagePattern by remember { mutableStateOf(client.gitCommitMessagePattern ?: "") }
    var gitCommitAuthorName by remember { mutableStateOf(client.gitCommitAuthorName ?: "") }
    var gitCommitAuthorEmail by remember { mutableStateOf(client.gitCommitAuthorEmail ?: "") }
    var gitCommitCommitterName by remember { mutableStateOf(client.gitCommitCommitterName ?: "") }
    var gitCommitCommitterEmail by remember { mutableStateOf(client.gitCommitCommitterEmail ?: "") }
    var gitCommitGpgSign by remember { mutableStateOf(client.gitCommitGpgSign) }
    var gitCommitGpgKeyId by remember { mutableStateOf(client.gitCommitGpgKeyId ?: "") }
    var gitTopCommitters by remember { mutableStateOf(client.gitTopCommitters) }

    // Git analysis state
    var analyzingGit by remember { mutableStateOf(false) }
    var gitAnalysisResult by remember { mutableStateOf<com.jervis.dto.git.GitAnalysisResultDto?>(null) }
    var showGitAnalysisDialog by remember { mutableStateOf(false) }

    // Cloud model policy
    var autoUseAnthropic by remember { mutableStateOf(client.autoUseAnthropic) }
    var autoUseOpenai by remember { mutableStateOf(client.autoUseOpenai) }
    var autoUseGemini by remember { mutableStateOf(client.autoUseGemini) }

    // Connections
    var selectedConnectionIds by remember { mutableStateOf(client.connectionIds.toMutableSet()) }
    var availableConnections by remember { mutableStateOf<List<ConnectionResponseDto>>(emptyList()) }

    // Capability configuration
    var connectionCapabilities by remember {
        mutableStateOf(client.connectionCapabilities.toMutableList())
    }
    var availableResources by remember {
        mutableStateOf<Map<Pair<String, ConnectionCapability>, List<ConnectionResourceDto>>>(emptyMap())
    }
    var loadingResources by remember { mutableStateOf<Set<Pair<String, ConnectionCapability>>>(emptySet()) }

    // Projects
    var projects by remember { mutableStateOf<List<ProjectDto>>(emptyList()) }

    val scope = rememberCoroutineScope()

    fun loadConnections() {
        scope.launch {
            try {
                availableConnections = repository.connections.getAllConnections()
            } catch (_: Exception) {
            }
        }
    }

    fun loadProjects() {
        scope.launch {
            try {
                projects = repository.projects.listProjectsForClient(client.id)
            } catch (_: Exception) {
            }
        }
    }

    fun loadResourcesForCapability(connectionId: String, capability: ConnectionCapability) {
        val key = Pair(connectionId, capability)
        if (key in availableResources || key in loadingResources) return

        scope.launch {
            loadingResources = loadingResources + key
            try {
                val resources = repository.connections.listAvailableResources(connectionId, capability)
                availableResources = availableResources + (key to resources)
            } catch (_: Exception) {
                availableResources = availableResources + (key to emptyList())
            } finally {
                loadingResources = loadingResources - key
            }
        }
    }

    fun getCapabilityConfig(connectionId: String, capability: ConnectionCapability): ClientConnectionCapabilityDto? {
        return connectionCapabilities.find { it.connectionId == connectionId && it.capability == capability }
    }

    fun updateCapabilityConfig(config: ClientConnectionCapabilityDto) {
        connectionCapabilities = connectionCapabilities
            .filter { !(it.connectionId == config.connectionId && it.capability == config.capability) }
            .toMutableList()
            .apply { add(config) }
    }

    fun removeCapabilityConfig(connectionId: String, capability: ConnectionCapability) {
        connectionCapabilities = connectionCapabilities
            .filter { !(it.connectionId == connectionId && it.capability == capability) }
            .toMutableList()
    }

    LaunchedEffect(Unit) {
        loadConnections()
        loadProjects()
    }

    LaunchedEffect(availableConnections, selectedConnectionIds.size) {
        selectedConnectionIds.forEach { connId ->
            val connection = availableConnections.firstOrNull { it.id == connId }
            connection?.capabilities?.forEach { capability ->
                loadResourcesForCapability(connId, capability)
            }
        }
    }

    fun findLinkedProject(connectionId: String, capability: ConnectionCapability, resourceId: String): ProjectDto? {
        return projects.find { project ->
            project.resources.any { res ->
                res.connectionId == connectionId &&
                    res.capability == capability &&
                    res.resourceIdentifier == resourceId
            }
        }
    }

    JDetailScreen(
        title = client.name,
        onBack = onCancel,
        onSave = {
            onSave(
                client.copy(
                    name = name,
                    description = description.ifBlank { null },
                    archived = archived,
                    connectionIds = selectedConnectionIds.toList(),
                    connectionCapabilities = connectionCapabilities,
                    gitCommitMessageFormat = gitCommitMessageFormat.ifBlank { null },
                    gitCommitMessagePattern = gitCommitMessagePattern.ifBlank { null },
                    gitCommitAuthorName = gitCommitAuthorName.ifBlank { null },
                    gitCommitAuthorEmail = gitCommitAuthorEmail.ifBlank { null },
                    gitCommitCommitterName = gitCommitCommitterName.ifBlank { null },
                    gitCommitCommitterEmail = gitCommitCommitterEmail.ifBlank { null },
                    gitCommitGpgSign = gitCommitGpgSign,
                    gitCommitGpgKeyId = gitCommitGpgKeyId.ifBlank { null },
                    gitTopCommitters = gitTopCommitters,
                    autoUseAnthropic = autoUseAnthropic,
                    autoUseOpenai = autoUseOpenai,
                    autoUseGemini = autoUseGemini,
                ),
            )
        },
        saveEnabled = name.isNotBlank(),
    ) {
        val scrollState = rememberScrollState()

        SelectionContainer {
            Column(
                modifier = androidx.compose.ui.Modifier
                    .weight(1f)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                JSection(title = "Základní údaje") {
                    JTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = "Název klienta",
                    )
                    Spacer(androidx.compose.ui.Modifier.height(JervisSpacing.itemGap))
                    JTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = "Popis",
                        singleLine = false,
                        minLines = 2,
                    )
                    Spacer(androidx.compose.ui.Modifier.height(JervisSpacing.itemGap))
                    JCheckboxRow(
                        label = "Archivovat klienta",
                        checked = archived,
                        onCheckedChange = { archived = it },
                    )
                }

                ClientConnectionsSection(
                    selectedConnectionIds = selectedConnectionIds,
                    availableConnections = availableConnections,
                    onConnectionsChanged = {},
                )

                // Capability configuration section
                JSection(title = "Konfigurace schopností") {
                    Text(
                        "Nastavte, které zdroje z připojení se mají indexovat pro tohoto klienta.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(androidx.compose.ui.Modifier.height(12.dp))

                    if (selectedConnectionIds.isEmpty()) {
                        Text(
                            "Nejprve přiřaďte alespoň jedno připojení.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        selectedConnectionIds.forEach { connId ->
                            val connection = availableConnections.firstOrNull { it.id == connId }
                            if (connection != null && connection.capabilities.isNotEmpty()) {
                                ConnectionCapabilityCard(
                                    connection = connection,
                                    capabilities = connectionCapabilities,
                                    availableResources = availableResources,
                                    loadingResources = loadingResources,
                                    onLoadResources = { capability -> loadResourcesForCapability(connId, capability) },
                                    onUpdateConfig = { config -> updateCapabilityConfig(config) },
                                    onRemoveConfig = { capability -> removeCapabilityConfig(connId, capability) },
                                    getConfig = { capability -> getCapabilityConfig(connId, capability) },
                                )
                            }
                        }
                    }
                }

                // Provider resources → project creation section
                JSection(title = "Dostupné zdroje z providerů") {
                    Text(
                        "Zdroje z připojených služeb. Můžete vytvořit projekt propojený s daným zdrojem.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(androidx.compose.ui.Modifier.height(12.dp))

                    if (selectedConnectionIds.isEmpty()) {
                        Text(
                            "Nejprve přiřaďte alespoň jedno připojení.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        selectedConnectionIds.forEach { connId ->
                            val connection = availableConnections.firstOrNull { it.id == connId }
                            if (connection != null && connection.capabilities.isNotEmpty()) {
                                ProviderResourcesCard(
                                    connection = connection,
                                    availableResources = availableResources,
                                    loadingResources = loadingResources,
                                    findLinkedProject = { capability, resourceId ->
                                        findLinkedProject(connId, capability, resourceId)
                                    },
                                    onCreateProject = { resource, capability ->
                                        scope.launch {
                                            try {
                                                repository.projects.saveProject(
                                                    ProjectDto(
                                                        name = resource.name,
                                                        description = resource.description,
                                                        clientId = client.id,
                                                        connectionCapabilities = listOf(
                                                            ProjectConnectionCapabilityDto(
                                                                connectionId = connId,
                                                                capability = capability,
                                                                enabled = true,
                                                                resourceIdentifier = resource.id,
                                                                selectedResources = listOf(resource.id),
                                                            ),
                                                        ),
                                                        resources = listOf(
                                                            ProjectResourceDto(
                                                                connectionId = connId,
                                                                capability = capability,
                                                                resourceIdentifier = resource.id,
                                                                displayName = resource.name,
                                                            ),
                                                        ),
                                                    ),
                                                )
                                                loadProjects()
                                            } catch (_: Exception) {
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                ClientProjectsSection(
                    clientId = client.id,
                    projects = projects,
                    repository = repository,
                    onProjectsChanged = { loadProjects() },
                )

                JSection(title = "Výchozí Git Commit Konfigurace") {
                    Text(
                        "Tato konfigurace bude použita pro všechny projekty klienta (pokud projekt nepřepíše).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(androidx.compose.ui.Modifier.height(12.dp))

                    // Analyze Git button
                    JPrimaryButton(
                        onClick = {
                            scope.launch {
                                analyzingGit = true
                                try {
                                    gitAnalysisResult = repository.clients.analyzeGitRepositories(client.id)
                                    showGitAnalysisDialog = true
                                } catch (e: Exception) {
                                    // TODO: show error snackbar
                                } finally {
                                    analyzingGit = false
                                }
                            }
                        },
                        enabled = !analyzingGit,
                    ) {
                        if (analyzingGit) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = androidx.compose.ui.Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(androidx.compose.ui.Modifier.width(8.dp))
                        }
                        Text(if (analyzingGit) "Analyzuji..." else "Analyzovat Git repozitáře")
                    }

                    Spacer(androidx.compose.ui.Modifier.height(12.dp))

                    GitCommitConfigFields(
                        messageFormat = gitCommitMessageFormat,
                        onMessageFormatChange = { gitCommitMessageFormat = it },
                        messagePattern = gitCommitMessagePattern,
                        onMessagePatternChange = { gitCommitMessagePattern = it },
                        authorName = gitCommitAuthorName,
                        onAuthorNameChange = { gitCommitAuthorName = it },
                        authorEmail = gitCommitAuthorEmail,
                        onAuthorEmailChange = { gitCommitAuthorEmail = it },
                        committerName = gitCommitCommitterName,
                        onCommitterNameChange = { gitCommitCommitterName = it },
                        committerEmail = gitCommitCommitterEmail,
                        onCommitterEmailChange = { gitCommitCommitterEmail = it },
                        gpgSign = gitCommitGpgSign,
                        onGpgSignChange = { gitCommitGpgSign = it },
                        gpgKeyId = gitCommitGpgKeyId,
                        onGpgKeyIdChange = { gitCommitGpgKeyId = it },
                    )
                }

                JSection(title = "Cloud modely") {
                    Text(
                        "Automatická eskalace na cloud modely při selhání lokálního modelu.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(androidx.compose.ui.Modifier.height(8.dp))
                    JCheckboxRow(
                        label = "Anthropic (Claude) – reasoning, analýza, architektura",
                        checked = autoUseAnthropic,
                        onCheckedChange = { autoUseAnthropic = it },
                    )
                    JCheckboxRow(
                        label = "OpenAI (GPT-4o) – editace kódu, strukturovaný výstup",
                        checked = autoUseOpenai,
                        onCheckedChange = { autoUseOpenai = it },
                    )
                    JCheckboxRow(
                        label = "Google Gemini – pouze pro extrémní kontext (>49k tokenů)",
                        checked = autoUseGemini,
                        onCheckedChange = { autoUseGemini = it },
                    )
                }

                // Bottom spacing
                Spacer(androidx.compose.ui.Modifier.height(16.dp))
            }
        }

        // Git Analysis Results Dialog
        if (showGitAnalysisDialog && gitAnalysisResult != null) {
            GitAnalysisResultDialog(
                result = gitAnalysisResult!!,
                onDismiss = { showGitAnalysisDialog = false },
                onApply = { result ->
                    // Apply detected pattern
                    result.detectedPattern?.let { pattern ->
                        gitCommitMessagePattern = pattern
                    }
                    // Apply GPG settings
                    gitCommitGpgSign = result.usesGpgSigning
                    if (result.gpgKeyIds.isNotEmpty()) {
                        gitCommitGpgKeyId = result.gpgKeyIds.first()
                    }
                    // Store top committers
                    gitTopCommitters = result.topCommitters.map { "${it.name} <${it.email}>" }
                    showGitAnalysisDialog = false
                },
            )
        }
    }
}

/**
 * Dialog showing Git repository analysis results.
 */
@Composable
private fun GitAnalysisResultDialog(
    result: com.jervis.dto.git.GitAnalysisResultDto,
    onDismiss: () -> Unit,
    onApply: (com.jervis.dto.git.GitAnalysisResultDto) -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = androidx.compose.ui.Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Column(
                modifier = androidx.compose.ui.Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Title
                Text(
                    "Výsledky analýzy Git",
                    style = MaterialTheme.typography.headlineSmall,
                )

                androidx.compose.material3.HorizontalDivider()

                // Results content
                Column(
                    modifier = androidx.compose.ui.Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Top committers
                    if (result.topCommitters.isNotEmpty()) {
                        Text(
                            "Top Committers:",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        result.topCommitters.take(5).forEach { committer ->
                            Row(
                                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = androidx.compose.ui.Modifier.weight(1f)) {
                                    Text(committer.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        committer.email,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    "${committer.commitCount} commitů",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Spacer(androidx.compose.ui.Modifier.height(8.dp))
                    }

                    // Detected pattern
                    result.detectedPattern?.let { pattern ->
                        Text(
                            "Detekovaný pattern:",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        androidx.compose.material3.Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                pattern,
                                modifier = androidx.compose.ui.Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                ),
                            )
                        }
                        Spacer(androidx.compose.ui.Modifier.height(8.dp))
                    }

                    // GPG signing
                    Text(
                        "GPG Podepisování:",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(
                            if (result.usesGpgSigning) {
                                Icons.Default.Check
                            } else {
                                Icons.Default.Close
                            },
                            contentDescription = null,
                            tint = if (result.usesGpgSigning) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = androidx.compose.ui.Modifier.size(20.dp),
                        )
                        Spacer(androidx.compose.ui.Modifier.width(8.dp))
                        Text(
                            if (result.usesGpgSigning) "Používá se" else "Nepoužívá se",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (result.usesGpgSigning && result.gpgKeyIds.isNotEmpty()) {
                        Text(
                            "Klíče: ${result.gpgKeyIds.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Sample messages
                    if (result.sampleMessages.isNotEmpty()) {
                        Spacer(androidx.compose.ui.Modifier.height(8.dp))
                        Text(
                            "Ukázky commit messages:",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        result.sampleMessages.take(5).forEach { msg ->
                            Text(
                                "• $msg",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                androidx.compose.material3.HorizontalDivider()

                // Action buttons
                Row(
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    JSecondaryButton(onClick = onDismiss) {
                        Text("Zavřít")
                    }
                    JPrimaryButton(onClick = { onApply(result) }) {
                        Text("Aplikovat do konfigurace")
                    }
                }
            }
        }
    }
}
