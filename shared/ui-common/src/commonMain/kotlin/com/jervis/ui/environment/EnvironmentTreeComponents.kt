package com.jervis.ui.environment

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jervis.dto.environment.ComponentStatusDto
import com.jervis.dto.environment.EnvironmentComponentDto
import com.jervis.dto.environment.EnvironmentDto
import com.jervis.dto.environment.EnvironmentStateEnum
import com.jervis.dto.environment.EnvironmentStatusDto
import com.jervis.ui.design.LocalJervisSemanticColors
import com.jervis.ui.screens.settings.sections.componentTypeLabel

/**
 * Expandable card for a single environment in the tree.
 * Shows name, namespace, state badge. Highlighted if resolved.
 */
@Composable
fun EnvironmentTreeNode(
    environment: EnvironmentDto,
    status: EnvironmentStatusDto?,
    isResolved: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    expandedComponentIds: Set<String>,
    onToggleComponent: (String) -> Unit,
) {
    val currentState = status?.state ?: environment.state

    com.jervis.ui.design.JCard(
        onClick = onToggleExpand,
        selected = isResolved,
    ) {
        // Header row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                contentDescription = if (expanded) "Sbalit" else "Rozbalit",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(environment.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    environment.namespace,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            EnvironmentStateBadge(currentState)
        }

        // Expanded components
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                environment.components.forEach { component ->
                    val componentStatus = status?.componentStatuses?.find {
                        it.componentId == component.id || it.name == component.name
                    }
                    ComponentTreeNode(
                        component = component,
                        status = componentStatus,
                        expanded = expandedComponentIds.contains(component.id),
                        onToggleExpand = { onToggleComponent(component.id) },
                    )
                }
                if (environment.components.isEmpty()) {
                    Text(
                        "Žádné komponenty",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 28.dp),
                    )
                }
            }
        }
    }
}

/**
 * Expandable row for a single component within an environment.
 * Shows type label, name, ready dot, replicas.
 */
@Composable
fun ComponentTreeNode(
    component: EnvironmentComponentDto,
    status: ComponentStatusDto?,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpand)
            .padding(start = 28.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                componentTypeLabel(component.type),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "\"${component.name}\"",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (status != null) {
                ReadyDot(status.ready)
                Spacer(Modifier.width(4.dp))
                Text(
                    "${status.availableReplicas}/${status.replicas}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(
                modifier = Modifier.padding(start = 20.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                component.image?.let { image ->
                    DetailRow("Image", image)
                }
                if (component.ports.isNotEmpty()) {
                    DetailRow(
                        "Porty",
                        component.ports.joinToString {
                            "${it.containerPort}${if (it.name.isNotBlank()) " (${it.name})" else ""}"
                        },
                    )
                }
                if (status != null) {
                    DetailRow("Repliky", "${status.availableReplicas}/${status.replicas}")
                    status.message?.let { msg ->
                        DetailRow("Stav", msg)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadyDot(ready: Boolean) {
    val semanticColors = LocalJervisSemanticColors.current
    val color = if (ready) semanticColors.success else MaterialTheme.colorScheme.error
    Surface(
        modifier = Modifier.size(8.dp),
        shape = CircleShape,
        color = color,
    ) {}
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            "$label: ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Environment state badge with colored dot and Czech label.
 */
@Composable
fun EnvironmentStateBadge(state: EnvironmentStateEnum) {
    val semanticColors = LocalJervisSemanticColors.current
    val (label, color) = when (state) {
        EnvironmentStateEnum.PENDING -> "Čeká" to semanticColors.warning
        EnvironmentStateEnum.CREATING -> "Vytváří se" to semanticColors.warning
        EnvironmentStateEnum.RUNNING -> "Běží" to semanticColors.success
        EnvironmentStateEnum.STOPPING -> "Zastavuje se" to semanticColors.warning
        EnvironmentStateEnum.STOPPED -> "Zastaveno" to MaterialTheme.colorScheme.outline
        EnvironmentStateEnum.ERROR -> "Chyba" to MaterialTheme.colorScheme.error
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(8.dp),
            shape = CircleShape,
            color = color,
        ) {}
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}
