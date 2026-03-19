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
import com.jervis.dto.ChatMessageDto
import com.jervis.dto.ChatRole
import com.jervis.dto.user.TaskRoutingMode
import com.jervis.dto.user.UserTaskDto
import com.jervis.dto.user.UserTaskListItemDto
import com.jervis.repository.JervisRepository
import com.jervis.ui.design.*
import com.jervis.ui.util.ConfirmDialog
import com.jervis.ui.util.DeleteIconButton
import com.jervis.ui.util.RefreshIconButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 20

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
                val page = repository.userTasks.listAllLightweight(serverQuery, offset, PAGE_SIZE)
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

    fun handleDelete() {
        val task = taskToDelete ?: return
        scope.launch {
            try {
                repository.userTasks.cancel(task.id)
                showDeleteConfirm = false
                taskToDelete = null
                // Optimistic removal — task goes DONE after cancel, remove from list immediately
                if (selectedListItem?.id == task.id) {
                    selectedListItem = null
                    selectedFullTask = null
                }
                listItems = listItems.filter { it.id != task.id }
                totalCount = (totalCount - 1).coerceAtLeast(0)
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
                // Optimistic removal
                if (selectedListItem?.id == item.id) {
                    selectedListItem = null
                    selectedFullTask = null
                }
                listItems = listItems.filter { it.id != item.id }
                totalCount = (totalCount - 1).coerceAtLeast(0)
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
            listItems = listItems.filter { it.id != cancelledId }
            totalCount = (totalCount - 1).coerceAtLeast(0)
            if (selectedListItem?.id == cancelledId) {
                selectedListItem = null
                selectedFullTask = null
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

        JActionBar(modifier = Modifier.padding(vertical = JervisSpacing.outerPadding)) {
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
