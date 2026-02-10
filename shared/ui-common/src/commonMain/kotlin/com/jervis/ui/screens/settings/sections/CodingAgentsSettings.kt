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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
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
import com.jervis.dto.coding.CodingAgentSetupTokenUpdateDto
import com.jervis.dto.coding.CodingAgentSettingsDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JCard
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JPrimaryButton
import com.jervis.ui.design.JSecondaryButton
import com.jervis.ui.design.JSection
import com.jervis.ui.design.JSnackbarHost
import com.jervis.ui.design.JTextField
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
            JCenteredLoading()
        } else {
            SelectionContainer {
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
                            onSaveSetupToken = { token ->
                                scope.launch {
                                    try {
                                        val updated = repository.codingAgents.updateSetupToken(
                                            CodingAgentSetupTokenUpdateDto(
                                                agentName = agent.name,
                                                token = token,
                                            ),
                                        )
                                        settings = updated
                                        snackbarHostState.showSnackbar("Setup token ulozen pro ${agent.displayName}")
                                    } catch (e: Exception) {
                                        snackbarHostState.showSnackbar("Chyba: ${e.message}")
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }

        JSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
        )
    }
}

@Composable
private fun CodingAgentCard(
    agent: CodingAgentConfigDto,
    onSaveApiKey: (String) -> Unit,
    onSaveSetupToken: (String) -> Unit,
) {
    var apiKeyInput by remember { mutableStateOf("") }
    var setupTokenInput by remember { mutableStateOf("") }

    JCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(agent.displayName, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                val isAuthenticated = agent.apiKeySet || agent.setupTokenConfigured
                SuggestionChip(
                    onClick = {},
                    label = {
                        val statusText = when {
                            agent.setupTokenConfigured -> "Max/Pro ucet"
                            agent.apiKeySet -> "API klic"
                            else -> "Nenastaveno"
                        }
                        Text(
                            statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isAuthenticated) {
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
                // Setup token section for Max/Pro subscription accounts
                if (agent.supportsSetupToken) {
                    Spacer(Modifier.height(12.dp))

                    JSection(title = "Max/Pro predplatne (doporuceno)") {
                        Text(
                            text = "Spustte lokalne 'claude setup-token', dokoncete prihlaseni v prohlizeci a vlozite vygenerovany token.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            JTextField(
                                value = setupTokenInput,
                                onValueChange = { setupTokenInput = it },
                                label = "Setup Token",
                                placeholder = if (agent.setupTokenConfigured) {
                                    "Token nastaven - vlozit novy pro zmenu"
                                } else {
                                    "sk-ant-oat01-..."
                                },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(12.dp))
                            JPrimaryButton(
                                onClick = {
                                    onSaveSetupToken(setupTokenInput)
                                    setupTokenInput = ""
                                },
                                enabled = setupTokenInput.isNotBlank(),
                            ) {
                                Text("Ulozit")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                JSection(title = if (agent.supportsSetupToken) "API klic (alternativa)" else "API klic") {
                    // "Ziskat API klic" button - opens provider console in browser
                    if (agent.consoleUrl.isNotBlank()) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            JSecondaryButton(
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
                        JTextField(
                            value = apiKeyInput,
                            onValueChange = { apiKeyInput = it },
                            label = "API Key",
                            placeholder = if (agent.apiKeySet) "Zadejte novy klic pro zmenu" else "Zadejte API klic",
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
