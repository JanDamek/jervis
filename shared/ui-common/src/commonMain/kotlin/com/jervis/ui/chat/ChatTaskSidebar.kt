package com.jervis.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jervis.dto.task.PendingTaskDto
import com.jervis.dto.task.SidebarSnapshot

/**
 * Chat task sidebar — push-only rendering (guideline #9).
 *
 * The sidebar receives a pre-built [SidebarSnapshot] from [ChatViewModel],
 * which subscribes to the server's `subscribeSidebar(clientId, showDone)`
 * stream via [com.jervis.di.RpcConnectionManager.resilientFlow]. No RPC
 * pull, no refresh button, no 15 s polling. Categorization by state is
 * local (state is an attribute of the DTO).
 */
@Composable
fun ChatTaskSidebar(
    snapshot: SidebarSnapshot?,
    showDone: Boolean,
    onToggleShowDone: () -> Unit,
    activeTaskId: String?,
    onTaskSelected: (PendingTaskDto) -> Unit,
    onMainChatSelected: () -> Unit,
    removedTaskIds: Set<String> = emptySet(),
    /**
     * Optional content rendered below the active-tasks list. Hosts that
     * want the sidebar Background section (Fáze K — coding agents + VNC)
     * pass it here. Empty by default so existing callers compile unchanged.
     */
    backgroundContent: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val expandedSections = remember {
        mutableStateMapOf(
            SidebarSection.NEEDS_ATTENTION.name to true,
            SidebarSection.WORKING.name to true,
            SidebarSection.QUEUED.name to true,
            SidebarSection.NEW.name to true,
            SidebarSection.BLOCKED.name to false,
            SidebarSection.ERRORS.name to false,
            SidebarSection.DONE.name to true,
        )
    }

    val tasks = snapshot?.tasks ?: emptyList()
    val visibleTasks = if (removedTaskIds.isEmpty()) tasks else tasks.filter { it.id !in removedTaskIds }
    val sections = if (showDone) {
        listOf(SidebarSection.DONE to visibleTasks)
    } else {
        SidebarSection.activeSections.mapNotNull { section ->
            val sectionTasks = visibleTasks
                .filter { section.matches(it) }
                .sortedByDescending { it.createdAt }
            if (sectionTasks.isNotEmpty()) section to sectionTasks else null
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (showDone) "Hotove ulohy (${visibleTasks.size})" else "Aktivni ulohy (${visibleTasks.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(
                    onClick = onToggleShowDone,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        if (showDone) Icons.Default.CheckCircle else Icons.Default.History,
                        contentDescription = if (showDone) "Zobrazit aktivni" else "Zobrazit hotove",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            MainChatRow(
                isActive = activeTaskId == null,
                onClick = onMainChatSelected,
            )

            Box(modifier = Modifier.weight(1f, fill = false)) {
                when {
                    snapshot == null -> {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    }
                    visibleTasks.isEmpty() -> {
                        Text(
                            text = if (showDone) "Zadne hotove ulohy." else "Zadne aktivni ulohy. JERVIS ceka na novy obsah.",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            for ((section, sectionTasks) in sections) {
                                val isExpanded = expandedSections[section.name] != false

                                item(key = "header-${section.name}") {
                                    SectionHeader(
                                        title = section.label,
                                        count = sectionTasks.size,
                                        color = section.color,
                                        isExpanded = isExpanded,
                                        onClick = { expandedSections[section.name] = !isExpanded },
                                    )
                                }

                                if (isExpanded) {
                                    items(sectionTasks, key = { it.id }) { task ->
                                        ChatSidebarTaskCard(
                                            task = task,
                                            isActive = task.id == activeTaskId,
                                            onClick = { onTaskSelected(task) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Sidebar Background section (Fáze K) — shown below active tasks when host provides content.
            HorizontalDivider()
            backgroundContent()
        }
    }
}

/** Pipeline stage sections for sidebar grouping. */
private enum class SidebarSection(
    val label: String,
    val color: Color,
    val states: Set<String>,
) {
    NEEDS_ATTENTION(
        label = "K vyrizeni",
        color = Color(0xFF1976D2),
        states = setOf("USER_TASK"),
    ),
    WORKING(
        label = "JERVIS pracuje",
        color = Color(0xFFF57C00),
        states = setOf("PROCESSING", "CODING"),
    ),
    QUEUED(
        label = "Ve fronte",
        color = Color(0xFF7B1FA2),
        states = setOf("QUEUED"),
    ),
    NEW(
        label = "Nove",
        color = Color(0xFF7B1FA2),
        states = setOf("NEW", "INDEXING"),
    ),
    BLOCKED(
        label = "Ceka na podulohy",
        color = Color(0xFF757575),
        states = setOf("BLOCKED"),
    ),
    ERRORS(
        label = "Chyby",
        color = Color(0xFFD32F2F),
        states = setOf("ERROR"),
    ),
    DONE(
        label = "Hotove",
        color = Color(0xFF388E3C),
        states = setOf("DONE"),
    );

    fun matches(task: PendingTaskDto): Boolean = task.state in states

    companion object {
        val activeSections = listOf(NEEDS_ATTENTION, WORKING, QUEUED, NEW, BLOCKED, ERRORS)
    }
}

@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    color: Color,
    isExpanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "$title ($count)",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (isExpanded) "Sbalit" else "Rozbalit",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (isExpanded) {
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 12.dp),
            thickness = 0.5.dp,
            color = color.copy(alpha = 0.3f),
        )
    }
}

@Composable
private fun MainChatRow(
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (isActive) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Hlavni chat",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun ChatSidebarTaskCard(
    task: PendingTaskDto,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (isActive) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = taskDisplayName(task),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(4.dp))
                StateBadge(task.state, task.needsQualification)
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (task.sourceLabel.isNotBlank()) {
                    Text(
                        text = task.sourceLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (task.parentTaskId != null) {
                    Text(
                        text = "poduloha",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                } else if (task.childCount > 0) {
                    Text(
                        text = "${task.completedChildCount}/${task.childCount} deti",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                if (task.needsQualification) {
                    Text(
                        text = "re-kvalifikace",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }

            if (!task.pendingUserQuestion.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = task.pendingUserQuestion!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                )
            }
        }
    }
}

/** Resolve a human-readable display name for a task card. */
private fun taskDisplayName(task: PendingTaskDto): String {
    task.summary?.let { return it }
    val name = task.taskName
    if (name.isNotBlank() && name != "Unnamed Task" && name != task.sourceLabel) {
        return name
    }
    for (line in task.content.lineSequence()) {
        val cleaned = line.removePrefix("# ").removePrefix("## ").trim()
        if (cleaned.isNotBlank() && cleaned.length > 5 &&
            !cleaned.startsWith("**") && cleaned != task.sourceLabel
        ) {
            return cleaned.take(120)
        }
    }
    return task.sourceLabel.ifBlank { "Uloha" }
}

@Composable
private fun StateBadge(state: String, needsQualification: Boolean = false) {
    val showQualificationBadge = needsQualification &&
        state in setOf("NEW", "INDEXING", "QUEUED")
    val (label, color) = if (showQualificationBadge) {
        "Kvalifikator" to Color(0xFF6A1B9A)
    } else when (state) {
        "USER_TASK" -> "K vyrizeni" to Color(0xFF1976D2)
        "ERROR" -> "Chyba" to Color(0xFFD32F2F)
        "QUEUED" -> "Ceka na JERVIS" to Color(0xFFF57C00)
        "PROCESSING" -> "JERVIS pracuje" to Color(0xFFF57C00)
        "CODING" -> "Kodovani" to Color(0xFFF57C00)
        "INDEXING" -> "Indexace" to Color(0xFF7B1FA2)
        "BLOCKED" -> "Ceka na podulohy" to Color(0xFF757575)
        "NEW" -> "Novy" to Color(0xFF7B1FA2)
        "DONE" -> "Hotovo" to Color(0xFF388E3C)
        else -> state to Color(0xFF757575)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
