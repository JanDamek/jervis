package com.jervis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.Badge
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jervis.dto.chat.ChatMessageDto
import com.jervis.dto.chat.ChatRole
import com.jervis.dto.proposal.UpdateProposalRequestDto
import com.jervis.dto.task.TaskProposalInfoDto
import com.jervis.dto.user.TaskRoutingMode
import com.jervis.dto.user.UserTaskDto
import com.jervis.dto.user.UserTaskListItemDto
import com.jervis.di.JervisRepository
import com.jervis.ui.design.*
import com.jervis.ui.util.ConfirmDialog
import com.jervis.ui.util.DeleteIconButton
import com.jervis.ui.util.RefreshIconButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

private const val PAGE_SIZE = 20

private fun formatInstant(epochMs: Long): String {
    val tz = kotlinx.datetime.TimeZone.currentSystemDefault()
    val instant = kotlinx.datetime.Instant.fromEpochMilliseconds(epochMs)
    val dt = instant.toLocalDateTime(tz)
    val dd = dt.dayOfMonth.toString().padStart(2, '0')
    val mm = dt.monthNumber.toString().padStart(2, '0')
    val hh = dt.hour.toString().padStart(2, '0')
    val mi = dt.minute.toString().padStart(2, '0')
    return "$dd.$mm.${dt.year} $hh:$mi"
}

private fun userTaskStateBadge(state: String): Pair<String, Color> = when (state) {
    "USER_TASK" -> "K vyřízení" to Color(0xFF1976D2)
    "DONE" -> "Dokončeno" to Color(0xFF388E3C)
    "ERROR", "FAILED" -> "Chyba" to Color(0xFFD32F2F)
    "QUEUED" -> "Ve frontě" to Color(0xFFF57C00)
    "PROCESSING" -> "Zpracovává se" to Color(0xFFF57C00)
    "CODING" -> "Kódování" to Color(0xFFF57C00)
    "INDEXING" -> "Indexace" to Color(0xFF7B1FA2)
    "BLOCKED" -> "Blokován" to Color(0xFF757575)
    "NEW" -> "Nový" to Color(0xFF7B1FA2)
    else -> state to Color(0xFF757575)
}

/**
 * Render a "Návrh Claude" / "Návrh Qualifier" badge based on
 * `proposedBy` prefix. Color = teal accent. Returned label is a
 * Czech UI string per project convention.
 */
private fun proposalBadgeLabel(info: TaskProposalInfoDto): Pair<String, Color> {
    val label = when {
        info.proposedBy.startsWith("qualifier", ignoreCase = true) -> "Návrh Qualifier"
        info.proposedBy.startsWith("claude", ignoreCase = true) -> "Návrh Claude"
        else -> "Návrh"
    }
    // Teal — distinct from existing state palette (blue/green/red/orange/purple).
    return label to Color(0xFF00897B)
}

/** Czech label for [TaskProposalInfoDto.proposalStage] enum names. */
private fun proposalStageLabel(stage: String): String = when (stage) {
    "DRAFT" -> "Koncept"
    "AWAITING_APPROVAL" -> "Čeká na schválení"
    "APPROVED" -> "Schváleno"
    "REJECTED" -> "Zamítnuto"
    else -> stage
}

@Composable
fun UserTasksScreen(
    repository: JervisRepository,
    onBack: () -> Unit,
    onNavigateToProject: ((clientId: String, projectId: String?) -> Unit)? = null,
    onRefreshBadge: (() -> Unit)? = null,
    userTaskCancelled: kotlinx.coroutines.flow.SharedFlow<String>? = null,
    initialTaskId: String? = null,
) {
    var listItems by remember { mutableStateOf<List<UserTaskListItemDto>>(emptyList()) }
    var hasMore by remember { mutableStateOf(false) }
    var totalCount by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var filterText by remember { mutableStateOf("") }
    // PR4 — chip toggling AWAITING_APPROVAL filter (Claude proposals).
    // Mutually exclusive with default "K reakci" view (state=USER_TASK)
    // because backend uses the field as a single discriminator.
    var pendingApprovalOnly by remember { mutableStateOf(false) }

    // Selected item in lightweight list + full DTO loaded on demand
    var selectedListItem by remember { mutableStateOf<UserTaskListItemDto?>(null) }
    var selectedFullTask by remember { mutableStateOf<UserTaskDto?>(null) }
    var isLoadingDetail by remember { mutableStateOf(false) }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var taskToDelete by remember { mutableStateOf<UserTaskListItemDto?>(null) }
    var isDismissing by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    fun loadTasks(query: String? = null, append: Boolean = false) {
        scope.launch {
            if (append) isLoadingMore = true else isLoading = true
            errorMessage = null
            try {
                val offset = if (append) listItems.size else 0
                val serverQuery = query?.takeIf { it.isNotBlank() }
                val stageFilter = if (pendingApprovalOnly) "AWAITING_APPROVAL" else null
                val page = repository.userTasks.listAllLightweight(
                    query = serverQuery,
                    offset = offset,
                    limit = PAGE_SIZE,
                    proposalStageFilter = stageFilter,
                )
                listItems = if (append) listItems + page.items else page.items
                hasMore = page.hasMore
                totalCount = page.totalCount
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage = "Chyba načítání úloh: ${e.message}"
            } finally {
                isLoading = false
                isLoadingMore = false
            }
        }
    }

    // Load full task detail when selecting an item from the lightweight list
    fun selectTask(item: UserTaskListItemDto) {
        selectedListItem = item
        selectedFullTask = null
        isLoadingDetail = true
        scope.launch {
            try {
                selectedFullTask = repository.userTasks.getById(item.id)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage = "Chyba načítání detailu: ${e.message}"
                selectedListItem = null
            } finally {
                isLoadingDetail = false
            }
        }
    }

    // After removing an item, select the next one in the list (or last if it was the last)
    fun selectNextAfterRemoval(removedId: String) {
        val idx = listItems.indexOfFirst { it.id == removedId }
        val remaining = listItems.filter { it.id != removedId }
        listItems = remaining
        totalCount = (totalCount - 1).coerceAtLeast(0)
        if (remaining.isEmpty()) {
            selectedListItem = null
            selectedFullTask = null
        } else {
            val nextIdx = idx.coerceAtMost(remaining.lastIndex)
            val nextItem = remaining[nextIdx]
            selectTask(nextItem)
        }
    }

    fun handleDelete() {
        val task = taskToDelete ?: return
        scope.launch {
            try {
                repository.userTasks.cancel(task.id)
                showDeleteConfirm = false
                taskToDelete = null
                selectNextAfterRemoval(task.id)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage = "Chyba mazání úlohy: ${e.message}"
            }
        }
    }

    fun handleDismiss(item: UserTaskListItemDto) {
        isDismissing = item.id
        scope.launch {
            try {
                repository.userTasks.dismiss(item.id)
                selectNextAfterRemoval(item.id)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage = "Chyba ignorování úlohy: ${e.message}"
            } finally {
                isDismissing = null
            }
        }
    }

    LaunchedEffect(Unit) {
        loadTasks()
        onRefreshBadge?.invoke()
    }

    // Auto-open task detail when navigated from alert card
    LaunchedEffect(initialTaskId) {
        if (initialTaskId != null) {
            try {
                val task = repository.userTasks.getById(initialTaskId)
                if (task != null) {
                    selectedFullTask = task
                    selectedListItem = UserTaskListItemDto(
                        id = task.id,
                        title = task.title,
                        state = task.state,
                        projectId = task.projectId,
                        clientId = task.clientId,
                        createdAtEpochMillis = task.createdAtEpochMillis,
                    )
                }
            } catch (_: Exception) {
                // Task may not exist — ignore
            }
        }
    }

    // Remove cancelled tasks from list reactively (event from global stream)
    LaunchedEffect(userTaskCancelled) {
        userTaskCancelled?.collect { cancelledId ->
            if (selectedListItem?.id == cancelledId) {
                selectNextAfterRemoval(cancelledId)
            } else {
                listItems = listItems.filter { it.id != cancelledId }
                totalCount = (totalCount - 1).coerceAtLeast(0)
            }
        }
    }

    var filterJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(filterText) {
        filterJob?.cancel()
        filterJob = scope.launch {
            delay(300)
            loadTasks(filterText)
        }
    }

    // Reload immediately when the proposal-filter chip flips (no debounce —
    // it's a binary toggle, not a typing event).
    LaunchedEffect(pendingApprovalOnly) {
        loadTasks(filterText)
    }

    if (errorMessage != null && selectedListItem == null) {
        Column {
            JTopBar(title = "Uživatelské úlohy")
            JErrorState(message = errorMessage!!, onRetry = { loadTasks(filterText) })
        }
    } else {
        JListDetailLayout(
            items = listItems,
            selectedItem = selectedListItem,
            isLoading = isLoading,
            onItemSelected = { it?.let { item -> selectTask(item) } },
            emptyMessage = "Žádné úlohy nenalezeny",
            emptyIcon = "📋",
            listHeader = {
                JTopBar(title = "Uživatelské úlohy", actions = {
                    RefreshIconButton(onClick = { loadTasks(filterText) })
                })

                JTextField(
                    value = filterText,
                    onValueChange = { filterText = it },
                    label = "Filtr",
                    placeholder = "Hledat podle názvu...",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = JervisSpacing.outerPadding),
                    singleLine = true,
                )

                // PR4 — proposal-stage filter chip. Toggles between
                // "K reakci" (state=USER_TASK) and "Čekající schválení"
                // (proposalStage=AWAITING_APPROVAL). Server-side
                // discriminator is single-field so chips can't combine.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = JervisSpacing.outerPadding, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = pendingApprovalOnly,
                        onClick = { pendingApprovalOnly = !pendingApprovalOnly },
                        label = { Text("Čekající schválení") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF00897B).copy(alpha = 0.2f),
                            selectedLabelColor = Color(0xFF00695C),
                        ),
                    )
                }
            },
            listFooter = if (hasMore) {
                {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        if (isLoadingMore) {
                            JCenteredLoading()
                        } else {
                            JSecondaryButton(onClick = { loadTasks(filterText, append = true) }) {
                                Text("Načíst další (${listItems.size}/$totalCount)")
                            }
                        }
                    }
                }
            } else null,
            listItem = { item ->
                UserTaskListRow(
                    item = item,
                    onClick = { selectTask(item) },
                    onDismiss = { handleDismiss(item) },
                    onDelete = {
                        taskToDelete = item
                        showDeleteConfirm = true
                    },
                )
            },
            detailContent = {
                if (isLoadingDetail) {
                    JCenteredLoading()
                } else if (selectedFullTask != null) {
                    UserTaskDetail(
                        task = selectedFullTask!!,
                        repository = repository,
                        onBack = {
                            selectedListItem = null
                            selectedFullTask = null
                            loadTasks(filterText)
                        },
                        onTaskSent = { mode ->
                            val clientId = selectedFullTask!!.clientId
                            val projectId = selectedFullTask!!.projectId
                            selectedListItem = null
                            selectedFullTask = null
                            loadTasks(filterText)
                            if (mode == TaskRoutingMode.DIRECT_TO_AGENT) {
                                onNavigateToProject?.invoke(clientId, projectId)
                            }
                        },
                        onError = { errorMessage = it },
                        onDismiss = {
                            selectedListItem?.let { handleDismiss(it) }
                        },
                        onDelete = {
                            selectedListItem?.let {
                                taskToDelete = it
                                showDeleteConfirm = true
                            }
                        },
                    )
                }
            },
        )
    }

    ConfirmDialog(
        visible = showDeleteConfirm && taskToDelete != null,
        title = "Smazat uživatelskou úlohu",
        message = "Opravdu chcete smazat úlohu \"${taskToDelete?.title}\"? Tuto akci nelze vrátit.",
        confirmText = "Smazat",
        onConfirm = { handleDelete() },
        onDismiss = { showDeleteConfirm = false },
        isDestructive = true,
    )
}

@Composable
private fun UserTaskListRow(
    item: UserTaskListItemDto,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    val (stateLabel, stateColor) = userTaskStateBadge(item.state)

    JCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .heightIn(min = JervisSpacing.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                )
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Badge(containerColor = stateColor) { Text(stateLabel) }
                    if (item.hasPendingQuestion) {
                        Badge(containerColor = Color(0xFFF57C00)) { Text("❓") }
                    }
                    item.proposalInfo?.let { info ->
                        val (label, color) = proposalBadgeLabel(info)
                        Badge(containerColor = color) { Text(label) }
                    }
                    Text(
                        text = formatInstant(item.createdAtEpochMillis),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Show pending question preview if available
                if (!item.pendingQuestionPreview.isNullOrBlank()) {
                    Text(
                        text = item.pendingQuestionPreview!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (item.state == "USER_TASK") {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(JervisSpacing.touchTarget),
                ) {
                    Icon(
                        Icons.Default.VisibilityOff,
                        contentDescription = "Ignorovat",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            DeleteIconButton(onClick = onDelete)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun UserTaskDetail(
    task: UserTaskDto,
    repository: JervisRepository,
    onBack: () -> Unit,
    onTaskSent: (TaskRoutingMode) -> Unit,
    onError: (String) -> Unit,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    var replyInput by remember(task.id) { mutableStateOf("") }
    var isSending by remember(task.id) { mutableStateOf(false) }
    var chatHistory by remember(task.id) { mutableStateOf<List<ChatMessageDto>>(emptyList()) }
    var isChatLoading by remember(task.id) { mutableStateOf(true) }
    var chatError by remember(task.id) { mutableStateOf<String?>(null) }
    // PR4 — proposal action state
    var isProposalActing by remember(task.id) { mutableStateOf(false) }
    var showRejectDialog by remember(task.id) { mutableStateOf(false) }
    var rejectReason by remember(task.id) { mutableStateOf("") }
    // PR-Q5 — edit dialog state. Single boolean — fields live inside
    // the dialog composable as remember-with-key state because there's
    // only one edit session at a time per task.
    var showEditDialog by remember(task.id) { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(task.id) {
        isChatLoading = true
        chatError = null
        try {
            chatHistory = repository.userTasks.getChatHistory(task.id)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            chatHistory = emptyList()
            chatError = "Chyba načítání historie: ${e.message}"
        } finally {
            isChatLoading = false
        }
    }

    fun sendReply(mode: TaskRoutingMode) {
        scope.launch {
            isSending = true
            try {
                repository.userTasks.sendToAgent(
                    task.id,
                    mode,
                    replyInput.takeIf { it.isNotBlank() },
                )
                onTaskSent(mode)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.message ?: "Selhalo odeslání úlohy")
            } finally {
                isSending = false
            }
        }
    }

    // PR4 — single-click approve. No bulk approve (anti-pattern); user
    // commits one proposal at a time so each gets a deliberate decision.
    fun approveProposal() {
        scope.launch {
            isProposalActing = true
            try {
                val result = repository.proposalAction.approveProposal(task.id)
                if (result.ok) {
                    // Approved → proposal moves to APPROVED + state=QUEUED;
                    // it leaves the AWAITING_APPROVAL list. Treat like a
                    // task removal so the next item is selected.
                    onTaskSent(TaskRoutingMode.BACK_TO_PENDING)
                } else {
                    onError(result.error ?: "Schválení selhalo")
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.message ?: "Schválení selhalo")
            } finally {
                isProposalActing = false
            }
        }
    }

    fun rejectProposal(reason: String) {
        scope.launch {
            isProposalActing = true
            try {
                val result = repository.proposalAction.rejectProposal(task.id, reason)
                if (result.ok) {
                    showRejectDialog = false
                    rejectReason = ""
                    // Rejected — proposal stays in list, but stage flips
                    // to REJECTED. Trigger reload so the badge updates.
                    onTaskSent(TaskRoutingMode.BACK_TO_PENDING)
                } else {
                    onError(result.error ?: "Zamítnutí selhalo")
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.message ?: "Zamítnutí selhalo")
            } finally {
                isProposalActing = false
            }
        }
    }

    // PR-Q5 — edit a DRAFT/REJECTED proposal. Server merges only
    // non-null fields; on REJECTED the stage flips back to DRAFT and
    // the rejection reason is cleared. After save we reload the list
    // (via onTaskSent) so the badge + stage label reflect the new
    // state — same flow as approve/reject above.
    fun submitEdit(patch: UpdateProposalRequestDto) {
        scope.launch {
            isProposalActing = true
            try {
                val result = repository.proposalAction.updateProposal(task.id, patch)
                if (result.ok) {
                    showEditDialog = false
                    onTaskSent(TaskRoutingMode.BACK_TO_PENDING)
                } else {
                    onError(result.error ?: "Uložení selhalo")
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.message ?: "Uložení selhalo")
            } finally {
                isProposalActing = false
            }
        }
    }

    // PR-Q5 — DRAFT → AWAITING_APPROVAL. Used after the user finishes
    // editing a draft (or a previously REJECTED proposal that was
    // edited and is now back in DRAFT) and wants to surface it for
    // approval.
    fun sendForApproval() {
        scope.launch {
            isProposalActing = true
            try {
                val result = repository.proposalAction.sendForApproval(task.id)
                if (result.ok) {
                    onTaskSent(TaskRoutingMode.BACK_TO_PENDING)
                } else {
                    onError(result.error ?: "Odeslání ke schválení selhalo")
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.message ?: "Odeslání ke schválení selhalo")
            } finally {
                isProposalActing = false
            }
        }
    }

    JDetailScreen(
        title = task.title,
        onBack = onBack,
        actions = {
            if (task.state == "USER_TASK") {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(JervisSpacing.touchTarget),
                ) {
                    Icon(
                        Icons.Default.VisibilityOff,
                        contentDescription = "Ignorovat",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            DeleteIconButton(onClick = onDelete)
        },
    ) {
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier.weight(1f).verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Structured header with metadata
                    JSection(title = "Informace") {
                        val (stateLabel, stateColor) = userTaskStateBadge(task.state)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Badge(containerColor = stateColor) { Text(stateLabel) }
                            task.proposalInfo?.let { info ->
                                val (label, color) = proposalBadgeLabel(info)
                                Badge(containerColor = color) { Text(label) }
                                Badge(containerColor = color.copy(alpha = 0.5f)) {
                                    Text(proposalStageLabel(info.proposalStage))
                                }
                            }
                            Text(
                                text = formatInstant(task.createdAtEpochMillis),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!task.sourceUri.isNullOrBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Zdroj: ${task.sourceUri}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // PR4 — proposal-flow sections. Always rendered when
                    // the task has proposal metadata, regardless of stage,
                    // so the user sees Claude's rationale before deciding.
                    task.proposalInfo?.let { info ->
                        if (info.proposalReason.isNotBlank()) {
                            JSection(title = "Důvod návrhu") {
                                Text(
                                    text = info.proposalReason,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        if (info.proposalStage == "REJECTED" &&
                            !info.proposalRejectionReason.isNullOrBlank()
                        ) {
                            JSection(title = "Důvod zamítnutí") {
                                Text(
                                    text = info.proposalRejectionReason!!,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }

                    // Prominent pending question from the agent
                    if (!task.pendingQuestion.isNullOrBlank()) {
                        JSection(title = "Otázka agenta") {
                            Text(
                                text = task.pendingQuestion!!,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (!task.questionContext.isNullOrBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = task.questionContext!!,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    if (!task.description.isNullOrBlank()) {
                        JSection(title = "Popis") {
                            Text(
                                text = task.description!!,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }

                    if (isChatLoading) {
                        JCenteredLoading()
                    } else if (chatError != null) {
                        Text(
                            text = chatError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (chatHistory.isNotEmpty()) {
                        JSection(title = "Historie konverzace") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (msg in chatHistory) {
                                    ChatBubble(msg)
                                }
                            }
                        }
                    }
                }
            }

            JSection(title = "Odpověď") {
                JTextField(
                    value = replyInput,
                    onValueChange = { replyInput = it },
                    label = "Vaše odpověď",
                    placeholder = if (task.pendingQuestion != null) "Napište odpověď na otázku agenta..." else "Napište instrukce nebo odpověď...",
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 3,
                    maxLines = 6,
                    enabled = !isSending,
                )
            }

            Spacer(Modifier.height(16.dp))
        }

        // PR4 + PR-Q5 — action bar branches on proposalStage:
        //  * AWAITING_APPROVAL : Schválit / Zamítnout (immutable —
        //    no edit; user must Reject first if a change is needed,
        //    aligns with the server invariant that AWAITING_APPROVAL
        //    proposals are not mutable).
        //  * DRAFT             : Upravit / Odeslat ke schválení.
        //    DRAFT means the qualifier or a Claude session hasn't sent
        //    it for approval yet, so the user can polish it. Discard
        //    is intentionally absent — server `rejectTask` only
        //    accepts AWAITING_APPROVAL → REJECTED transitions, so a
        //    "Zahodit" button here would just produce INVALID_STATE.
        //    To drop a DRAFT proposal user uses the row-level Delete
        //    icon instead.
        //  * REJECTED          : Upravit / Odeslat ke schválení.
        //    Editing a REJECTED proposal flips it back to DRAFT
        //    server-side, then "Odeslat ke schválení" pushes it to
        //    AWAITING_APPROVAL.
        //  * APPROVED / null   : regular USER_TASK reply flow (Hotovo
        //    / Převzít do chatu / Odpovědět).
        val proposalStage = task.proposalInfo?.proposalStage
        JActionBar(modifier = Modifier.padding(vertical = JervisSpacing.outerPadding)) {
            when (proposalStage) {
                "AWAITING_APPROVAL" -> {
                    JSecondaryButton(
                        onClick = { showRejectDialog = true },
                        enabled = !isProposalActing,
                    ) {
                        Text("Zamítnout", color = MaterialTheme.colorScheme.error)
                    }
                    JPrimaryButton(
                        onClick = { approveProposal() },
                        enabled = !isProposalActing,
                    ) {
                        Text("Schválit")
                    }
                }

                "DRAFT" -> {
                    JSecondaryButton(
                        onClick = { showEditDialog = true },
                        enabled = !isProposalActing,
                    ) {
                        Text("Upravit")
                    }
                    JPrimaryButton(
                        onClick = { sendForApproval() },
                        enabled = !isProposalActing,
                    ) {
                        Text("Odeslat ke schválení")
                    }
                }

                "REJECTED" -> {
                    JSecondaryButton(
                        onClick = { showEditDialog = true },
                        enabled = !isProposalActing,
                    ) {
                        Text("Upravit")
                    }
                    JPrimaryButton(
                        onClick = { sendForApproval() },
                        enabled = !isProposalActing,
                    ) {
                        Text("Odeslat ke schválení")
                    }
                }

                else -> {
                    JSecondaryButton(
                        onClick = onDismiss,
                        enabled = !isSending,
                    ) {
                        Text("Hotovo")
                    }
                    JSecondaryButton(
                        onClick = { sendReply(TaskRoutingMode.DIRECT_TO_AGENT) },
                        enabled = !isSending,
                    ) {
                        Text("Převzít do chatu")
                    }
                    JPrimaryButton(
                        onClick = { sendReply(TaskRoutingMode.BACK_TO_PENDING) },
                        enabled = !isSending && replyInput.isNotBlank(),
                    ) {
                        Text("Odpovědět")
                    }
                }
            }
        }
    }

    // Reject dialog — server enforces min 5 chars on `reason`, mirror in
    // UI so the user gets immediate feedback rather than an RPC roundtrip.
    // Uses AlertDialog directly (not ConfirmDialog) because we need a
    // text field inline with the message body.
    if (showRejectDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                if (!isProposalActing) {
                    showRejectDialog = false
                    rejectReason = ""
                }
            },
            title = { Text("Zamítnout návrh") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Zadejte stručný důvod (min. $MIN_REJECT_REASON_LENGTH znaků). " +
                            "Důvod uvidí Claude session, aby mohla návrh upravit " +
                            "a znovu nabídnout.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    JTextField(
                        value = rejectReason,
                        onValueChange = { rejectReason = it },
                        label = "Důvod zamítnutí",
                        placeholder = "Např. už řešíme jinou cestou; prosím zúžit scope...",
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        minLines = 3,
                        maxLines = 6,
                        enabled = !isProposalActing,
                    )
                }
            },
            confirmButton = {
                JDestructiveButton(
                    onClick = {
                        if (rejectReason.trim().length >= MIN_REJECT_REASON_LENGTH &&
                            !isProposalActing
                        ) {
                            rejectProposal(rejectReason.trim())
                        }
                    },
                    enabled = !isProposalActing &&
                        rejectReason.trim().length >= MIN_REJECT_REASON_LENGTH,
                ) {
                    Text("Zamítnout")
                }
            },
            dismissButton = {
                JTextButton(
                    onClick = {
                        if (!isProposalActing) {
                            showRejectDialog = false
                            rejectReason = ""
                        }
                    },
                    enabled = !isProposalActing,
                ) {
                    Text("Zrušit")
                }
            },
        )
    }

    // PR-Q5 — edit dialog. Rendered only for DRAFT/REJECTED proposals
    // (action bar above only shows the "Upravit" button there). The
    // dialog itself owns its TextField state so re-opening always
    // starts from the current task snapshot rather than stale buffer.
    if (showEditDialog) {
        ProposalEditDialog(
            task = task,
            isSaving = isProposalActing,
            onConfirm = { patch -> submitEdit(patch) },
            onDismiss = {
                if (!isProposalActing) showEditDialog = false
            },
        )
    }
}

private const val MIN_REJECT_REASON_LENGTH = 5

// ── PR-Q5 — proposal edit dialog ────────────────────────────────────────

/** Czech label for [TaskProposalInfoDto.proposalTaskType] enum names. */
private fun proposalTaskTypeLabel(type: String?): String = when (type) {
    "CODING" -> "Kódování"
    "MAIL_REPLY" -> "Odpověď na e-mail"
    "TEAMS_REPLY" -> "Odpověď v Teams"
    "CALENDAR_RESPONSE" -> "Odpověď na pozvánku"
    "BUGTRACKER_ENTRY" -> "Issue v bug-trackeru"
    "MEETING_ATTEND" -> "Účast na schůzce"
    "OTHER" -> "Jiné (manuální review)"
    null -> "Nezvoleno"
    else -> type
}

/**
 * Stable ordering for the dropdown — most-likely picks first so users
 * don't have to scroll. Mirrors the enum order in the orchestrator
 * `ProposalTaskType`.
 */
private val PROPOSAL_TASK_TYPE_OPTIONS = listOf(
    "CODING",
    "MAIL_REPLY",
    "TEAMS_REPLY",
    "CALENDAR_RESPONSE",
    "BUGTRACKER_ENTRY",
    "MEETING_ATTEND",
    "OTHER",
)

/**
 * Edit dialog for a Claude/qualifier-proposed task in DRAFT or REJECTED
 * stage. Only non-null fields in the resulting patch are forwarded to
 * the server (server merges); blank or unchanged values map to `null`
 * so the server doesn't overwrite identical text.
 *
 * Validation client-side:
 *  - title.isNotBlank() — server-side `taskName` is required
 *  - description.isNotBlank() — body is the actual draft reply / mail /
 *    Teams message; an empty body is never useful
 *  - reason / scheduledAt / proposalTaskType — optional
 *
 * Material 3 [androidx.compose.material3.DatePicker] is not yet stable
 * on Compose Multiplatform (1.9.x ships an experimental API but iOS
 * targets routinely break on it). To keep the dialog portable we use
 * preset chips ("Dnes 18:00", "Zítra 9:00", "Pondělí 9:00") + a free-
 * text ISO field. When richer pickers land in CMP we can swap the
 * inner [ScheduledAtPicker] body without touching the rest.
 */
@Composable
private fun ProposalEditDialog(
    task: UserTaskDto,
    isSaving: Boolean,
    onConfirm: (UpdateProposalRequestDto) -> Unit,
    onDismiss: () -> Unit,
) {
    val originalTitle = task.title
    val originalDescription = task.description ?: ""
    val originalReason = task.proposalInfo?.proposalReason ?: ""
    val originalType = task.proposalInfo?.proposalTaskType

    var titleState by remember(task.id) { mutableStateOf(originalTitle) }
    var descriptionState by remember(task.id) { mutableStateOf(originalDescription) }
    var reasonState by remember(task.id) { mutableStateOf(originalReason) }
    var typeState by remember(task.id) { mutableStateOf(originalType) }
    var scheduledAtIsoState by remember(task.id) { mutableStateOf<String?>(null) }

    val canSave = titleState.isNotBlank() && descriptionState.isNotBlank() && !isSaving

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Upravit návrh") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(JervisSpacing.itemGap),
            ) {
                Text(
                    "Server přepíše pouze pole, která se reálně změnila. " +
                        "Návrh ve stavu \"Zamítnuto\" se po uložení vrátí do " +
                        "\"Koncept\" a ztratí původní důvod zamítnutí.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                JTextField(
                    value = titleState,
                    onValueChange = { titleState = it },
                    label = "Název",
                    placeholder = "Krátký výstižný název návrhu",
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isSaving,
                    isError = titleState.isBlank(),
                    errorMessage = if (titleState.isBlank()) "Název nesmí být prázdný" else null,
                )
                JTextField(
                    value = descriptionState,
                    onValueChange = { descriptionState = it },
                    label = "Obsah / odpověď / popis",
                    placeholder = "Tělo zprávy, kódovací zadání nebo popis úkolu...",
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 6,
                    maxLines = 20,
                    enabled = !isSaving,
                    isError = descriptionState.isBlank(),
                    errorMessage = if (descriptionState.isBlank()) "Obsah nesmí být prázdný" else null,
                )
                JTextField(
                    value = reasonState,
                    onValueChange = { reasonState = it },
                    label = "Důvod návrhu (volitelné)",
                    placeholder = "Proč Claude tuto akci navrhuje",
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 2,
                    maxLines = 5,
                    enabled = !isSaving,
                )
                ProposalTaskTypeDropdown(
                    selected = typeState,
                    onSelect = { typeState = it },
                    enabled = !isSaving,
                )
                ScheduledAtPicker(
                    value = scheduledAtIsoState,
                    onChange = { scheduledAtIsoState = it },
                    enabled = !isSaving,
                )
            }
        },
        confirmButton = {
            JPrimaryButton(
                onClick = {
                    if (!canSave) return@JPrimaryButton
                    // Diff against original — null = "leave unchanged".
                    // For scheduledAt we only ever push a non-null ISO
                    // because the server treats blank as "no change"
                    // (existing scheduledAt is preserved); a future
                    // "clear schedule" UI would need a server-side
                    // sentinel value.
                    val patch = UpdateProposalRequestDto(
                        title = titleState.takeIf { it != originalTitle },
                        description = descriptionState.takeIf { it != originalDescription },
                        reason = reasonState.takeIf { it != originalReason },
                        scheduledAtIso = scheduledAtIsoState?.takeIf { it.isNotBlank() },
                        proposalTaskType = typeState?.takeIf { it != originalType },
                    )
                    // No-change save — close without an RPC roundtrip.
                    val isNoOp = patch.title == null &&
                        patch.description == null &&
                        patch.reason == null &&
                        patch.scheduledAtIso == null &&
                        patch.proposalTaskType == null
                    if (isNoOp) {
                        onDismiss()
                    } else {
                        onConfirm(patch)
                    }
                },
                enabled = canSave,
            ) {
                Text("Uložit")
            }
        },
        dismissButton = {
            JTextButton(onClick = onDismiss, enabled = !isSaving) {
                Text("Zrušit")
            }
        },
    )
}

@Composable
private fun ProposalTaskTypeDropdown(
    selected: String?,
    onSelect: (String) -> Unit,
    enabled: Boolean,
) {
    JDropdown(
        items = PROPOSAL_TASK_TYPE_OPTIONS,
        selectedItem = selected,
        onItemSelected = onSelect,
        label = "Typ návrhu",
        itemLabel = { proposalTaskTypeLabel(it) },
        enabled = enabled,
        placeholder = proposalTaskTypeLabel(null),
    )
}

/**
 * Lightweight scheduled-at picker. Compose Multiplatform doesn't ship
 * a stable cross-platform DateTimePicker yet (Material 3 DatePicker
 * works on Android/Desktop but iOS support is in flux), so we offer
 * three preset chips ("Dnes 18:00", "Zítra 9:00", "Pondělí 9:00") plus
 * a free-text ISO-8601 field for custom values. The result is an
 * RFC 3339 string the server parses with `Instant.parse`. Selecting
 * a preset overwrites the text field, picking "Vlastní…" focuses it.
 */
@Composable
private fun ScheduledAtPicker(
    value: String?,
    onChange: (String?) -> Unit,
    enabled: Boolean,
) {
    val tz = remember { TimeZone.currentSystemDefault() }
    val now = remember { Clock.System.now() }
    val today = remember(now, tz) { now.toLocalDateTime(tz).date }

    fun atIso(date: LocalDate, hour: Int, minute: Int = 0): String {
        val ldt = LocalDateTime(date, LocalTime(hour, minute))
        return ldt.toInstant(tz).toString()
    }

    val tonightIso = remember(today) { atIso(today, 18) }
    val tomorrowIso = remember(today) {
        atIso(today.plus(1, DateTimeUnit.DAY), 9)
    }
    val nextMondayIso = remember(today) {
        // Days until next Monday (1..7 — never "today" so the chip is
        // useful when today is already Monday).
        val daysAhead = ((DayOfWeek.MONDAY.ordinal - today.dayOfWeek.ordinal + 7) % 7)
            .let { if (it == 0) 7 else it }
        atIso(today.plus(daysAhead, DateTimeUnit.DAY), 9)
    }

    var customIso by remember { mutableStateOf(value ?: "") }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Plánovaný čas (volitelné)",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = value == tonightIso,
                onClick = {
                    val next = if (value == tonightIso) null else tonightIso
                    onChange(next)
                    customIso = next ?: ""
                },
                label = { Text("Dnes 18:00") },
                enabled = enabled,
            )
            FilterChip(
                selected = value == tomorrowIso,
                onClick = {
                    val next = if (value == tomorrowIso) null else tomorrowIso
                    onChange(next)
                    customIso = next ?: ""
                },
                label = { Text("Zítra 9:00") },
                enabled = enabled,
            )
            FilterChip(
                selected = value == nextMondayIso,
                onClick = {
                    val next = if (value == nextMondayIso) null else nextMondayIso
                    onChange(next)
                    customIso = next ?: ""
                },
                label = { Text("Po 9:00") },
                enabled = enabled,
            )
        }
        JTextField(
            value = customIso,
            onValueChange = {
                customIso = it
                // Bubble up only when the user has typed something
                // resembling an ISO timestamp; otherwise treat as
                // "no schedule" so saving an unrecognised string
                // doesn't fail server-side parse.
                onChange(it.trim().takeIf { v -> v.isNotEmpty() })
            },
            label = "Vlastní (ISO 8601)",
            placeholder = "2026-05-01T09:00:00+02:00",
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = enabled,
        )
    }
}

@Composable
private fun ChatBubble(message: ChatMessageDto) {
    val isUser = message.role == ChatRole.USER
    val roleLabel = when (message.role) {
        ChatRole.USER -> "Uživatel"
        ChatRole.ASSISTANT -> "Agent"
        ChatRole.SYSTEM -> "Systém"
        ChatRole.BACKGROUND -> "Background"
        ChatRole.ALERT -> "Upozornění"
    }
    val bgColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    JCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = roleLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
