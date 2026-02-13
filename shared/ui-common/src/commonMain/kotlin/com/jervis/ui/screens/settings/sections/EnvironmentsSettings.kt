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
import androidx.compose.material.icons.filled.KeyboardArrowRight
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
import com.jervis.ui.design.JListDetailLayout
import com.jervis.ui.design.JPrimaryButton
import com.jervis.ui.design.JStatusBadge
import com.jervis.ui.design.JervisSpacing
import com.jervis.ui.util.RefreshIconButton
import kotlinx.coroutines.launch

@Composable
fun EnvironmentsSettings(repository: JervisRepository) {
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
        emptyIcon = "🌐",
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
            JCard(
                onClick = { selectedEnv = env },
            ) {
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
                                "${infraCount} infra · ${projectCount} projekt",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            EnvironmentStateBadge(env.state)
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        detailContent = { env ->
            EnvironmentEditForm(
                environment = env,
                repository = repository,
                onSave = { updated ->
                    scope.launch {
                        try {
                            repository.environments.updateEnvironment(updated.id, updated)
                            selectedEnv = null
                            loadData()
                        } catch (_: Exception) {
                        }
                    }
                },
                onProvision = {
                    scope.launch {
                        try {
                            repository.environments.provisionEnvironment(env.id)
                            loadData()
                        } catch (_: Exception) {
                        }
                    }
                },
                onDeprovision = {
                    scope.launch {
                        try {
                            repository.environments.deprovisionEnvironment(env.id)
                            loadData()
                        } catch (_: Exception) {
                        }
                    }
                },
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
                onCancel = { selectedEnv = null },
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
