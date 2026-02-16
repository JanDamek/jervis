package com.jervis.ui.screens.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Icon
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
import com.jervis.dto.environment.ComponentTypeEnum
import com.jervis.dto.environment.EnvironmentDto
import com.jervis.dto.environment.EnvironmentStateEnum
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JActionBar
import com.jervis.ui.design.JCard
import com.jervis.ui.design.JDestructiveButton
import com.jervis.ui.design.JDetailScreen
import com.jervis.ui.design.JKeyValueRow
import com.jervis.ui.design.JListDetailLayout
import com.jervis.ui.design.JPrimaryButton
import com.jervis.ui.design.JSection
import com.jervis.ui.design.JStatusBadge
import com.jervis.ui.design.JervisSpacing
import com.jervis.ui.util.ConfirmDialog
import com.jervis.ui.util.RefreshIconButton
import kotlinx.coroutines.launch

/**
 * Simplified Environments section in Settings.
 *
 * Phase 3: Only create/delete environments here. For full configuration,
 * users are redirected to the Environment Manager screen via cross-link.
 */
@Composable
fun EnvironmentsSettings(
    repository: JervisRepository,
    onOpenInManager: (String) -> Unit = {},
) {
    var environments by remember { mutableStateOf<List<EnvironmentDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var selectedEnv by remember { mutableStateOf<EnvironmentDto?>(null) }
    var showNewDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun loadData() {
        scope.launch {
            isLoading = true
            try {
                environments = repository.environments.getAllEnvironments()
            } catch (_: Exception) {
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadData() }

    JListDetailLayout(
        items = environments,
        selectedItem = selectedEnv,
        isLoading = isLoading,
        onItemSelected = { selectedEnv = it },
        emptyMessage = "Žádná prostředí",
        emptyIcon = "\uD83C\uDF10",
        listHeader = {
            JActionBar {
                RefreshIconButton(onClick = { loadData() })
                Spacer(Modifier.width(8.dp))
                JPrimaryButton(onClick = { showNewDialog = true }) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Nové prostředí")
                }
            }
        },
        listItem = { env ->
            EnvironmentListCard(env = env, onClick = { selectedEnv = env })
        },
        detailContent = { env ->
            EnvironmentSummaryDetail(
                environment = env,
                onBack = { selectedEnv = null },
                onOpenInManager = { onOpenInManager(env.id) },
                onDelete = {
                    scope.launch {
                        try {
                            repository.environments.deleteEnvironment(env.id)
                            selectedEnv = null
                            loadData()
                        } catch (_: Exception) {
                        }
                    }
                },
            )
        },
    )

    if (showNewDialog) {
        NewEnvironmentDialog(
            repository = repository,
            onCreated = {
                showNewDialog = false
                loadData()
            },
            onDismiss = { showNewDialog = false },
        )
    }
}

/**
 * List card for environment in Settings sidebar.
 */
@Composable
private fun EnvironmentListCard(
    env: EnvironmentDto,
    onClick: () -> Unit,
) {
    JCard(onClick = onClick) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .heightIn(min = JervisSpacing.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(env.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    env.namespace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val infraCount = env.components.count { it.type != ComponentTypeEnum.PROJECT }
                    val projectCount = env.components.count { it.type == ComponentTypeEnum.PROJECT }
                    Text(
                        "$infraCount infra · $projectCount projekt",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    EnvironmentStateBadge(env.state)
                }
            }
        }
    }
}

/**
 * Simplified read-only detail for Settings — no editing, just summary + cross-link.
 */
@Composable
private fun EnvironmentSummaryDetail(
    environment: EnvironmentDto,
    onBack: () -> Unit,
    onOpenInManager: () -> Unit,
    onDelete: () -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    JDetailScreen(
        title = environment.name,
        onBack = onBack,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(JervisSpacing.sectionGap),
        ) {
            JSection(title = "Základní informace") {
                JKeyValueRow("Název", environment.name)
                JKeyValueRow("Namespace", environment.namespace)
                JKeyValueRow("Stav", environmentStateLabel(environment.state))
                environment.description?.let { desc ->
                    JKeyValueRow("Popis", desc)
                }
            }

            JSection(title = "Komponenty") {
                val infraCount = environment.components.count { it.type != ComponentTypeEnum.PROJECT }
                val projectCount = environment.components.count { it.type == ComponentTypeEnum.PROJECT }
                JKeyValueRow("Infrastruktura", "$infraCount")
                JKeyValueRow("Projekty", "$projectCount")
                if (environment.components.isNotEmpty()) {
                    Text(
                        environment.components.joinToString(", ") { it.name },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Cross-link to Environment Manager
            JSection(title = "Konfigurace") {
                Text(
                    "Detailní konfigurace komponent, portů, ENV proměnných a K8s zdrojů se provádí v Environment Manageru.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                JPrimaryButton(onClick = onOpenInManager) {
                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Otevřít v Environment Manageru")
                }
            }

            // Delete
            JSection(title = "Nebezpečná zóna") {
                JDestructiveButton(onClick = { showDeleteConfirm = true }) {
                    Text("Smazat prostředí")
                }
            }
        }
    }

    ConfirmDialog(
        visible = showDeleteConfirm,
        title = "Smazat prostředí?",
        message = "Tato akce je nevratná. Pokud je prostředí provisionované, bude nejdříve zastaveno.",
        confirmText = "Smazat",
        onConfirm = {
            showDeleteConfirm = false
            onDelete()
        },
        onDismiss = { showDeleteConfirm = false },
    )
}

@Composable
private fun EnvironmentStateBadge(state: EnvironmentStateEnum) {
    val status = when (state) {
        EnvironmentStateEnum.PENDING -> "Čeká"
        EnvironmentStateEnum.CREATING -> "Vytváří se"
        EnvironmentStateEnum.RUNNING -> "Běží"
        EnvironmentStateEnum.STOPPING -> "Zastavuje se"
        EnvironmentStateEnum.STOPPED -> "Zastaveno"
        EnvironmentStateEnum.ERROR -> "Chyba"
    }
    JStatusBadge(status = status)
}

private fun environmentStateLabel(state: EnvironmentStateEnum): String = when (state) {
    EnvironmentStateEnum.PENDING -> "Čeká"
    EnvironmentStateEnum.CREATING -> "Vytváří se"
    EnvironmentStateEnum.RUNNING -> "Běží"
    EnvironmentStateEnum.STOPPING -> "Zastavuje se"
    EnvironmentStateEnum.STOPPED -> "Zastaveno"
    EnvironmentStateEnum.ERROR -> "Chyba"
}
