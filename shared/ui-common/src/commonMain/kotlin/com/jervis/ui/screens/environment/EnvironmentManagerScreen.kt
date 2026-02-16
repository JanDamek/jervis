package com.jervis.ui.screens.environment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.text.font.FontFamily
import com.jervis.dto.environment.EnvironmentDto
import com.jervis.dto.environment.EnvironmentStateEnum
import com.jervis.dto.environment.EnvironmentStatusDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JErrorState
import com.jervis.ui.design.JListDetailLayout
import com.jervis.ui.design.JTopBar
import com.jervis.ui.design.JPrimaryButton
import com.jervis.ui.design.JervisSpacing
import com.jervis.ui.environment.EnvironmentStateBadge
import com.jervis.ui.screens.settings.sections.NewEnvironmentDialog
import kotlinx.coroutines.launch

/**
 * Environment Manager — standalone screen for full environment management.
 *
 * Layout: JListDetailLayout — left: environment list, right: tabbed detail.
 * Tabs: Přehled | Komponenty | K8s zdroje | Logy & Události
 *
 * Phase 2: Only Overview tab is fully functional; others show placeholder.
 */
@Composable
fun EnvironmentManagerScreen(
    repository: JervisRepository,
    onBack: () -> Unit,
    initialEnvironmentId: String? = null,
) {
    var environments by remember { mutableStateOf<List<EnvironmentDto>>(emptyList()) }
    var selectedEnv by remember { mutableStateOf<EnvironmentDto?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showNewDialog by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    fun loadEnvironments() {
        scope.launch {
            isLoading = true
            error = null
            try {
                environments = repository.environments.getAllEnvironments()
            } catch (e: Exception) {
                error = "Chyba při načítání prostředí: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadEnvironments() }

    // Deep-link: select initial environment once loaded
    LaunchedEffect(environments, initialEnvironmentId) {
        if (initialEnvironmentId != null && selectedEnv == null && environments.isNotEmpty()) {
            selectedEnv = environments.find { it.id == initialEnvironmentId }
        }
    }

    Scaffold(
        topBar = {
            JTopBar(title = "Správa prostředí")
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(JervisSpacing.outerPadding),
        ) {
            when {
                error != null && environments.isEmpty() -> {
                    JErrorState(
                        message = error!!,
                        onRetry = { loadEnvironments() },
                    )
                }

                else -> {
                    JListDetailLayout(
                        items = environments,
                        selectedItem = selectedEnv,
                        isLoading = isLoading,
                        onItemSelected = { selectedEnv = it },
                        emptyMessage = "Žádná prostředí",
                        emptyIcon = "\uD83D\uDCE6",
                        listHeader = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Prostředí",
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                JPrimaryButton(onClick = { showNewDialog = true }) {
                                    Text("Nové prostředí")
                                }
                            }
                        },
                        listItem = { env ->
                            EnvironmentListItem(
                                environment = env,
                                onClick = { selectedEnv = env },
                            )
                        },
                        detailContent = { env ->
                            EnvironmentDetail(
                                environment = env,
                                repository = repository,
                                onBack = { selectedEnv = null },
                                onDeleted = {
                                    selectedEnv = null
                                    loadEnvironments()
                                },
                                onUpdated = { loadEnvironments() },
                            )
                        },
                    )
                }
            }
        }
    }

    // New environment dialog
    if (showNewDialog) {
        NewEnvironmentDialog(
            repository = repository,
            onCreated = {
                showNewDialog = false
                loadEnvironments()
            },
            onDismiss = { showNewDialog = false },
        )
    }
}

/**
 * List item for environment in the left panel.
 */
@Composable
private fun EnvironmentListItem(
    environment: EnvironmentDto,
    onClick: () -> Unit,
) {
    com.jervis.ui.design.JCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(environment.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    environment.namespace,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "${environment.components.size} komponent",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            EnvironmentStateBadge(environment.state)
        }
    }
}

/**
 * Detail panel for selected environment — tabbed layout.
 */
@Composable
private fun EnvironmentDetail(
    environment: EnvironmentDto,
    repository: JervisRepository,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onUpdated: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(EnvironmentManagerTab.OVERVIEW) }
    var status by remember { mutableStateOf<EnvironmentStatusDto?>(null) }
    var statusLoading by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    // Load status when environment changes
    LaunchedEffect(environment.id) {
        if (environment.state == EnvironmentStateEnum.RUNNING ||
            environment.state == EnvironmentStateEnum.CREATING
        ) {
            statusLoading = true
            try {
                status = repository.environments.getEnvironmentStatus(environment.id)
            } catch (_: Exception) {
                status = null
            } finally {
                statusLoading = false
            }
        }
    }

    com.jervis.ui.design.JDetailScreen(
        title = environment.name,
        onBack = onBack,
    ) {
        // Tab row
        TabRow(
            selectedTabIndex = selectedTab.ordinal,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            EnvironmentManagerTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    text = { Text(tab.label) },
                )
            }
        }

        Spacer(Modifier.height(JervisSpacing.itemGap))

        // Tab content
        when (selectedTab) {
            EnvironmentManagerTab.OVERVIEW -> {
                OverviewTab(
                    environment = environment,
                    status = status,
                    onProvision = {
                        scope.launch {
                            try {
                                repository.environments.provisionEnvironment(environment.id)
                                onUpdated()
                            } catch (_: Exception) {}
                        }
                    },
                    onStop = {
                        scope.launch {
                            try {
                                repository.environments.deprovisionEnvironment(environment.id)
                                onUpdated()
                            } catch (_: Exception) {}
                        }
                    },
                    onDelete = {
                        scope.launch {
                            try {
                                repository.environments.deleteEnvironment(environment.id)
                                onDeleted()
                            } catch (_: Exception) {}
                        }
                    },
                )
            }

            EnvironmentManagerTab.COMPONENTS -> {
                JEmptyState(
                    message = "Správa komponent bude dostupná v další verzi",
                    icon = "\uD83D\uDEE0\uFE0F",
                )
            }

            EnvironmentManagerTab.K8S_RESOURCES -> {
                JEmptyState(
                    message = "Inspekce K8s zdrojů bude dostupná v další verzi",
                    icon = "\u2601\uFE0F",
                )
            }

            EnvironmentManagerTab.LOGS_EVENTS -> {
                JEmptyState(
                    message = "Logy a události budou dostupné v další verzi",
                    icon = "\uD83D\uDCDC",
                )
            }
        }
    }
}
