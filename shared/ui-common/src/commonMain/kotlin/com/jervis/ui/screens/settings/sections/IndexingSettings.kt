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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
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
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.indexing.PollingIntervalSettingsDto
import com.jervis.dto.indexing.PollingIntervalUpdateDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JErrorState
import com.jervis.ui.design.JPrimaryButton
import com.jervis.ui.design.JSecondaryButton
import com.jervis.ui.design.JSection
import com.jervis.ui.design.JSnackbarHost
import com.jervis.ui.design.JTextField
import com.jervis.ui.design.JervisSpacing
import kotlinx.coroutines.launch

/**
 * Indexing settings screen.
 * Configures polling intervals per ConnectionCapability (Git, Jira, Wiki, Email).
 * Also provides "Zkontrolovat nyní" buttons for on-demand polling.
 */
@Composable
fun IndexingSettings(repository: JervisRepository) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Editable state: capability → interval string (for text field)
    var repositoryInterval by remember { mutableStateOf("30") }
    var bugtrackerInterval by remember { mutableStateOf("10") }
    var wikiInterval by remember { mutableStateOf("60") }
    var emailReadInterval by remember { mutableStateOf("1") }

    fun applyFromDto(dto: PollingIntervalSettingsDto) {
        repositoryInterval = (dto.intervals[ConnectionCapability.REPOSITORY] ?: 30).toString()
        bugtrackerInterval = (dto.intervals[ConnectionCapability.BUGTRACKER] ?: 10).toString()
        wikiInterval = (dto.intervals[ConnectionCapability.WIKI] ?: 60).toString()
        emailReadInterval = (dto.intervals[ConnectionCapability.EMAIL_READ] ?: 1).toString()
    }

    fun loadSettings() {
        scope.launch {
            isLoading = true
            try {
                val dto = repository.pollingIntervals.getSettings()
                applyFromDto(dto)
                error = null
            } catch (e: Exception) {
                error = "Chyba načítání: ${e.message}"
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { loadSettings() }

    fun saveSettings() {
        scope.launch {
            try {
                val intervals = mapOf(
                    ConnectionCapability.REPOSITORY to (repositoryInterval.toIntOrNull() ?: 30),
                    ConnectionCapability.BUGTRACKER to (bugtrackerInterval.toIntOrNull() ?: 10),
                    ConnectionCapability.WIKI to (wikiInterval.toIntOrNull() ?: 60),
                    ConnectionCapability.EMAIL_READ to (emailReadInterval.toIntOrNull() ?: 1),
                )
                val updated = repository.pollingIntervals.updateSettings(
                    PollingIntervalUpdateDto(intervals = intervals),
                )
                applyFromDto(updated)
                snackbarHostState.showSnackbar("Nastavení uloženo")
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Chyba: ${e.message}")
            }
        }
    }

    fun triggerPoll(capability: ConnectionCapability) {
        scope.launch {
            try {
                repository.pollingIntervals.triggerPollNow(capability.name)
                snackbarHostState.showSnackbar("Kontrola spuštěna: ${getCapabilityLabel(capability)}")
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Chyba: ${e.message}")
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            isLoading -> JCenteredLoading()
            error != null -> JErrorState(
                message = error!!,
                onRetry = { loadSettings() },
            )
            else -> SelectionContainer {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // === Info ===
                    Text(
                        "Nastavení intervalů pro automatické zjišťování nových položek. " +
                            "Změny platí globálně pro všechna připojení daného typu.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // === Git (Repository) ===
                    JSection(title = "Git repozitáře") {
                        IntervalRow(
                            value = repositoryInterval,
                            onValueChange = { repositoryInterval = it },
                            description = "Kontrola nových commitů, větví a pull requestů",
                            defaultMinutes = 30,
                            onPollNow = { triggerPoll(ConnectionCapability.REPOSITORY) },
                        )
                    }

                    // === Bugtracker (Jira) ===
                    JSection(title = "Bug tracker (Jira, GitHub Issues)") {
                        IntervalRow(
                            value = bugtrackerInterval,
                            onValueChange = { bugtrackerInterval = it },
                            description = "Kontrola nových a změněných ticketů",
                            defaultMinutes = 10,
                            onPollNow = { triggerPoll(ConnectionCapability.BUGTRACKER) },
                        )
                    }

                    // === Wiki (Confluence) ===
                    JSection(title = "Wiki (Confluence)") {
                        IntervalRow(
                            value = wikiInterval,
                            onValueChange = { wikiInterval = it },
                            description = "Kontrola nových a upravených stránek",
                            defaultMinutes = 60,
                            onPollNow = { triggerPoll(ConnectionCapability.WIKI) },
                        )
                    }

                    // === Email ===
                    JSection(title = "E-mail (IMAP/POP3)") {
                        IntervalRow(
                            value = emailReadInterval,
                            onValueChange = { emailReadInterval = it },
                            description = "Kontrola nových e-mailů",
                            defaultMinutes = 1,
                            onPollNow = { triggerPoll(ConnectionCapability.EMAIL_READ) },
                        )
                    }

                    // === Save Button ===
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(JervisSpacing.outerPadding),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        JPrimaryButton(onClick = { saveSettings() }) {
                            Text("Uložit nastavení")
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }
        }

        JSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
        )
    }
}

/**
 * Row with interval text field + "Zkontrolovat nyní" button.
 */
@Composable
private fun IntervalRow(
    value: String,
    onValueChange: (String) -> Unit,
    description: String,
    defaultMinutes: Int,
    onPollNow: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(JervisSpacing.fieldGap)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            JTextField(
                value = value,
                onValueChange = { newVal ->
                    // Allow only digits
                    onValueChange(newVal.filter { it.isDigit() })
                },
                label = "Interval (minuty)",
                placeholder = "$defaultMinutes",
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            JSecondaryButton(
                onClick = onPollNow,
                modifier = Modifier.height(JervisSpacing.touchTarget),
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Zkontrolovat nyní",
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Zkontrolovat nyní")
            }
        }
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val parsed = value.toIntOrNull()
        if (parsed != null && (parsed < 1 || parsed > 1440)) {
            Text(
                "Interval musí být 1–1440 minut",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// Uses getCapabilityLabel from ClientsSettings.kt (internal)
