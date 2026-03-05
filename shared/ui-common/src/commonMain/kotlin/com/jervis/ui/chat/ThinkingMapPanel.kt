package com.jervis.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jervis.dto.graph.TaskGraphDto

/**
 * Panel showing the active thinking map (TaskGraph) alongside chat.
 * Displays a dropdown for switching between maps and the graph visualization.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThinkingMapPanel(
    activeMap: TaskGraphDto?,
    allMaps: List<ChatViewModel.ThinkingMapSummary>,
    onSelectMap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
    ) {
        // Header: map selector dropdown
        if (allMaps.size > 1) {
            var expanded by remember { mutableStateOf(false) }
            val selectedTitle = allMaps.firstOrNull { it.id == activeMap?.taskId }?.title
                ?: activeMap?.let { graph ->
                    graph.vertices[graph.rootVertexId]?.title
                } ?: "Mapa"

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                OutlinedTextField(
                    value = selectedTitle,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Myšlenková mapa") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    allMaps.forEach { summary ->
                        DropdownMenuItem(
                            text = { Text("${summary.title} (${summary.vertexCount})") },
                            onClick = {
                                onSelectMap(summary.id)
                                expanded = false
                            },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        } else {
            // Single map — just show title
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                Icon(
                    Icons.Default.AccountTree,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                val title = activeMap?.let { graph ->
                    graph.vertices[graph.rootVertexId]?.title
                } ?: "Myšlenková mapa"
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
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
