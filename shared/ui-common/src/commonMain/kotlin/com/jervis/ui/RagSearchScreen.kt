package com.jervis.ui

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
import com.jervis.dto.rag.RagSearchItemDto
import com.jervis.dto.rag.RagSearchRequestDto
import com.jervis.repository.JervisRepository
import kotlinx.coroutines.launch
import com.jervis.ui.design.JTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RagSearchScreen(
    repository: JervisRepository,
    onBack: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var clients by remember { mutableStateOf<List<ClientDto>>(emptyList()) }
    var projects by remember { mutableStateOf<List<ProjectDto>>(emptyList()) }
    var selectedClient by remember { mutableStateOf<ClientDto?>(null) }
    var selectedProject by remember { mutableStateOf<ProjectDto?>(null) }
    var filterKey by remember { mutableStateOf("") }
    var filterValue by remember { mutableStateOf("") }
    var maxChunks by remember { mutableStateOf("100") }
    var minScore by remember { mutableStateOf("0.0") }

    var searchResults by remember { mutableStateOf<List<RagSearchItemDto>>(emptyList()) }
    var selectedResult by remember { mutableStateOf<RagSearchItemDto?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var isLoadingData by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var resultCount by remember { mutableStateOf(0) }

    var clientDropdownExpanded by remember { mutableStateOf(false) }
    var projectDropdownExpanded by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    // Load clients and projects on mount
    LaunchedEffect(Unit) {
        isLoadingData = true
        try {
            clients = repository.clients.listClients()
            projects = repository.projects.getAllProjects()
            if (clients.isNotEmpty()) {
                selectedClient = clients[0]
            }
        } catch (e: Exception) {
            errorMessage = "Failed to load data: ${e.message}"
        } finally {
            isLoadingData = false
        }
    }

    fun performSearch() {
        if (searchQuery.isBlank()) {
            errorMessage = "Please enter a search query"
            return
        }
        if (selectedClient == null) {
            errorMessage = "Please select a client"
            return
        }

        val maxChunksInt = maxChunks.toIntOrNull() ?: 20
        val minScoreDouble = minScore.toDoubleOrNull() ?: 0.15

        if (maxChunksInt !in 1..1000) {
            errorMessage = "Max Chunks must be between 1 and 1000"
            return
        }
        if (minScoreDouble !in 0.0..1.0) {
            errorMessage = "Min Score must be between 0.0 and 1.0"
            return
        }

        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                val request = RagSearchRequestDto(
                    clientId = selectedClient!!.id,
                    projectId = selectedProject?.id,
                    searchText = searchQuery,
                    filterKey = filterKey.ifBlank { null },
                    filterValue = filterValue.ifBlank { null },
                    maxChunks = maxChunksInt,
                    minSimilarityThreshold = minScoreDouble
                )
                val response = repository.ragSearch.search(request)
                searchResults = response.items
                resultCount = response.items.size
                if (searchResults.isNotEmpty()) {
                    selectedResult = searchResults[0]
                }
            } catch (e: Exception) {
                errorMessage = "Search failed: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets.safeDrawing,
        topBar = {
            JTopBar(
                title = "RAG Search${if (resultCount > 0) " - $resultCount results" else ""}",
                onBack = onBack,
            )
        }
    ) { padding ->
        Row(
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            // Left panel - Search form
            Column(
                modifier = Modifier.width(400.dp).fillMaxHeight()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Search Query", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Query") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                )

                HorizontalDivider()

                Text("Filters", style = MaterialTheme.typography.titleSmall)

                // Client dropdown
                ExposedDropdownMenuBox(
                    expanded = clientDropdownExpanded,
                    onExpandedChange = { clientDropdownExpanded = !clientDropdownExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedClient?.name ?: "Select Client",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Client") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = clientDropdownExpanded) },
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true).fillMaxWidth(),
                        enabled = !isLoading && !isLoadingData
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

                // Project dropdown
                ExposedDropdownMenuBox(
                    expanded = projectDropdownExpanded,
                    onExpandedChange = { projectDropdownExpanded = !projectDropdownExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedProject?.name ?: "<All Projects>",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Project (Optional)") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = projectDropdownExpanded) },
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true).fillMaxWidth(),
                        enabled = !isLoading && !isLoadingData
                    )
                    ExposedDropdownMenu(
                        expanded = projectDropdownExpanded,
                        onDismissRequest = { projectDropdownExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("<All Projects>") },
                            onClick = {
                                selectedProject = null
                                projectDropdownExpanded = false
                            }
                        )
                        projects.forEach { project ->
                            DropdownMenuItem(
                                text = { Text(project.name) },
                                onClick = {
                                    selectedProject = project
                                    projectDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = filterKey,
                    onValueChange = { filterKey = it },
                    label = { Text("Filter Key (Optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                )

                OutlinedTextField(
                    value = filterValue,
                    onValueChange = { filterValue = it },
                    label = { Text("Filter Value (Optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                )

                HorizontalDivider()

                Text("Search Parameters", style = MaterialTheme.typography.titleSmall)

                OutlinedTextField(
                    value = maxChunks,
                    onValueChange = { maxChunks = it },
                    label = { Text("Max Chunks (1-1000)") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                )

                OutlinedTextField(
                    value = minScore,
                    onValueChange = { minScore = it },
                    label = { Text("Min Score (0.0-1.0)") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                )

                Button(
                    onClick = { performSearch() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading && searchQuery.isNotBlank() && selectedClient != null
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if (isLoading) "Searching..." else "🔍 Search")
                }

                errorMessage?.let { error ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = error,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            VerticalDivider()

            // Right panel - Results
            Row(
                modifier = Modifier.weight(1f).fillMaxHeight()
            ) {
                // Results list
                Column(
                    modifier = Modifier.weight(0.4f).fillMaxHeight()
                ) {
                    Text(
                        text = "Results ($resultCount)",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                    HorizontalDivider()

                    if (searchResults.isEmpty() && !isLoading) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No results. Perform a search.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(searchResults) { result ->
                                RagResultCard(
                                    result = result,
                                    isSelected = selectedResult == result,
                                    onClick = { selectedResult = result }
                                )
                            }
                        }
                    }
                }

                VerticalDivider()

                // Result detail
                Column(
                    modifier = Modifier.weight(0.6f).fillMaxHeight()
                ) {
                    Text(
                        text = "Details",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                    HorizontalDivider()

                    if (selectedResult != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            val scoreText = selectedResult!!.score.toString().take(6)
                            RagDetailField("Score", scoreText)
                            selectedResult!!.metadata["sourceType"]?.let {
                                RagDetailField("Source Type", it)
                            }
                            selectedResult!!.metadata["sourceUri"]?.let {
                                RagDetailField("Source URI", it)
                            }
                            selectedResult!!.metadata["filePath"]?.let {
                                RagDetailField("File Path", it)
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Content:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Text(
                                    text = selectedResult!!.content,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }

                            if (selectedResult!!.metadata.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "All Metadata:",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                selectedResult!!.metadata.forEach { (key, value) ->
                                    RagDetailField(key, value)
                                }
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Select a result to view details",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RagResultCard(
    result: RagSearchItemDto,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isSelected) 4.dp else 1.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = result.metadata["sourceType"] ?: "Unknown",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = result.score.toString().take(5),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = result.content.take(150) + if (result.content.length > 150) "..." else "",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun RagDetailField(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
