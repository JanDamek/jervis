package com.jervis.ui

import com.jervis.dto.ChatRequestContextDto
import com.jervis.dto.ChatRequestDto
import com.jervis.dto.ChatResponseType
import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.events.JervisEvent
import com.jervis.dto.user.TaskRoutingMode
import com.jervis.dto.ui.ChatMessage
import com.jervis.repository.JervisRepository
import com.jervis.ui.model.AgentActivityEntry
import com.jervis.ui.model.AgentActivityLog
import com.jervis.ui.model.NodeEntry
import com.jervis.ui.model.NodeStatus
import com.jervis.ui.model.PendingQueueItem
import com.jervis.ui.model.TaskHistoryEntry
import com.jervis.ui.notification.NotificationAction
import com.jervis.ui.notification.NotificationActionChannel
import com.jervis.ui.notification.PlatformNotificationManager
import com.jervis.ui.storage.PendingMessageStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * ViewModel for MainScreen
 * Manages state and business logic for the UI
 */
class MainViewModel(
    private val repository: JervisRepository,
    defaultClientId: String? = null,
    defaultProjectId: String? = null,
    private val onRefreshConnection: (() -> Unit)? = null,
) {
    // Global exception handler to prevent app crashes
    private val exceptionHandler =
        CoroutineExceptionHandler { _, exception ->
            println("Uncaught exception in MainViewModel: ${exception.message}")
            exception.printStackTrace()

            // Check if it's a cancellation or "Cancelled" error
            val isCancelled =
                exception is CancellationException ||
                    exception.message?.contains("cancelled", ignoreCase = true) == true ||
                    exception.message?.contains("RpcClient was cancelled", ignoreCase = true) == true ||
                    exception.message?.contains("Client cancelled", ignoreCase = true) == true

            if (isCancelled) {
                _connectionState.value = ConnectionState.DISCONNECTED
                // Neukazujeme overlay hned, necháme connectWithRetry, aby ho zobrazil až po selhání pokusu
            } else {
                _errorMessage.value = "An unexpected error occurred: ${exception.message}"
            }
        }

    /**
     * Deserialize workflow steps from metadata.
     * Metadata contains "workflowSteps" key with JSON-serialized List<WorkflowStep>.
     */
    private fun parseWorkflowSteps(metadata: Map<String, String>): List<ChatMessage.WorkflowStep> {
        val json = metadata["workflowSteps"] ?: return emptyList()
        return try {
            kotlinx.serialization.json.Json.decodeFromString<List<ChatMessage.WorkflowStep>>(json)
        } catch (e: Exception) {
            println("Failed to deserialize workflow steps: ${e.message}")
            emptyList()
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + exceptionHandler)

    // UI State
    private val _clients = MutableStateFlow<List<ClientDto>>(emptyList())
    val clients: StateFlow<List<ClientDto>> = _clients.asStateFlow()

    private val _projects = MutableStateFlow<List<ProjectDto>>(emptyList())
    val projects: StateFlow<List<ProjectDto>> = _projects.asStateFlow()

    private val _selectedClientId = MutableStateFlow<String?>(defaultClientId)
    val selectedClientId: StateFlow<String?> = _selectedClientId.asStateFlow()

    private val _selectedProjectId = MutableStateFlow<String?>(defaultProjectId)
    val selectedProjectId: StateFlow<String?> = _selectedProjectId.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _showReconnectDialog = MutableStateFlow(false)
    val showReconnectDialog: StateFlow<Boolean> = _showReconnectDialog.asStateFlow()

    private var pendingMessage: String? = null

    // Connection state
    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _isOverlayVisible = MutableStateFlow(false)
    val isOverlayVisible: StateFlow<Boolean> = _isOverlayVisible.asStateFlow()

    private val _isInitialLoading = MutableStateFlow(true)
    val isInitialLoading: StateFlow<Boolean> = _isInitialLoading.asStateFlow()

    private val _reconnectAttemptDisplay = MutableStateFlow(0)
    val reconnectAttemptDisplay: StateFlow<Int> = _reconnectAttemptDisplay.asStateFlow()

    private var pingJob: Job? = null

    private val _notifications = MutableStateFlow<List<com.jervis.dto.events.JervisEvent>>(emptyList())
    val notifications: StateFlow<List<com.jervis.dto.events.JervisEvent>> = _notifications.asStateFlow()

    private val _queueSize = MutableStateFlow(0)
    val queueSize: StateFlow<Int> = _queueSize.asStateFlow()

    private val _runningProjectId = MutableStateFlow<String?>(null)
    val runningProjectId: StateFlow<String?> = _runningProjectId.asStateFlow()

    private val _runningProjectName = MutableStateFlow<String?>(null)
    val runningProjectName: StateFlow<String?> = _runningProjectName.asStateFlow()

    private val _runningTaskPreview = MutableStateFlow<String?>(null)
    val runningTaskPreview: StateFlow<String?> = _runningTaskPreview.asStateFlow()

    private val _runningTaskType = MutableStateFlow<String?>(null)
    val runningTaskType: StateFlow<String?> = _runningTaskType.asStateFlow()

    // Pending queue items (tasks waiting to be processed) - split by queue type
    private val _pendingQueueItems = MutableStateFlow<List<PendingQueueItem>>(emptyList())
    val pendingQueueItems: StateFlow<List<PendingQueueItem>> = _pendingQueueItems.asStateFlow()

    private val _foregroundQueue = MutableStateFlow<List<PendingQueueItem>>(emptyList())
    val foregroundQueue: StateFlow<List<PendingQueueItem>> = _foregroundQueue.asStateFlow()

    private val _backgroundQueue = MutableStateFlow<List<PendingQueueItem>>(emptyList())
    val backgroundQueue: StateFlow<List<PendingQueueItem>> = _backgroundQueue.asStateFlow()

    // In-memory agent activity log (since app start, max 200 entries, no persistence)
    val activityLog = AgentActivityLog()
    private val _activityEntries = MutableStateFlow<List<AgentActivityEntry>>(emptyList())
    val activityEntries: StateFlow<List<AgentActivityEntry>> = _activityEntries.asStateFlow()

    // Track previous running state for transition detection
    private var previousRunningProjectId: String? = null

    // Approval dialog state — shown when orchestrator interrupt arrives
    private val _approvalDialogEvent = MutableStateFlow<JervisEvent.UserTaskCreated?>(null)
    val approvalDialogEvent: StateFlow<JervisEvent.UserTaskCreated?> = _approvalDialogEvent.asStateFlow()

    // Orchestrator task progress (push-based from Python via Kotlin)
    private val _orchestratorProgress = MutableStateFlow<OrchestratorProgressInfo?>(null)
    val orchestratorProgress: StateFlow<OrchestratorProgressInfo?> = _orchestratorProgress.asStateFlow()

    // Task history — COMPLETED tasks only (not shown while running)
    private val _taskHistory = MutableStateFlow<List<TaskHistoryEntry>>(emptyList())
    val taskHistory: StateFlow<List<TaskHistoryEntry>> = _taskHistory.asStateFlow()

    // Running task nodes — visible in Agent section, moved to _taskHistory on finalize
    private val _runningTaskNodes = MutableStateFlow<List<NodeEntry>>(emptyList())
    val runningTaskNodes: StateFlow<List<NodeEntry>> = _runningTaskNodes.asStateFlow()
    private var runningTaskStartTime: String? = null

    // Platform notification manager
    val notificationManager = PlatformNotificationManager()

    private var chatJob: Job? = null
    private var eventJob: Job? = null
    private var queueStatusJob: Job? = null
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 50 // Avoid infinite loop if something is fundamentally wrong
    private var isReconnecting = false

    init {
        // Initialize notifications
        notificationManager.initialize()

        // Restore pending message from persistent storage (survives app restart)
        pendingMessage = PendingMessageStorage.load()

        // Load initial data
        loadClients()

        // Auto-load projects if client is pre-selected
        _selectedClientId.value?.let { clientId ->
            selectClient(clientId)
        }

        // Subscribe to chat stream when both client and project are selected
        scope.launch {
            combine(_selectedClientId, _selectedProjectId) { clientId, projectId ->
                clientId to projectId
            }.collect { (clientId, projectId) ->
                if (clientId != null && projectId != null) {
                    subscribeToChatStream(clientId, projectId)
                }
            }
        }

        // Subscribe to global events for the selected client
        scope.launch {
            _selectedClientId.collect { clientId ->
                if (clientId != null) {
                    subscribeToEventStream(clientId)
                    subscribeToQueueStatus(clientId)
                } else {
                    eventJob?.cancel()
                    queueStatusJob?.cancel()
                }
            }
        }

        // Collect notification action results from platform-specific handlers
        scope.launch {
            NotificationActionChannel.actions.collect { result ->
                when (result.action) {
                    NotificationAction.APPROVE -> {
                        try {
                            repository.userTasks.sendToAgent(
                                taskId = result.taskId,
                                routingMode = TaskRoutingMode.DIRECT_TO_AGENT,
                                additionalInput = null,
                            )
                        } catch (e: Exception) {
                            println("Failed to approve task ${result.taskId}: ${e.message}")
                            _errorMessage.value = "Schválení selhalo: ${e.message}"
                        }
                        _approvalDialogEvent.value = null
                    }
                    NotificationAction.DENY -> {
                        // Deny from notification → show in-app dialog for reason input
                        // The approval dialog will handle the actual deny with reason
                        _approvalDialogEvent.value?.let { event ->
                            // Already showing dialog, user will provide reason there
                        }
                    }
                    NotificationAction.OPEN -> {
                        // Navigate to user tasks — handled by UI layer
                    }
                }
            }
        }
    }

    private fun subscribeToEventStream(clientId: String) {
        eventJob?.cancel()
        eventJob =
            scope.launch {
                repository.notifications
                    .subscribeToEvents(clientId)
                    .retryWhen { cause, attempt ->
                        val isRpcDead = cause.message?.contains("RpcClient was cancelled", ignoreCase = true) == true ||
                            cause.message?.contains("Client cancelled", ignoreCase = true) == true
                        if (isRpcDead) {
                            println("Event stream: RPC client dead, stopping retry")
                            false // stop — connectWithRetry in chat stream triggers onRefreshConnection
                        } else {
                            println("Event stream error: ${cause.message}, retry attempt $attempt")
                            val delaySeconds =
                                when (attempt + 1) {
                                    1L -> 1L
                                    2L -> 2L
                                    else -> 3L
                                }
                            delay(delaySeconds.seconds)
                            true
                        }
                    }.collect { event ->
                        handleGlobalEvent(event)
                    }
            }
    }

    private fun handleGlobalEvent(event: JervisEvent) {
        println("Received global event: ${event::class.simpleName}")
        val currentNotifications = _notifications.value
        when (event) {
            is JervisEvent.UserTaskCreated -> {
                _notifications.value = currentNotifications + event

                // Show platform notification
                notificationManager.showNotification(
                    title = if (event.isApproval) "Schválení vyžadováno" else "Nová úloha",
                    body = event.title,
                    taskId = event.taskId,
                    isApproval = event.isApproval,
                    interruptAction = event.interruptAction,
                )

                // Show in-app approval dialog for approval events
                if (event.isApproval) {
                    _approvalDialogEvent.value = event
                }
            }

            is JervisEvent.UserTaskCancelled -> {
                _notifications.value =
                    currentNotifications.filter {
                        !(it is JervisEvent.UserTaskCreated && it.taskId == event.taskId)
                    }
                notificationManager.cancelNotification(event.taskId)
                // Dismiss approval dialog if this task was cancelled
                if (_approvalDialogEvent.value?.taskId == event.taskId) {
                    _approvalDialogEvent.value = null
                }
            }

            is JervisEvent.ErrorNotification -> {
                _errorMessage.value = "Server error: ${event.message}"
            }

            is JervisEvent.MeetingStateChanged -> {
                // Handled by MeetingViewModel
            }

            is JervisEvent.MeetingTranscriptionProgress -> {
                // Handled by MeetingViewModel
            }

            is JervisEvent.MeetingCorrectionProgress -> {
                // Handled by MeetingViewModel
            }

            is JervisEvent.OrchestratorTaskProgress -> {
                _orchestratorProgress.value = OrchestratorProgressInfo(
                    taskId = event.taskId,
                    node = event.node,
                    message = event.message,
                    percent = event.percent,
                    goalIndex = event.goalIndex,
                    totalGoals = event.totalGoals,
                    stepIndex = event.stepIndex,
                    totalSteps = event.totalSteps,
                )
                updateTaskHistory(event.taskId, event.node)
            }

            is JervisEvent.OrchestratorTaskStatusChange -> {
                when (event.status) {
                    "done" -> {
                        // Clear progress — orchestration finished
                        // FINAL chat message arrives via chat stream
                        _orchestratorProgress.value = null
                        finalizeTaskHistory(event.taskId, event.status)
                    }
                    "error" -> {
                        _orchestratorProgress.value = null
                        finalizeTaskHistory(event.taskId, event.status)
                        // Replace PROGRESS with error in chat
                        replaceChatProgress("Chyba při zpracování úlohy", ChatMessage.MessageType.ERROR)
                    }
                    "interrupted" -> {
                        // Keep progress visible but update message
                        _orchestratorProgress.value = _orchestratorProgress.value?.copy(
                            message = "Čekám na schválení...",
                        )
                        finalizeTaskHistory(event.taskId, "interrupted")
                        // Update chat PROGRESS message
                        replaceChatProgress("Čekám na vaše schválení...", ChatMessage.MessageType.PROGRESS)
                    }
                }
            }

            else -> {
                // Ignore others or handle as needed
            }
        }
    }

    /**
     * Approve an approval request from the in-app dialog.
     */
    fun approveTask(taskId: String) {
        scope.launch {
            try {
                repository.userTasks.sendToAgent(
                    taskId = taskId,
                    routingMode = TaskRoutingMode.DIRECT_TO_AGENT,
                    additionalInput = null,
                )
                _approvalDialogEvent.value = null
                notificationManager.cancelNotification(taskId)
            } catch (e: Exception) {
                _errorMessage.value = "Schválení selhalo: ${e.message}"
            }
        }
    }

    /**
     * Cancel a running orchestration.
     */
    fun cancelOrchestration(taskId: String) {
        scope.launch {
            try {
                repository.agentOrchestrator.cancelOrchestration(taskId)
                _orchestratorProgress.value = null
            } catch (e: Exception) {
                println("Failed to cancel orchestration: ${e.message}")
            }
        }
    }

    /**
     * Deny an approval request with a reason from the in-app dialog.
     * The reason becomes a clarification user task for the orchestrator.
     */
    fun denyTask(taskId: String, reason: String) {
        scope.launch {
            try {
                repository.userTasks.sendToAgent(
                    taskId = taskId,
                    routingMode = TaskRoutingMode.DIRECT_TO_AGENT,
                    additionalInput = reason,
                )
                _approvalDialogEvent.value = null
                notificationManager.cancelNotification(taskId)
            } catch (e: Exception) {
                _errorMessage.value = "Zamítnutí selhalo: ${e.message}"
            }
        }
    }

    /**
     * Dismiss the approval dialog without action.
     * The task remains in the user tasks list.
     */
    fun dismissApprovalDialog() {
        _approvalDialogEvent.value = null
    }

    /** Node name to Czech label for orchestrator pipeline steps. */
    private val nodeLabels = mapOf(
        "intake" to "Analýza úlohy",
        "evidence" to "Shromažďování kontextu",
        "evidence_pack" to "Shromažďování kontextu",
        "plan" to "Plánování",
        "plan_steps" to "Plánování kroků",
        "execute" to "Provádění",
        "execute_step" to "Provádění kroku",
        "evaluate" to "Vyhodnocení",
        "finalize" to "Dokončení",
        "respond" to "Generování odpovědi",
        "clarify" to "Upřesnění",
        "decompose" to "Dekompozice na cíle",
        "select_goal" to "Výběr cíle",
        "advance_step" to "Další krok",
        "advance_goal" to "Další cíl",
        "git_operations" to "Git operace",
        "report" to "Generování reportu",
    )

    /**
     * Accumulate orchestrator node progress for the running task.
     * Does NOT add to _taskHistory — that happens only in finalizeTaskHistory().
     */
    private fun updateTaskHistory(taskId: String, node: String) {
        val label = nodeLabels[node] ?: node
        val current = _runningTaskNodes.value.toMutableList()

        // Mark previous RUNNING nodes as DONE
        for (i in current.indices) {
            if (current[i].status == NodeStatus.RUNNING) {
                current[i] = current[i].copy(status = NodeStatus.DONE)
            }
        }

        // Add node if not present, or update its status
        val existingIdx = current.indexOfFirst { it.node == node }
        if (existingIdx < 0) {
            current.add(NodeEntry(node = node, label = label, status = NodeStatus.RUNNING))
        } else {
            current[existingIdx] = current[existingIdx].copy(status = NodeStatus.RUNNING)
        }

        _runningTaskNodes.value = current

        // Record start time on first node
        if (runningTaskStartTime == null) {
            runningTaskStartTime = AgentActivityEntry.formatNow()
        }
    }

    /**
     * Replace the current PROGRESS chat message with new text/type,
     * or remove it if no replacement needed.
     */
    private fun replaceChatProgress(text: String, messageType: ChatMessage.MessageType) {
        val messages = _chatMessages.value.toMutableList()
        val progressIdx = messages.indexOfLast { it.messageType == ChatMessage.MessageType.PROGRESS }
        if (progressIdx >= 0) {
            messages[progressIdx] = ChatMessage(
                from = ChatMessage.Sender.Assistant,
                text = text,
                contextId = _selectedProjectId.value,
                messageType = messageType,
            )
            _chatMessages.value = messages
        }
    }

    /**
     * Create a history entry from accumulated nodes when orchestration completes.
     * This is the ONLY place where entries are added to _taskHistory.
     */
    private fun finalizeTaskHistory(taskId: String, status: String) {
        val nodes = _runningTaskNodes.value
        if (nodes.isEmpty()) return

        val finalNodes = nodes.map { n ->
            if (n.status == NodeStatus.RUNNING) n.copy(status = NodeStatus.DONE) else n
        }
        val preview = _runningTaskPreview.value ?: "Zpracování úlohy"
        val projectName = _runningProjectName.value

        val entry = TaskHistoryEntry(
            taskId = taskId,
            taskPreview = preview,
            projectName = projectName,
            startTime = runningTaskStartTime ?: AgentActivityEntry.formatNow(),
            endTime = AgentActivityEntry.formatNow(),
            status = status,
            nodes = finalNodes,
        )

        val history = _taskHistory.value.toMutableList()
        history.add(0, entry) // newest first
        _taskHistory.value = history

        // Reset accumulator
        _runningTaskNodes.value = emptyList()
        runningTaskStartTime = null
    }

    private fun subscribeToQueueStatus(clientId: String) {
        queueStatusJob?.cancel()
        queueStatusJob =
            scope.launch {
                repository.agentOrchestrator
                    .subscribeToQueueStatus(clientId)
                    .retryWhen { cause, attempt ->
                        val isRpcDead = cause.message?.contains("RpcClient was cancelled", ignoreCase = true) == true ||
                            cause.message?.contains("Client cancelled", ignoreCase = true) == true
                        if (isRpcDead) {
                            println("Queue status stream: RPC client dead, stopping retry")
                            false // stop — connectWithRetry in chat stream triggers onRefreshConnection
                        } else {
                            println("Queue status stream error: ${cause.message}, retry attempt $attempt")
                            delay(2.seconds)
                            true
                        }
                    }.collect { response ->
                        if (response.type == ChatResponseType.QUEUE_STATUS) {
                            val newRunningId = response.metadata["runningProjectId"]
                            val newProjectName = response.metadata["runningProjectName"]
                            val newTaskPreview = response.metadata["runningTaskPreview"]
                            val newTaskType = response.metadata["runningTaskType"]
                            val newQueueSize = response.metadata["queueSize"]?.toIntOrNull() ?: 0

                            // Detect state transitions and log activity
                            val wasRunning = previousRunningProjectId != null && previousRunningProjectId != "none"
                            val isRunning = newRunningId != null && newRunningId != "none"

                            // Use meaningful task type label for activity log
                            // "Asistent" is not useful as a type label for chat tasks
                            val displayTaskType = newTaskType?.takeIf { it != "Asistent" }
                            val prevDisplayTaskType = _runningTaskType.value?.takeIf { it != "Asistent" }

                            if (!wasRunning && isRunning) {
                                // Task started
                                activityLog.add(
                                    type = AgentActivityEntry.Type.TASK_STARTED,
                                    description = newTaskPreview ?: "Zpracování úlohy",
                                    projectName = newProjectName,
                                    taskType = displayTaskType,
                                    clientId = clientId,
                                )
                                _activityEntries.value = activityLog.entries
                            } else if (wasRunning && !isRunning) {
                                // Task completed, agent idle
                                activityLog.add(
                                    type = AgentActivityEntry.Type.TASK_COMPLETED,
                                    description = "Úloha dokončena" +
                                        (_runningProjectName.value?.let { " ($it)" } ?: ""),
                                    projectName = _runningProjectName.value,
                                    taskType = prevDisplayTaskType,
                                    clientId = clientId,
                                )
                                _activityEntries.value = activityLog.entries
                            } else if (wasRunning && isRunning && previousRunningProjectId != newRunningId) {
                                // Different task started (previous completed)
                                activityLog.add(
                                    type = AgentActivityEntry.Type.TASK_COMPLETED,
                                    description = "Úloha dokončena" +
                                        (_runningProjectName.value?.let { " ($it)" } ?: ""),
                                    projectName = _runningProjectName.value,
                                    taskType = prevDisplayTaskType,
                                    clientId = clientId,
                                )
                                activityLog.add(
                                    type = AgentActivityEntry.Type.TASK_STARTED,
                                    description = newTaskPreview ?: "Zpracování úlohy",
                                    projectName = newProjectName,
                                    taskType = displayTaskType,
                                    clientId = clientId,
                                )
                                _activityEntries.value = activityLog.entries
                            }

                            previousRunningProjectId = newRunningId
                            _queueSize.value = newQueueSize
                            _runningProjectId.value = newRunningId
                            _runningProjectName.value = newProjectName
                            _runningTaskPreview.value = newTaskPreview
                            _runningTaskType.value = newTaskType

                            // No backfill needed — history entries are created only on finalize,
                            // by which time _runningTaskPreview is already set from queue status

                            // Parse FOREGROUND pending queue items from metadata
                            val pendingCount = response.metadata["pendingItemCount"]?.toIntOrNull() ?: 0
                            val foregroundItems = (0 until pendingCount).mapNotNull { i ->
                                val preview = response.metadata["pendingItem_${i}_preview"]
                                val project = response.metadata["pendingItem_${i}_project"]
                                val taskId = response.metadata["pendingItem_${i}_taskId"] ?: ""
                                if (preview != null) {
                                    PendingQueueItem(
                                        taskId = taskId,
                                        preview = preview,
                                        projectName = project ?: "General",
                                        processingMode = "FOREGROUND",
                                    )
                                } else null
                            }
                            _foregroundQueue.value = foregroundItems
                            _pendingQueueItems.value = foregroundItems // backward compat

                            // Parse BACKGROUND pending queue items from metadata
                            val backgroundCount = response.metadata["backgroundItemCount"]?.toIntOrNull() ?: 0
                            val backgroundItems = (0 until backgroundCount).mapNotNull { i ->
                                val preview = response.metadata["backgroundItem_${i}_preview"]
                                val project = response.metadata["backgroundItem_${i}_project"]
                                val taskId = response.metadata["backgroundItem_${i}_taskId"] ?: ""
                                if (preview != null) {
                                    PendingQueueItem(
                                        taskId = taskId,
                                        preview = preview,
                                        projectName = project ?: "General",
                                        processingMode = "BACKGROUND",
                                    )
                                } else null
                            }
                            _backgroundQueue.value = backgroundItems
                        }
                    }
            }
    }

    private fun subscribeToChatStream(
        clientId: String,
        projectId: String,
    ) {
        // Cancel previous chat subscription
        chatJob?.cancel()
        pingJob?.cancel()
        reconnectAttempts = 0
        _reconnectAttemptDisplay.value = 0
        isReconnecting = false

        // Subscribe with auto-reconnect
        chatJob =
            scope.launch {
                // Ensure UI reflects connecting state immediately
                _connectionState.value = ConnectionState.CONNECTING
                // Nechceme zobrazit overlay okamžitě při startu, pokud už jsme připojený k něčemu jinému
                // nebo pokud jde o první načtení. Zobrazíme ho až při skutečném výpadku nebo delším čekání.
                connectWithRetry(clientId, projectId)
            }

        // Start ping job
        startPingJob(clientId)
    }

    private fun startPingJob(clientId: String) {
        pingJob?.cancel()
        pingJob =
            scope.launch {
                while (true) {
                    delay(10000) // Reduced frequency to 10 seconds
                    if (_connectionState.value == ConnectionState.CONNECTED) {
                        try {
                            // Simple RPC call to verify connection
                            repository.clients.getClientById(clientId)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            println("Ping failed: ${e.message}")

                            // If we are DISCONNECTED or it's a "Cancelled" error, trigger reconnect
                            val isRpcCancelled =
                                e.message?.contains("RpcClient was cancelled", ignoreCase = true) == true ||
                                    e.message?.contains("Client cancelled", ignoreCase = true) == true ||
                                    e.message?.contains("connection", ignoreCase = true) == true

                            if (isRpcCancelled) {
                                println("Triggering reconnect due to ping failure or RPC cancellation")
                                _connectionState.value = ConnectionState.DISCONNECTED

                                // Explicitly cancel and restart to force fresh RPC client state
                                chatJob?.cancel()
                                val projectId = _selectedProjectId.value
                                if (projectId != null) {
                                    subscribeToChatStream(clientId, projectId)
                                }
                            }
                        }
                    }
                }
            }
    }

    private suspend fun connectWithRetry(
        clientId: String,
        projectId: String,
    ) {
        while (reconnectAttempts < maxReconnectAttempts) {
            try {
                // If we are already connected and it fails, don't show overlay immediately
                // but if we are RECONNECTING, we might want to show it.
                // Logic was: if error occurs in catch, _isOverlayVisible.value = true

                val state = if (reconnectAttempts > 0) ConnectionState.RECONNECTING else ConnectionState.CONNECTING
                _connectionState.value = state
                _reconnectAttemptDisplay.value = reconnectAttempts + 1

                // Pokud už je to několikátý pokus nebo trvá připojení dlouho, ukážeme overlay
                if (reconnectAttempts > 0) {
                    _isOverlayVisible.value = true

                    // After 3 failed attempts, trigger full refresh (recreates HttpClient + RPC)
                    if (reconnectAttempts % 3 == 0 && onRefreshConnection != null) {
                        println("=== Max attempts reached for this RPC client, triggering full refresh ===")
                        onRefreshConnection.invoke()
                        return // LaunchedEffect v JervisApp se postará o zbytek
                    }
                } else {
                    // Při prvním pokusu (CONNECTING) overlay skryjeme, pokud by náhodou visel
                    _isOverlayVisible.value = false
                }

                println(
                    "=== ${state.name}: attempt ${reconnectAttempts + 1}/$maxReconnectAttempts for client=$clientId, project=$projectId ===",
                )

                repository.agentOrchestrator
                    .subscribeToChat(clientId, projectId)
                    .onStart {
                        println("=== Flow started, setting CONNECTED ===")
                        _connectionState.value = ConnectionState.CONNECTED

                        // Po připojení explicitně vyžádat historii, aby UI bylo synchronizované
                        // Reload history se postará o skrytí overlaye
                        scope.launch {
                            reloadHistory(clientId, projectId)
                        }
                    }.catch { e ->
                        println("Chat stream error: ${e.message}")
                        e.printStackTrace()

                        // Check if RPC client was cancelled - this needs full reconnect
                        val isRpcCancelled =
                            e.message?.contains("RpcClient was cancelled", ignoreCase = true) == true ||
                                e.message?.contains("Client cancelled", ignoreCase = true) == true

                        if (isRpcCancelled && reconnectAttempts >= 1) {
                            println("=== RPC client cancelled, triggering full reconnect ===")
                            onRefreshConnection?.invoke()
                            throw e // Stop retry loop, onRefreshConnection will handle it
                        }

                        // Treat as disconnected
                        _connectionState.value = ConnectionState.DISCONNECTED
                        _isOverlayVisible.value = true

                        // Auto-reconnect with custom backoff: 1s, 2s, then stays at 3s
                        reconnectAttempts++
                        _reconnectAttemptDisplay.value = reconnectAttempts
                        val delaySeconds =
                            when (reconnectAttempts) {
                                1 -> 1L
                                else -> 2L
                            }
                        println("=== Reconnecting in ${delaySeconds}s (attempt $reconnectAttempts) ===")
                        delay(delaySeconds.seconds)
                    }.collect { response ->
                        handleChatResponse(response, clientId, projectId)
                    }

                // If flow completes normally, check if it was truly finished or just closed
                println("=== Flow completed normally ===")
                _connectionState.value = ConnectionState.DISCONNECTED

                reconnectAttempts++
                _reconnectAttemptDisplay.value = reconnectAttempts
                val delaySeconds =
                    when (reconnectAttempts) {
                        1 -> 1L
                        else -> 2L
                    }
                println("=== Reconnecting after flow completion in ${delaySeconds}s (attempt $reconnectAttempts) ===")
                delay(delaySeconds.seconds)
            } catch (e: Exception) {
                if (e is CancellationException) {
                    println("=== connectWithRetry cancelled ===")
                    throw e
                }

                println("=== Unexpected error in connectWithRetry: ${e.message} ===")
                e.printStackTrace()

                _connectionState.value = ConnectionState.DISCONNECTED
                _isOverlayVisible.value = true

                reconnectAttempts++
                _reconnectAttemptDisplay.value = reconnectAttempts
                val delaySeconds =
                    when (reconnectAttempts) {
                        1 -> 1L
                        else -> 2L
                    }
                println("=== Reconnecting after error in ${delaySeconds}s (attempt $reconnectAttempts) ===")
                delay(delaySeconds.seconds)
            }
        }
    }

    private suspend fun handleChatResponse(
        response: com.jervis.dto.ChatResponseDto,
        clientId: String,
        projectId: String,
    ) {
        // Filter out internal markers (HISTORY_LOADED, QUEUE_STATUS)
        if (response.metadata["status"] == "synchronized" || response.type == ChatResponseType.QUEUE_STATUS) {
            return
        }

        val messageType =
            when (response.type) {
                ChatResponseType.USER_MESSAGE -> {
                    ChatMessage.MessageType.USER_MESSAGE
                }

                ChatResponseType.PLANNING,
                ChatResponseType.EVIDENCE_GATHERING,
                ChatResponseType.EXECUTING,
                ChatResponseType.REVIEWING,
                -> {
                    ChatMessage.MessageType.PROGRESS
                }

                ChatResponseType.FINAL -> {
                    ChatMessage.MessageType.FINAL
                }

                ChatResponseType.ERROR -> {
                    ChatMessage.MessageType.ERROR
                }

                ChatResponseType.CHAT_CHANGED -> {
                    println("=== Chat session changed, reloading history... ===")
                    reloadHistory(clientId, projectId)
                    null
                }

                else -> {
                    null
                }
            }

        if (messageType == null) return

        println("=== Received chat message (${response.type}): ${response.message.take(100)} ===")

        val messages = _chatMessages.value.toMutableList()

        when (messageType) {
            ChatMessage.MessageType.USER_MESSAGE -> {
                // Deduplicate: skip if we already showed this message optimistically
                val alreadyShown = messages.any {
                    it.from == ChatMessage.Sender.Me &&
                        it.text == response.message &&
                        it.messageType == ChatMessage.MessageType.USER_MESSAGE &&
                        it.timestamp == null // optimistic messages have no timestamp
                }
                if (alreadyShown) {
                    // Replace the optimistic message with the server-confirmed one (with timestamp)
                    val idx = messages.indexOfLast {
                        it.from == ChatMessage.Sender.Me &&
                            it.text == response.message &&
                            it.messageType == ChatMessage.MessageType.USER_MESSAGE &&
                            it.timestamp == null
                    }
                    if (idx >= 0) {
                        messages[idx] = ChatMessage(
                            from = ChatMessage.Sender.Me,
                            text = response.message,
                            contextId = projectId,
                            messageType = ChatMessage.MessageType.USER_MESSAGE,
                            metadata = response.metadata,
                            timestamp = response.metadata["timestamp"],
                        )
                    }
                } else {
                    messages.add(
                        ChatMessage(
                            from = ChatMessage.Sender.Me,
                            text = response.message,
                            contextId = projectId,
                            messageType = ChatMessage.MessageType.USER_MESSAGE,
                            metadata = response.metadata,
                            timestamp = response.metadata["timestamp"],
                        ),
                    )
                }
            }

            ChatMessage.MessageType.PROGRESS -> {
                // Replace any existing PROGRESS message (not just the last one)
                // to avoid stacking multiple progress indicators when user sends messages
                val existingProgressIndex = messages.indexOfLast { it.messageType == ChatMessage.MessageType.PROGRESS }
                if (existingProgressIndex >= 0) {
                    messages[existingProgressIndex] =
                        ChatMessage(
                            from = ChatMessage.Sender.Assistant,
                            text = response.message,
                            contextId = projectId,
                            messageType = messageType,
                            metadata = response.metadata,
                        )
                } else {
                    messages.add(
                        ChatMessage(
                            from = ChatMessage.Sender.Assistant,
                            text = response.message,
                            contextId = projectId,
                            messageType = messageType,
                            metadata = response.metadata,
                        ),
                    )
                }
            }

            ChatMessage.MessageType.FINAL -> {
                // Remove ALL progress messages (not just last) to clean up stacked indicators
                messages.removeAll { it.messageType == ChatMessage.MessageType.PROGRESS }
                messages.add(
                    ChatMessage(
                        from = ChatMessage.Sender.Assistant,
                        text = response.message,
                        contextId = projectId,
                        messageType = messageType,
                        metadata = response.metadata,
                        workflowSteps = parseWorkflowSteps(response.metadata),
                    ),
                )
            }

            ChatMessage.MessageType.ERROR -> {
                messages.removeAll { it.messageType == ChatMessage.MessageType.PROGRESS }
                messages.add(
                    ChatMessage(
                        from = ChatMessage.Sender.Assistant,
                        text = response.message,
                        contextId = projectId,
                        messageType = messageType,
                        metadata = response.metadata,
                    ),
                )
            }
        }
        _chatMessages.value = messages
    }

    private suspend fun reloadHistory(
        clientId: String,
        projectId: String,
    ) {
        try {
            val history = repository.agentOrchestrator.getChatHistory(clientId, projectId, limit = 10)
            val newMessages =
                history.messages.map { msg ->
                    ChatMessage(
                        from = if (msg.role == com.jervis.dto.ChatRole.USER) ChatMessage.Sender.Me else ChatMessage.Sender.Assistant,
                        text = msg.content,
                        contextId = projectId,
                        messageType = if (msg.role == com.jervis.dto.ChatRole.USER) ChatMessage.MessageType.USER_MESSAGE else ChatMessage.MessageType.FINAL,
                        metadata = msg.metadata,
                        timestamp = msg.timestamp,
                        workflowSteps = parseWorkflowSteps(msg.metadata),
                    )
                }
            _chatMessages.value = newMessages

            // Re-sync UI state
            _connectionState.value = ConnectionState.CONNECTED

            // Důležité: Skrýt overlay okamžitě, jakmile máme data
            _isOverlayVisible.value = false
            reconnectAttempts = 0
            _reconnectAttemptDisplay.value = 0

            // After successful reconnect, retry pending message
            pendingMessage?.let { msg ->
                println("=== Retrying pending message after reconnect ===")
                _inputText.value = msg
                pendingMessage = null
                PendingMessageStorage.save(null)
                sendMessage()
            }
        } catch (e: Exception) {
            println("Failed to reload history: ${e.message}")

            // Check if RPC client was cancelled - trigger full reconnect
            val isRpcCancelled =
                e.message?.contains("RpcClient was cancelled", ignoreCase = true) == true ||
                    e.message?.contains("Client cancelled", ignoreCase = true) == true

            if (isRpcCancelled) {
                println("=== RPC client cancelled in reloadHistory, triggering full reconnect ===")
                onRefreshConnection?.invoke()
            }
        }
    }

    fun loadClients() {
        scope.launch {
            _isLoading.value = true
            _isInitialLoading.value = true
            try {
                val clientList = repository.clients.getAllClients()
                _clients.value = clientList
            } catch (e: Exception) {
                _errorMessage.value = "Failed to load clients: ${e.message}"
            } finally {
                _isLoading.value = false
                _isInitialLoading.value = false
            }
        }
    }

    fun selectClient(clientId: String) {
        if (_selectedClientId.value == clientId) return

        _selectedClientId.value = clientId
        _selectedProjectId.value = null // Reset project selection temporarily
        _projects.value = emptyList()
        _chatMessages.value = emptyList() // Clear messages for the new client

        chatJob?.cancel() // Zrušit stream pro předchozího klienta
        pingJob?.cancel()
        _connectionState.value = ConnectionState.DISCONNECTED
        _isOverlayVisible.value = false // Skrýt overlay při změně klienta

        scope.launch {
            _isLoading.value = true
            try {
                val projectList = repository.projects.listProjectsForClient(clientId)
                _projects.value = projectList

                // Restore last selected project if available
                val client = _clients.value.find { it.id == clientId }
                client?.lastSelectedProjectId?.let { lastProjectId ->
                    if (projectList.any { it.id == lastProjectId }) {
                        _selectedProjectId.value = lastProjectId
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _errorMessage.value = "Failed to load projects: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun selectProject(projectId: String) {
        if (projectId.isBlank()) {
            _selectedProjectId.value = null
            return
        }
        if (_selectedProjectId.value == projectId) return

        _selectedProjectId.value = projectId
        _chatMessages.value = emptyList() // Clear messages for the new project

        // Zrušit předchozí stream a nechat init bloku v kombinaci s MutableStateFlows spustit nový
        chatJob?.cancel()
        pingJob?.cancel()
        _connectionState.value = ConnectionState.DISCONNECTED
        _isOverlayVisible.value = false // Skrýt overlay při změně projektu

        // Save selection to server
        val clientId = _selectedClientId.value
        if (clientId != null) {
            scope.launch {
                try {
                    val updatedClient = repository.clients.updateLastSelectedProject(clientId, projectId)
                    _clients.value =
                        _clients.value.map {
                            if (it.id == clientId) updatedClient else it
                        }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // Silent fail - not critical
                }
            }
        }
    }

    fun updateInputText(text: String) {
        _inputText.value = text
    }

    fun sendMessage() {
        val text = _inputText.value.trim()
        if (text.isEmpty()) return

        val clientId = _selectedClientId.value
        val projectId = _selectedProjectId.value

        if (clientId == null) {
            _errorMessage.value = "Nejdříve vyberte klienta"
            return
        }

        // Optimistic update — show user message immediately
        val optimisticMsg = ChatMessage(
            from = ChatMessage.Sender.Me,
            text = text,
            contextId = projectId,
            messageType = ChatMessage.MessageType.USER_MESSAGE,
        )
        _chatMessages.value = _chatMessages.value + optimisticMsg

        // Send message - server echo will be deduplicated in handleChatResponse
        scope.launch {
            _isLoading.value = true
            val originalText = text
            _inputText.value = "" // Clear input immediately

            try {
                repository.agentOrchestrator.sendMessage(
                    ChatRequestDto(
                        text = originalText,
                        context =
                            ChatRequestContextDto(
                                clientId = clientId,
                                projectId = projectId,
                            ),
                    ),
                )
                println("=== Message sent successfully ===")
                pendingMessage = null
                PendingMessageStorage.save(null)

                // Show progress message — agent is now processing
                val isQueued = _runningProjectId.value != null &&
                    _runningProjectId.value != "none" &&
                    _runningProjectId.value != projectId
                val progressText = if (isQueued) "Zpráva zařazena do fronty..." else "Zpracovávám..."
                val progressMsg = ChatMessage(
                    from = ChatMessage.Sender.Assistant,
                    text = progressText,
                    contextId = projectId,
                    messageType = ChatMessage.MessageType.PROGRESS,
                )
                _chatMessages.value = _chatMessages.value + progressMsg
            } catch (e: Exception) {
                println("Error sending message: ${e.message}")
                e.printStackTrace()
                pendingMessage = originalText
                PendingMessageStorage.save(originalText)
                // Remove optimistic message on failure
                _chatMessages.value = _chatMessages.value.filter { it !== optimisticMsg }
                _errorMessage.value = "Nepodařilo se odeslat zprávu: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun retrySendMessage() {
        val text = pendingMessage ?: return
        val clientId = _selectedClientId.value
        val projectId = _selectedProjectId.value

        if (clientId == null) return

        scope.launch {
            _isLoading.value = true
            try {
                repository.agentOrchestrator.sendMessage(
                    ChatRequestDto(
                        text = text,
                        context =
                            ChatRequestContextDto(
                                clientId = clientId,
                                projectId = projectId,
                            ),
                    ),
                )
                println("=== Retried message sent successfully ===")
                pendingMessage = null
                PendingMessageStorage.save(null)
            } catch (e: Exception) {
                println("Error retrying message: ${e.message}")
                _errorMessage.value = "Nepodařilo se odeslat zprávu: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun cancelRetry() {
        pendingMessage = null
        PendingMessageStorage.save(null)
    }

    fun clearError() {
        _errorMessage.value = null
    }

    // --- Queue Management Actions ---

    /**
     * Refresh queue data from server via dedicated RPC call.
     * Populates foregroundQueue and backgroundQueue with full task details.
     */
    fun refreshQueues() {
        val clientId = _selectedClientId.value ?: return
        scope.launch {
            try {
                val pending = repository.agentOrchestrator.getPendingTasks(clientId)
                _foregroundQueue.value = pending.foreground.map { dto ->
                    PendingQueueItem(
                        taskId = dto.taskId,
                        preview = dto.preview,
                        projectName = dto.projectName,
                        taskType = dto.taskType,
                        processingMode = dto.processingMode,
                        queuePosition = dto.queuePosition,
                    )
                }
                _backgroundQueue.value = pending.background.map { dto ->
                    PendingQueueItem(
                        taskId = dto.taskId,
                        preview = dto.preview,
                        projectName = dto.projectName,
                        taskType = dto.taskType,
                        processingMode = dto.processingMode,
                        queuePosition = dto.queuePosition,
                    )
                }
            } catch (e: Exception) {
                println("Error refreshing queues: ${e.message}")
            }
        }
    }

    /**
     * Move task up in its queue (decrease position).
     */
    fun moveTaskUp(taskId: String) {
        val allItems = _foregroundQueue.value + _backgroundQueue.value
        val item = allItems.find { it.taskId == taskId } ?: return
        val currentPos = item.queuePosition ?: return
        if (currentPos <= 1) return

        scope.launch {
            try {
                repository.agentOrchestrator.reorderTask(taskId, currentPos - 1)
                refreshQueues()
            } catch (e: Exception) {
                println("Error moving task up: ${e.message}")
            }
        }
    }

    /**
     * Move task down in its queue (increase position).
     */
    fun moveTaskDown(taskId: String) {
        val allItems = _foregroundQueue.value + _backgroundQueue.value
        val item = allItems.find { it.taskId == taskId } ?: return
        val currentPos = item.queuePosition ?: return

        // Check if it's the last item in its queue
        val queueItems = if (item.processingMode == "FOREGROUND") _foregroundQueue.value else _backgroundQueue.value
        if (currentPos >= queueItems.size) return

        scope.launch {
            try {
                repository.agentOrchestrator.reorderTask(taskId, currentPos + 1)
                refreshQueues()
            } catch (e: Exception) {
                println("Error moving task down: ${e.message}")
            }
        }
    }

    /**
     * Move task between FOREGROUND and BACKGROUND queues.
     */
    fun moveTaskToQueue(taskId: String, targetMode: String) {
        scope.launch {
            try {
                repository.agentOrchestrator.moveTask(taskId, targetMode)
                refreshQueues()
            } catch (e: Exception) {
                println("Error moving task to $targetMode: ${e.message}")
            }
        }
    }

    fun onDispose() {
        // Cleanup if needed
    }
}

/**
 * Orchestrator task progress info pushed from Python via Kotlin.
 * Displayed in UI to show real-time orchestration progress.
 */
data class OrchestratorProgressInfo(
    val taskId: String,
    val node: String,
    val message: String,
    val percent: Double = 0.0,
    val goalIndex: Int = 0,
    val totalGoals: Int = 0,
    val stepIndex: Int = 0,
    val totalSteps: Int = 0,
)
