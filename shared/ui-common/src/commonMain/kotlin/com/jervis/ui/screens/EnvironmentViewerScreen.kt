package com.jervis.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.jervis.dto.environment.EnvironmentStateEnum
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
import com.jervis.ui.design.JTopBar
import com.jervis.ui.design.JervisSpacing
import kotlinx.coroutines.launch

@Composable
fun EnvironmentViewerScreen(
    repository: JervisRepository,
    onBack: () -> Unit,
) {
    var environments by remember { mutableStateOf<List<EnvironmentDto>>(emptyList()) }
    var selectedEnv by remember { mutableStateOf<EnvironmentDto?>(null) }
    var resources by remember { mutableStateOf<K8sResourceListDto?>(null) }
    var nsStatus by remember { mutableStateOf<K8sNamespaceStatusDto?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var isLoadingResources by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Section expansion
    var podsExpanded by remember { mutableStateOf(true) }
    var deploymentsExpanded by remember { mutableStateOf(true) }
    var servicesExpanded by remember { mutableStateOf(false) }

    // Dialogs
    var logDialog by remember { mutableStateOf<Pair<String, String>?>(null) } // envId, podName
    var logContent by remember { mutableStateOf<String?>(null) }
    var logLoading by remember { mutableStateOf(false) }
    var deploymentDetail by remember { mutableStateOf<K8sDeploymentDetailDto?>(null) }
    var deploymentDetailLoading by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    fun loadEnvironments() {
        scope.launch {
            isLoading = true
            error = null
            try {
                environments = repository.environments.getAllEnvironments()
                    .filter { it.state == EnvironmentStateEnum.RUNNING || it.state == EnvironmentStateEnum.CREATING }
            } catch (e: Exception) {
                error = "Chyba při načítání prostředí: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    fun loadResources(env: EnvironmentDto) {
        scope.launch {
            isLoadingResources = true
            try {
                resources = repository.environmentResources.listResources(env.id)
                nsStatus = repository.environmentResources.getNamespaceStatus(env.id)
            } catch (e: Exception) {
                error = "Chyba při načítání K8s zdrojů: ${e.message}"
            } finally {
                isLoadingResources = false
            }
        }
    }

    fun loadPodLogs(envId: String, podName: String) {
        scope.launch {
            logLoading = true
            logContent = null
            try {
                logContent = repository.environmentResources.getPodLogs(envId, podName, 200)
            } catch (e: Exception) {
                logContent = "Chyba: ${e.message}"
            } finally {
                logLoading = false
            }
        }
    }

    fun loadDeploymentDetail(envId: String, name: String) {
        scope.launch {
            deploymentDetailLoading = true
            try {
                deploymentDetail = repository.environmentResources.getDeploymentDetails(envId, name)
            } catch (e: Exception) {
                error = "Chyba: ${e.message}"
            } finally {
                deploymentDetailLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadEnvironments() }

    LaunchedEffect(selectedEnv) {
        selectedEnv?.let { loadResources(it) }
    }

    Scaffold(
        topBar = {
            JTopBar(
                title = "Prostředí – K8s zdroje",
                onBack = onBack,
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(JervisSpacing.outerPadding),
        ) {
            when {
                isLoading -> JCenteredLoading()

                error != null && environments.isEmpty() -> JErrorState(
                    message = error!!,
                    onRetry = { loadEnvironments() },
                )

                environments.isEmpty() -> JEmptyState(message = "Žádná běžící prostředí")

                else -> {
                    // Environment selector (horizontal chips)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
                    ) {
                        environments.forEach { env ->
                            val isSelected = selectedEnv?.id == env.id
                            JCard(
                                onClick = {
                                    selectedEnv = env
                                    resources = null
                                    nsStatus = null
                                },
                                selected = isSelected,
                            ) {
                                Row(
                                    modifier = Modifier.padding(
                                        horizontal = 12.dp,
                                        vertical = JervisSpacing.itemGap,
                                    ),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
                                ) {
                                    Text(env.name, style = MaterialTheme.typography.bodyMedium)
                                    JStatusBadge(
                                        status = when (env.state) {
                                            EnvironmentStateEnum.RUNNING -> "ok"
                                            EnvironmentStateEnum.CREATING -> "pending"
                                            else -> "error"
                                        },
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(JervisSpacing.sectionGap))

                    if (selectedEnv == null) {
                        JEmptyState(message = "Vyberte prostředí pro zobrazení K8s zdrojů")
                    } else if (isLoadingResources) {
                        JCenteredLoading()
                    } else if (resources != null) {
                        // Namespace health summary
                        nsStatus?.let { status ->
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

                            Spacer(Modifier.height(JervisSpacing.sectionGap))
                        }

                        JActionBar {
                            JRefreshButton(onClick = { selectedEnv?.let { loadResources(it) } })
                        }

                        Spacer(Modifier.height(JervisSpacing.itemGap))

                        // Resource list
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
                        ) {
                            // --- Pods section ---
                            item {
                                CollapsibleHeader(
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
                                            logDialog = selectedEnv!!.id to pod.name
                                            loadPodLogs(selectedEnv!!.id, pod.name)
                                        },
                                    )
                                }
                            }

                            // --- Deployments section ---
                            item {
                                Spacer(Modifier.height(JervisSpacing.itemGap))
                                CollapsibleHeader(
                                    title = "Deploymenty (${resources!!.deployments.size})",
                                    expanded = deploymentsExpanded,
                                    onToggle = { deploymentsExpanded = !deploymentsExpanded },
                                )
                            }
                            if (deploymentsExpanded) {
                                items(resources!!.deployments, key = { it.name }) { dep ->
                                    DeploymentCard(
                                        deployment = dep,
                                        onViewDetails = {
                                            loadDeploymentDetail(selectedEnv!!.id, dep.name)
                                        },
                                        onRestart = {
                                            scope.launch {
                                                try {
                                                    repository.environmentResources.restartDeployment(
                                                        selectedEnv!!.id, dep.name,
                                                    )
                                                    loadResources(selectedEnv!!)
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
                                CollapsibleHeader(
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
            }
        }
    }

    // --- Pod Log Dialog ---
    logDialog?.let { (_, podName) ->
        AlertDialog(
            onDismissRequest = { logDialog = null; logContent = null },
            title = { Text("Logy: $podName") },
            text = {
                if (logLoading) {
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
                TextButton(onClick = { logDialog = null; logContent = null }) {
                    Text("Zavřít")
                }
            },
            dismissButton = {
                logDialog?.let { (envId, pName) ->
                    TextButton(onClick = { loadPodLogs(envId, pName) }) {
                        Text("Obnovit")
                    }
                }
            },
        )
    }

    // --- Deployment Detail Dialog ---
    deploymentDetail?.let { detail ->
        AlertDialog(
            onDismissRequest = { deploymentDetail = null },
            title = { Text("Deployment: ${detail.name}") },
            text = {
                if (deploymentDetailLoading) {
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
                TextButton(onClick = { deploymentDetail = null }) {
                    Text("Zavřít")
                }
            },
        )
    }
}

@Composable
private fun CollapsibleHeader(
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
                    JStatusBadge(status = if (pod.ready) "ok" else if (pod.phase == "Running") "pending" else "error")
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
