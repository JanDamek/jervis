package com.jervis.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
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
import com.jervis.ui.design.*
import kotlinx.coroutines.launch

@Composable
fun RagSearchScreen(
    repository: JervisRepository,
    onBack: () -> Unit,
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

    val scope = rememberCoroutineScope()

    // Load clients and projects on mount
    LaunchedEffect(Unit) {
        isLoadingData = true
        try {
            clients = repository.clients.getAllClients()
            projects = repository.projects.getAllProjects()
            if (clients.isNotEmpty()) {
                selectedClient = clients[0]
            }
        } catch (e: Exception) {
            errorMessage = "Chyba při načítání dat: ${e.message}"
        } finally {
            isLoadingData = false
        }
    }

    fun performSearch() {
        if (searchQuery.isBlank()) {
            errorMessage = "Zadejte vyhledávací dotaz"
            return
        }
        if (selectedClient == null) {
            errorMessage = "Vyberte klienta"
            return
        }

        val maxChunksInt = maxChunks.toIntOrNull() ?: 20
        val minScoreDouble = minScore.toDoubleOrNull() ?: 0.15

        if (maxChunksInt !in 1..1000) {
            errorMessage = "Max fragmentů musí být mezi 1 a 1000"
            return
        }
        if (minScoreDouble !in 0.0..1.0) {
            errorMessage = "Min skóre musí být mezi 0.0 a 1.0"
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
                    minSimilarityThreshold = minScoreDouble,
                )
                val response = repository.ragSearch.search(request)
                searchResults = response.items
                resultCount = response.items.size
                if (searchResults.isNotEmpty()) {
                    selectedResult = searchResults[0]
                }
            } catch (e: Exception) {
                errorMessage = "Hledání selhalo: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets.safeDrawing,
        topBar = {
            JTopBar(
                title = "RAG Hledání${if (resultCount > 0) " - $resultCount výsledků" else ""}",
                onBack = onBack,
            )
        },
    ) { padding ->
        Row(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            // Left panel - Search form
            Column(
                modifier = Modifier.width(400.dp).fillMaxHeight()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                JSection(title = "Hledání") {
                    JTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = "Dotaz",
                        enabled = !isLoading,
                    )
                }

                JSection(title = "Filtry") {
                    JDropdown(
                        items = clients,
                        selectedItem = selectedClient,
                        onItemSelected = { selectedClient = it },
                        label = "Klient",
                        itemLabel = { it.name },
                        enabled = !isLoading && !isLoadingData,
                    )

                    Spacer(Modifier.height(8.dp))

                    JDropdown(
                        items = listOf<ProjectDto?>(null) + projects,
                        selectedItem = selectedProject,
                        onItemSelected = { selectedProject = it },
                        label = "Projekt (volitelné)",
                        itemLabel = { it?.name ?: "<Všechny projekty>" },
                        enabled = !isLoading && !isLoadingData,
                    )

                    Spacer(Modifier.height(8.dp))

                    JTextField(
                        value = filterKey,
                        onValueChange = { filterKey = it },
                        label = "Klíč filtru (volitelné)",
                        enabled = !isLoading,
                    )

                    JTextField(
                        value = filterValue,
                        onValueChange = { filterValue = it },
                        label = "Hodnota filtru (volitelné)",
                        enabled = !isLoading,
                    )
                }

                JSection(title = "Parametry") {
                    JTextField(
                        value = maxChunks,
                        onValueChange = { maxChunks = it },
                        label = "Max fragmentů (1-1000)",
                        enabled = !isLoading,
                    )

                    JTextField(
                        value = minScore,
                        onValueChange = { minScore = it },
                        label = "Min skóre (0.0-1.0)",
                        enabled = !isLoading,
                    )
                }

                JPrimaryButton(
                    onClick = { performSearch() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading && searchQuery.isNotBlank() && selectedClient != null,
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if (isLoading) "Hledám..." else "Hledat")
                }

                errorMessage?.let { error ->
                    JCard(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = error,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            VerticalDivider()

            // Right panel - Results
            Row(
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                // Results list
                Column(
                    modifier = Modifier.weight(0.4f).fillMaxHeight(),
                ) {
                    Text(
                        text = "Výsledky ($resultCount)",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                    HorizontalDivider()

                    if (searchResults.isEmpty() && !isLoading) {
                        JEmptyState(message = "Žádné výsledky. Proveďte hledání.", icon = "🔍")
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(searchResults) { result ->
                                JCard(
                                    selected = selectedResult == result,
                                    modifier = Modifier.clickable { selectedResult = result },
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(
                                                text = result.metadata["sourceType"] ?: "Neznámý",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                            Text(
                                                text = result.score.toString().take(5),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Text(
                                            text = result.content.take(150) + if (result.content.length > 150) "..." else "",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(top = 4.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                VerticalDivider()

                // Result detail
                Column(
                    modifier = Modifier.weight(0.6f).fillMaxHeight(),
                ) {
                    Text(
                        text = "Detaily",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                    HorizontalDivider()

                    if (selectedResult != null) {
                        SelectionContainer {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                JSection {
                                    val scoreText = selectedResult!!.score.toString().take(6)
                                    JKeyValueRow("Skóre", scoreText)
                                    selectedResult!!.metadata["sourceType"]?.let {
                                        JKeyValueRow("Typ zdroje", it)
                                    }
                                    selectedResult!!.metadata["sourceUri"]?.let {
                                        JKeyValueRow("URI zdroje", it)
                                    }
                                    selectedResult!!.metadata["filePath"]?.let {
                                        JKeyValueRow("Cesta k souboru", it)
                                    }
                                }

                                Text(
                                    text = "Obsah:",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                JCard {
                                    Text(
                                        text = selectedResult!!.content,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(12.dp),
                                    )
                                }

                                if (selectedResult!!.metadata.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Všechna metadata:",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    JSection {
                                        selectedResult!!.metadata.forEach { (key, value) ->
                                            JKeyValueRow(key, value)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "Vyberte výsledek pro zobrazení detailů",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
