package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jervis.dto.coding.CodingAgentApiKeyUpdateDto
import com.jervis.dto.coding.CodingAgentConfigDto
import com.jervis.dto.coding.CodingAgentSettingsDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JPrimaryButton
import com.jervis.ui.design.JSection
import com.jervis.ui.util.openUrlInBrowser
import kotlinx.coroutines.launch

@Composable
fun CodingAgentsSettings(repository: JervisRepository) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var settings by remember { mutableStateOf<CodingAgentSettingsDto?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isLoading = true
        try {
            settings = repository.codingAgents.getSettings()
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("Chyba nacitani: ${e.message}")
        }
        isLoading = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isLoading && settings == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                val agents = settings?.agents ?: emptyList()
                items(agents) { agent ->
                    CodingAgentCard(
                        agent = agent,
                        onSaveApiKey = { apiKey ->
                            scope.launch {
                                try {
                                    val updated = repository.codingAgents.updateApiKey(
                                        CodingAgentApiKeyUpdateDto(
                                            agentName = agent.name,
                                            apiKey = apiKey,
                                        ),
                                    )
                                    settings = updated
                                    snackbarHostState.showSnackbar("API klic ulozen pro ${agent.displayName}")
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar("Chyba: ${e.message}")
                                }
                            }
                        },
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
        )
    }
}

@Composable
private fun CodingAgentCard(
    agent: CodingAgentConfigDto,
    onSaveApiKey: (String) -> Unit,
) {
    var apiKeyInput by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(agent.displayName, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                SuggestionChip(
                    onClick = {},
                    label = {
                        Text(
                            if (agent.apiKeySet) "Aktivni" else "Nenastaveno",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (agent.apiKeySet) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    },
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = "Provider: ${agent.provider} | Model: ${agent.model}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (agent.requiresApiKey) {
                Spacer(Modifier.height(12.dp))

                JSection(title = "API klic") {
                    // "Ziskat API klic" button - opens provider console in browser
                    if (agent.consoleUrl.isNotBlank()) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { openUrlInBrowser(agent.consoleUrl) },
                            ) {
                                Text("Ziskat API klic")
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Otevre ${agent.consoleUrl.substringAfter("://").substringBefore("/")} v prohlizeci",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.CenterVertically),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = { apiKeyInput = it },
                            label = { Text("API Key") },
                            placeholder = {
                                Text(if (agent.apiKeySet) "Zadejte novy klic pro zmenu" else "Zadejte API klic")
                            },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        JPrimaryButton(
                            onClick = {
                                onSaveApiKey(apiKeyInput)
                                apiKeyInput = ""
                            },
                            enabled = apiKeyInput.isNotBlank(),
                        ) {
                            Text("Ulozit")
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Pouziva lokalni Ollama - API klic neni potreba",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
