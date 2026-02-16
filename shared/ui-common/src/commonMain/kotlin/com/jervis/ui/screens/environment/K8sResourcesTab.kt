package com.jervis.ui.screens.environment

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import com.jervis.dto.environment.EnvironmentDto
import com.jervis.dto.environment.K8sDeploymentDetailDto
import com.jervis.dto.environment.K8sDeploymentDto
import com.jervis.dto.environment.K8sNamespaceStatusDto
import com.jervis.dto.environment.K8sPodDto
import com.jervis.dto.environment.K8sResourceListDto
import com.jervis.dto.environment.K8sServiceDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.JActionBar
import com.jervis.ui.design.JCard
import com.jervis.ui.design.JCenteredLoading
import com.jervis.ui.design.JEmptyState
import com.jervis.ui.design.JErrorState
import com.jervis.ui.design.JKeyValueRow
import com.jervis.ui.design.JRefreshButton
import com.jervis.ui.design.JSection
import com.jervis.ui.design.JStatusBadge
import com.jervis.ui.design.JervisSpacing
import kotlinx.coroutines.launch

/**
 * K8s Resources tab — displays pods, deployments and services for an environment.
 *
 * Migrated from EnvironmentViewerScreen into a reusable tab that fits
 * inside the Environment Manager's tabbed detail panel.
 */
@Composable
fun K8sResourcesTab(
    environment: EnvironmentDto,
    repository: JervisRepository,
) {
    var resources by remember { mutableStateOf<K8sResourceListDto?>(null) }
    var nsStatus by remember { mutableStateOf<K8sNamespaceStatusDto?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Section expansion
    var podsExpanded by remember { mutableStateOf(true) }
    var deploymentsExpanded by remember { mutableStateOf(true) }
    var servicesExpanded by remember { mutableStateOf(false) }

    // Dialogs
    var logDialog by remember { mutableStateOf<String?>(null) } // podName
    var logContent by remember { mutableStateOf<String?>(null) }
    var logLoading by remember { mutableStateOf(false) }
    var deploymentDetail by remember { mutableStateOf<K8sDeploymentDetailDto?>(null) }
    var deploymentDetailLoading by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    fun loadResources() {
        scope.launch {
            isLoading = true
            error = null
            try {
                resources = repository.environmentResources.listResources(environment.id)
                nsStatus = repository.environmentResources.getNamespaceStatus(environment.id)
            } catch (e: Exception) {
                error = "Chyba při načítání K8s zdrojů: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    fun loadPodLogs(podName: String) {
        scope.launch {
            logLoading = true
            logContent = null
            try {
                logContent = repository.environmentResources.getPodLogs(environment.id, podName, 200)
            } catch (e: Exception) {
                logContent = "Chyba: ${e.message}"
            } finally {
                logLoading = false
            }
        }
    }

    fun loadDeploymentDetail(name: String) {
        scope.launch {
            deploymentDetailLoading = true
            try {
                deploymentDetail = repository.environmentResources.getDeploymentDetails(environment.id, name)
            } catch (e: Exception) {
                error = "Chyba: ${e.message}"
            } finally {
                deploymentDetailLoading = false
            }
        }
    }

    // Load resources when environment changes
    LaunchedEffect(environment.id) {
        loadResources()
    }

    when {
        isLoading -> JCenteredLoading()

        error != null && resources == null -> JErrorState(
            message = error!!,
            onRetry = { loadResources() },
        )

        resources != null -> {
            Column {
                // Namespace health summary
                nsStatus?.let { status ->
                    NamespaceHealthSummary(status = status)
                    Spacer(Modifier.height(JervisSpacing.sectionGap))
                }

                JActionBar {
                    JRefreshButton(onClick = { loadResources() })
                }

                Spacer(Modifier.height(JervisSpacing.itemGap))

                // Resource list
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
                ) {
                    // --- Pods section ---
                    item {
                        CollapsibleSectionHeader(
                            title = "Pody (${resources!!.pods.size})",
                            expanded = podsExpanded,
                            onToggle = { podsExpanded = !podsExpanded },
                        )
                    }
                    if (podsExpanded) {
                        items(resources!!.pods, key = { it.name }) { pod ->
                            PodCard(
                                pod = pod,
                                onViewLogs = {
                                    logDialog = pod.name
                                    loadPodLogs(pod.name)
                                },
                            )
                        }
                    }

                    // --- Deployments section ---
                    item {
                        Spacer(Modifier.height(JervisSpacing.itemGap))
                        CollapsibleSectionHeader(
                            title = "Deploymenty (${resources!!.deployments.size})",
                            expanded = deploymentsExpanded,
                            onToggle = { deploymentsExpanded = !deploymentsExpanded },
                        )
                    }
                    if (deploymentsExpanded) {
                        items(resources!!.deployments, key = { it.name }) { dep ->
                            DeploymentCard(
                                deployment = dep,
                                onViewDetails = { loadDeploymentDetail(dep.name) },
                                onRestart = {
                                    scope.launch {
                                        try {
                                            repository.environmentResources.restartDeployment(
                                                environment.id, dep.name,
                                            )
                                            loadResources()
                                        } catch (e: Exception) {
                                            error = "Restart selhal: ${e.message}"
                                        }
                                    }
                                },
                            )
                        }
                    }

                    // --- Services section ---
                    item {
                        Spacer(Modifier.height(JervisSpacing.itemGap))
                        CollapsibleSectionHeader(
                            title = "Služby (${resources!!.services.size})",
                            expanded = servicesExpanded,
                            onToggle = { servicesExpanded = !servicesExpanded },
                        )
                    }
                    if (servicesExpanded) {
                        items(resources!!.services, key = { it.name }) { svc ->
                            ServiceCard(service = svc)
                        }
                    }
                }
            }
        }

        else -> JEmptyState(message = "Žádné K8s zdroje")
    }

    // --- Pod Log Dialog ---
    logDialog?.let { podName ->
        PodLogDialog(
            podName = podName,
            logContent = logContent,
            isLoading = logLoading,
            onRefresh = { loadPodLogs(podName) },
            onDismiss = { logDialog = null; logContent = null },
        )
    }

    // --- Deployment Detail Dialog ---
    deploymentDetail?.let { detail ->
        DeploymentDetailDialog(
            detail = detail,
            isLoading = deploymentDetailLoading,
            onDismiss = { deploymentDetail = null },
        )
    }
}

// ── Sub-components ──────────────────────────────────────────────────────────────

@Composable
private fun NamespaceHealthSummary(status: K8sNamespaceStatusDto) {
    JSection(title = "Stav namespace: ${status.namespace}") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
        ) {
            Icon(
                imageVector = if (status.healthy) Icons.Filled.CheckCircle else Icons.Filled.Error,
                contentDescription = null,
                tint = if (status.healthy) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Text(
                if (status.healthy) "Vše v pořádku" else "Problémy detekovány",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        JKeyValueRow("Pody", "${status.runningPods}/${status.totalPods} běží")
        JKeyValueRow("Deploymenty", "${status.readyDeployments}/${status.totalDeployments} připraveno")
        JKeyValueRow("Služby", "${status.totalServices}")
        if (status.crashingPods.isNotEmpty()) {
            Text(
                "Crashing: ${status.crashingPods.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun CollapsibleSectionHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = JervisSpacing.itemGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "Sbalit" else "Rozbalit",
            modifier = Modifier.size(JervisSpacing.touchTarget),
        )
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun PodCard(
    pod: K8sPodDto,
    onViewLogs: () -> Unit,
) {
    JCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(JervisSpacing.sectionPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(pod.name, style = MaterialTheme.typography.titleSmall)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
                ) {
                    JStatusBadge(
                        status = if (pod.ready) "ok"
                        else if (pod.phase == "Running") "pending"
                        else "error",
                    )
                    Text(
                        pod.phase ?: "Unknown",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (pod.restartCount > 0) {
                        Text(
                            "Restarty: ${pod.restartCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (pod.restartCount > 5) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            TextButton(onClick = onViewLogs) {
                Text("Logy")
            }
        }
    }
}

@Composable
private fun DeploymentCard(
    deployment: K8sDeploymentDto,
    onViewDetails: () -> Unit,
    onRestart: () -> Unit,
) {
    JCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(JervisSpacing.sectionPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(deployment.name, style = MaterialTheme.typography.titleSmall)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
                ) {
                    JStatusBadge(status = if (deployment.ready) "ok" else "error")
                    Text(
                        "${deployment.availableReplicas}/${deployment.replicas} replik",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                deployment.image?.let { img ->
                    Text(
                        img.substringAfterLast("/"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onViewDetails) { Text("Detail") }
                TextButton(onClick = onRestart) { Text("Restart") }
            }
        }
    }
}

@Composable
private fun ServiceCard(service: K8sServiceDto) {
    JCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(JervisSpacing.sectionPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(service.name, style = MaterialTheme.typography.titleSmall)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
                ) {
                    Text(
                        service.type ?: "ClusterIP",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    service.clusterIP?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (service.ports.isNotEmpty()) {
                    Text(
                        service.ports.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ── Dialogs ─────────────────────────────────────────────────────────────────────

@Composable
private fun PodLogDialog(
    podName: String,
    logContent: String?,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Logy: $podName") },
        text = {
            if (isLoading) {
                JCenteredLoading()
            } else {
                Text(
                    text = logContent ?: "",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().height(400.dp).verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Zavřít")
            }
        },
        dismissButton = {
            TextButton(onClick = onRefresh) {
                Text("Obnovit")
            }
        },
    )
}

@Composable
private fun DeploymentDetailDialog(
    detail: K8sDeploymentDetailDto,
    isLoading: Boolean,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Deployment: ${detail.name}") },
        text = {
            if (isLoading) {
                JCenteredLoading()
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
                ) {
                    JKeyValueRow("Image", detail.image ?: "-")
                    JKeyValueRow("Repliky", "${detail.availableReplicas}/${detail.replicas}")
                    JKeyValueRow("Stav", if (detail.ready) "Připraveno" else "Nepřipraveno")

                    if (detail.conditions.isNotEmpty()) {
                        Text("Podmínky:", style = MaterialTheme.typography.titleSmall)
                        detail.conditions.forEach { cond ->
                            Text(
                                "${cond.type}: ${cond.status} — ${cond.message ?: ""}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    if (detail.events.isNotEmpty()) {
                        Spacer(Modifier.height(JervisSpacing.itemGap))
                        Text("Události:", style = MaterialTheme.typography.titleSmall)
                        detail.events.forEach { event ->
                            Text(
                                "[${event.type ?: ""}] ${event.reason ?: ""}: ${event.message ?: ""}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Zavřít")
            }
        },
    )
}
