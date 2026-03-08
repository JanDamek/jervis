package com.jervis.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.graph.TaskGraphDto
import com.jervis.ui.design.JIconButton
import com.jervis.ui.design.JTopBar

/**
 * Panel showing the Paměťová mapa (Memory Map) graph alongside chat.
 * Simple read-only visualization — no switching, no dropdown.
 * Active (RUNNING) vertices are visually highlighted via statusColor.
 */
@Composable
fun ThinkingMapPanel(
    activeMap: TaskGraphDto?,
    isCompact: Boolean = false,
    onClose: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val title = when (activeMap?.graphType) {
        "memory_map" -> "Paměťová mapa"
        "thinking_map" -> "Myšlenková mapa"
        else -> "Mapa"
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (isCompact) {
            JTopBar(
                title = title,
                onBack = onClose,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
        ) {
            // Header: title + close button
            if (!isCompact) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    Icon(
                        Icons.Default.AccountTree,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    JIconButton(
                        onClick = onClose,
                        icon = Icons.Default.Close,
                        contentDescription = "Zavřít",
                    )
                }
            }

            // Graph visualization
            if (activeMap != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    TaskGraphSection(
                        graph = activeMap,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Text(
                    text = "Žádná aktivní mapa",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}
