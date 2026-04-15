package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
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
import com.jervis.di.JervisRepository
import com.jervis.dto.client.ClientDto
import com.jervis.dto.urgency.FastPathDeadlinesDto
import com.jervis.dto.urgency.PresenceFactorDto
import com.jervis.dto.urgency.UrgencyConfigDto
import com.jervis.ui.LocalRpcGeneration
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JPrimaryButton
import com.jervis.ui.design.JSection
import com.jervis.ui.design.JSnackbarHost
import com.jervis.ui.design.JervisSpacing
import kotlinx.coroutines.launch

/**
 * Settings tab "Urgency & Deadlines" — per-client configuration of fast-path deadlines
 * (DM / mention / reply-to-my-thread), presence factors, TTL, classifier budget, and
 * approaching-deadline threshold. Client is picked via a dropdown; "Uložit" persists
 * the full UrgencyConfigDto (update_urgency_config semantics).
 */
@Composable
internal fun UrgencySettings(repository: JervisRepository) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var clients by remember { mutableStateOf<List<ClientDto>>(emptyList()) }
    var selectedClient by remember { mutableStateOf<ClientDto?>(null) }
    var clientDropdownOpen by remember { mutableStateOf(false) }

    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }

    // Editable fields — ints as strings for TextField binding
    var defaultDeadlineMinutes by remember { mutableStateOf("30") }
    var dmMinutes by remember { mutableStateOf("2") }
    var mentionMinutes by remember { mutableStateOf("5") }
    var replyActiveMinutes by remember { mutableStateOf("5") }
    var replyStaleMinutes by remember { mutableStateOf("10") }
    var factorActive by remember { mutableStateOf("1.0") }
    var factorAwayRecent by remember { mutableStateOf("1.5") }
    var factorAwayOld by remember { mutableStateOf("5.0") }
    var factorOffline by remember { mutableStateOf("10.0") }
    var factorUnknown by remember { mutableStateOf("1.0") }
    var presenceTtl by remember { mutableStateOf("120") }
    var classifierBudget by remember { mutableStateOf("5") }
    var approachingPct by remember { mutableStateOf("0.20") }

    fun populateFromDto(dto: UrgencyConfigDto) {
        defaultDeadlineMinutes = dto.defaultDeadlineMinutes.toString()
        dmMinutes = dto.fastPathDeadlineMinutes.directMessage.toString()
        mentionMinutes = dto.fastPathDeadlineMinutes.channelMention.toString()
        replyActiveMinutes = dto.fastPathDeadlineMinutes.replyMyThreadActive.toString()
        replyStaleMinutes = dto.fastPathDeadlineMinutes.replyMyThreadStale.toString()
        factorActive = dto.presenceFactor.active.toString()
        factorAwayRecent = dto.presenceFactor.awayRecent.toString()
        factorAwayOld = dto.presenceFactor.awayOld.toString()
        factorOffline = dto.presenceFactor.offline.toString()
        factorUnknown = dto.presenceFactor.unknown.toString()
        presenceTtl = dto.presenceTtlSeconds.toString()
        classifierBudget = dto.classifierBudgetPerHourPerSender.toString()
        approachingPct = dto.approachingDeadlineThresholdPct.toString()
    }

    val rpcGeneration = LocalRpcGeneration.current
    LaunchedEffect(rpcGeneration) {
        try {
            clients = repository.clients.getAllClients().filter { !it.archived }
            selectedClient = clients.firstOrNull()
            selectedClient?.let {
                populateFromDto(repository.urgencyConfig.getUrgencyConfig(it.id))
            }
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("Chyba načítání: ${e.message}")
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(selectedClient?.id) {
        val clientId = selectedClient?.id ?: return@LaunchedEffect
        try {
            populateFromDto(repository.urgencyConfig.getUrgencyConfig(clientId))
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("Chyba načítání konfigurace: ${e.message}")
        }
    }

    if (isLoading) {
        JCenteredLoading()
        return
    }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Client picker — plain TextField (read-only) + DropdownMenu anchored to a
        // trailing icon. Keeps cross-platform compatibility (ExposedDropdownMenu has
        // differing signatures across material3 revisions).
        JSection(title = "Klient") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextField(
                    value = selectedClient?.name ?: "—",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Vybraný klient") },
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = { clientDropdownOpen = true }) {
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Vybrat klienta")
                    }
                    DropdownMenu(
                        expanded = clientDropdownOpen,
                        onDismissRequest = { clientDropdownOpen = false },
                    ) {
                        clients.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(c.name) },
                                onClick = {
                                    selectedClient = c
                                    clientDropdownOpen = false
                                },
                            )
                        }
                    }
                }
            }
        }

        JSection(title = "Výchozí deadline") {
            LabeledNumber("Default (min)", defaultDeadlineMinutes) { defaultDeadlineMinutes = it }
        }

        JSection(title = "Fast-path deadliny (minuty)") {
            Text(
                "Strukturální signály z inbound zpráv. Presence factor tyto hodnoty násobí.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            LabeledNumber("Direct message (DM)", dmMinutes) { dmMinutes = it }
            LabeledNumber("@mention v kanále", mentionMinutes) { mentionMinutes = it }
            LabeledNumber("Reply na můj thread (aktivní)", replyActiveMinutes) { replyActiveMinutes = it }
            LabeledNumber("Reply na můj thread (stale)", replyStaleMinutes) { replyStaleMinutes = it }
        }

        JSection(title = "Presence factors (násobič deadlinu)") {
            LabeledNumber("Aktivní", factorActive) { factorActive = it }
            LabeledNumber("Nedávno away (< 5 min)", factorAwayRecent) { factorAwayRecent = it }
            LabeledNumber("Away (hodiny)", factorAwayOld) { factorAwayOld = it }
            LabeledNumber("Offline", factorOffline) { factorOffline = it }
            LabeledNumber("Neznámá (API nedostupné)", factorUnknown) { factorUnknown = it }
        }

        JSection(title = "Ostatní") {
            LabeledNumber("Presence cache TTL (sekundy)", presenceTtl) { presenceTtl = it }
            LabeledNumber("Classifier budget / hod / sender", classifierBudget) { classifierBudget = it }
            LabeledNumber("Approaching deadline threshold (podíl)", approachingPct) { approachingPct = it }
        }

        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            JPrimaryButton(
                text = if (isSaving) "Ukládám..." else "Uložit",
                onClick = {
                    val clientId = selectedClient?.id ?: return@JPrimaryButton
                    scope.launch {
                        isSaving = true
                        try {
                            val dto = UrgencyConfigDto(
                                clientId = clientId,
                                defaultDeadlineMinutes = defaultDeadlineMinutes.toIntOrNull() ?: 30,
                                fastPathDeadlineMinutes = FastPathDeadlinesDto(
                                    directMessage = dmMinutes.toIntOrNull() ?: 2,
                                    channelMention = mentionMinutes.toIntOrNull() ?: 5,
                                    replyMyThreadActive = replyActiveMinutes.toIntOrNull() ?: 5,
                                    replyMyThreadStale = replyStaleMinutes.toIntOrNull() ?: 10,
                                ),
                                presenceFactor = PresenceFactorDto(
                                    active = factorActive.toDoubleOrNull() ?: 1.0,
                                    awayRecent = factorAwayRecent.toDoubleOrNull() ?: 1.5,
                                    awayOld = factorAwayOld.toDoubleOrNull() ?: 5.0,
                                    offline = factorOffline.toDoubleOrNull() ?: 10.0,
                                    unknown = factorUnknown.toDoubleOrNull() ?: 1.0,
                                ),
                                presenceTtlSeconds = presenceTtl.toIntOrNull() ?: 120,
                                classifierBudgetPerHourPerSender = classifierBudget.toIntOrNull() ?: 5,
                                approachingDeadlineThresholdPct = approachingPct.toDoubleOrNull() ?: 0.20,
                            )
                            repository.urgencyConfig.updateUrgencyConfig(dto)
                            snackbarHostState.showSnackbar("Nastavení uloženo.")
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar("Chyba při ukládání: ${e.message}")
                        } finally {
                            isSaving = false
                        }
                    }
                },
                enabled = !isSaving && selectedClient != null,
            )
        }

        JSnackbarHost(snackbarHostState)
    }
}

@Composable
private fun LabeledNumber(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(JervisSpacing.touchTarget + 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.width(260.dp))
        TextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
