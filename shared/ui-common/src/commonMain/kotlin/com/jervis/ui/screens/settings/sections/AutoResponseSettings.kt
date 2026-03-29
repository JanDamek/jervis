package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
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
import com.jervis.dto.agent.AutoResponseSettingsDto
import com.jervis.dto.agent.ResponseRuleDto
import com.jervis.di.JervisRepository
import com.jervis.ui.design.JCard
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JCheckboxRow
import com.jervis.ui.design.JDropdown
import com.jervis.ui.design.JErrorState
import com.jervis.ui.design.JIconButton
import com.jervis.ui.design.JPrimaryButton
import com.jervis.ui.design.JSnackbarHost
import com.jervis.ui.design.JSwitch
import com.jervis.ui.design.JTextField
import com.jervis.ui.design.JervisSpacing
import kotlinx.coroutines.launch

/**
 * Auto-response settings screen.
 *
 * Following existing settings patterns (OpenRouterSettings, ClientsSettings):
 * - Per-client toggle: "Automatické odpovědi" (default OFF)
 * - Per-client "Nikdy automaticky neodpovídat" checkbox
 * - Per-channel rules list (when expanded)
 * - Save button
 */
@Composable
internal fun AutoResponseSettings(
    repository: JervisRepository,
    clientId: String?,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // State
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    // Editable fields
    var enabled by remember { mutableStateOf(false) }
    var neverAutoResponse by remember { mutableStateOf(false) }
    var learningEnabled by remember { mutableStateOf(true) }
    var rules by remember { mutableStateOf<List<ResponseRuleDto>>(emptyList()) }
    var settingsId by remember { mutableStateOf<String?>(null) }

    // Channel rules (per-channel settings within the client scope)
    var channelRules by remember { mutableStateOf<List<ChannelRuleState>>(emptyList()) }
    var showChannelRules by remember { mutableStateOf(false) }

    suspend fun loadData() {
        isLoading = true
        error = null
        try {
            val settings = repository.autoResponseSettings.getSettings(clientId = clientId)
            if (settings != null) {
                settingsId = settings.id
                enabled = settings.enabled
                neverAutoResponse = settings.neverAutoResponse
                learningEnabled = settings.learningEnabled
                rules = settings.responseRules
            }
        } catch (e: Exception) {
            error = e.message ?: "Nepodařilo se načíst nastavení"
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(clientId) {
        loadData()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(JervisSpacing.outerPadding),
        verticalArrangement = Arrangement.spacedBy(JervisSpacing.sectionGap),
    ) {
        Text(
            text = "Automatické odpovědi",
            style = MaterialTheme.typography.headlineSmall,
        )

        if (clientId == null) {
            Text(
                text = "Vyberte klienta pro konfiguraci automatických odpovědí.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        when {
            isLoading -> JCenteredLoading()
            error != null -> JErrorState(message = error!!, onRetry = { scope.launch { loadData() } })
            else -> {
                // Main toggle
                JSwitch(
                    label = "Automatické odpovědi",
                    description = "Povolit agentům automaticky odpovídat na zprávy",
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                )

                // Never auto-respond checkbox
                JCheckboxRow(
                    label = "Nikdy automaticky neodpovídat",
                    checked = neverAutoResponse,
                    onCheckedChange = { neverAutoResponse = it },
                    enabled = !enabled,
                )

                // Learning toggle
                JSwitch(
                    label = "Učení z uživatelských úprav",
                    description = "Agent se učí z vašich úprav návrhů odpovědí",
                    checked = learningEnabled,
                    onCheckedChange = { learningEnabled = it },
                    enabled = enabled,
                )

                HorizontalDivider()

                // Response rules section
                Text(
                    text = "Pravidla odpovědí",
                    style = MaterialTheme.typography.titleMedium,
                )

                if (rules.isEmpty() && enabled) {
                    Text(
                        text = "Žádná pravidla. Přidejte pravidlo pro automatickou reakci na specifické zprávy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                rules.forEachIndexed { index, rule ->
                    ResponseRuleRow(
                        rule = rule,
                        onTriggerChange = { newTrigger ->
                            rules = rules.toMutableList().also {
                                it[index] = rule.copy(trigger = newTrigger)
                            }
                        },
                        onActionChange = { newAction ->
                            rules = rules.toMutableList().also {
                                it[index] = rule.copy(action = newAction)
                            }
                        },
                        onDelete = {
                            rules = rules.toMutableList().also { it.removeAt(index) }
                        },
                        enabled = enabled,
                    )
                }

                // Add rule button
                JIconButton(
                    onClick = {
                        rules = rules + ResponseRuleDto(trigger = "", action = "DRAFT")
                    },
                    icon = Icons.Default.Add,
                    contentDescription = "Přidat pravidlo",
                    enabled = enabled,
                )

                Spacer(Modifier.height(JervisSpacing.sectionGap))

                // Save button
                JPrimaryButton(
                    onClick = {
                        scope.launch {
                            isSaving = true
                            try {
                                val dto = AutoResponseSettingsDto(
                                    id = settingsId,
                                    clientId = clientId,
                                    enabled = enabled,
                                    neverAutoResponse = neverAutoResponse,
                                    learningEnabled = learningEnabled,
                                    responseRules = rules.filter { it.trigger.isNotBlank() },
                                )
                                repository.autoResponseSettings.saveSettings(dto)
                                snackbarHostState.showSnackbar("Nastavení uloženo")
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("Chyba: ${e.message}")
                            } finally {
                                isSaving = false
                            }
                        }
                    },
                    enabled = !isSaving,
                ) {
                    Text(if (isSaving) "Ukládání..." else "Uložit")
                }

                JSnackbarHost(hostState = snackbarHostState)
            }
        }
    }
}

/**
 * Row for editing a single response rule (trigger -> action).
 */
@Composable
private fun ResponseRuleRow(
    rule: ResponseRuleDto,
    onTriggerChange: (String) -> Unit,
    onActionChange: (String) -> Unit,
    onDelete: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val actionOptions = listOf("DRAFT", "AUTO", "OFF")
    val actionLabels = mapOf(
        "DRAFT" to "Draft",
        "AUTO" to "Automaticky",
        "OFF" to "Vypnuto",
    )

    JCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            JTextField(
                value = rule.trigger,
                onValueChange = onTriggerChange,
                label = "Trigger",
                placeholder = "Klíčové slovo nebo vzor",
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            JDropdown(
                items = actionOptions,
                selectedItem = rule.action,
                onItemSelected = onActionChange,
                label = "Akce",
                itemLabel = { actionLabels[it] ?: it },
                enabled = enabled,
                modifier = Modifier.width(140.dp),
            )
            JIconButton(
                onClick = onDelete,
                icon = Icons.Default.Delete,
                contentDescription = "Smazat pravidlo",
                tint = MaterialTheme.colorScheme.error,
                enabled = enabled,
            )
        }
    }
}

/**
 * Internal state for per-channel rule configuration.
 */
private data class ChannelRuleState(
    val channelType: String = "",
    val channelId: String = "",
    val mode: String = "OFF", // OFF, DRAFT, AUTO
    val rules: List<ResponseRuleDto> = emptyList(),
)
