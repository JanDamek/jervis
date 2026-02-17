package com.jervis.ui

import com.jervis.di.RpcConnectionManager
import com.jervis.di.RpcConnectionState
import com.jervis.dto.ChatRequestContextDto
import com.jervis.dto.ChatRequestDto
import com.jervis.dto.ChatResponseType
import com.jervis.dto.ClientDto
import com.jervis.dto.ProjectDto
import com.jervis.dto.filterVisible
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
import com.jervis.ui.notification.PushTokenRegistrar
import com.jervis.ui.model.PendingMessageInfo
import com.jervis.ui.model.classifySendError
import com.jervis.ui.storage.PendingMessageState
import com.jervis.ui.storage.PendingMessageStorage
import com.jervis.ui.storage.isExpired
import com.jervis.ui.util.PickedFile
import com.jervis.ui.util.pickFile
import com.jervis.dto.AttachmentDto
import com.jervis.dto.CompressionBoundaryDto
import com.jervis.dto.environment.EnvironmentDto
import com.jervis.dto.environment.EnvironmentStateEnum
import com.jervis.dto.environment.EnvironmentStatusDto
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * ViewModel for MainScreen
 * Manages state and business logic for the UI
 */
class MainViewModel(
    private val repository: JervisRepository,
    private val connectionManager: RpcConnectionManager,
    defaultClientId: String? = null,
    defaultProjectId: String? = null,
) {
    // Global exception handler to prevent app crashes
    private val exceptionHandler =
        CoroutineExceptionHandler { _, exception ->
            println("Uncaught exception in MainViewModel: ${exception.message}")
            exception.printStackTrace()

            if (exception is CancellationException) {
                // Ignore cancellations — RpcConnectionManager handles reconnection
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

    private val _projectGroups = MutableStateFlow<List<com.jervis.dto.ProjectGroupDto>>(emptyList())
    val projectGroups: StateFlow<List<com.jervis.dto.ProjectGroupDto>> = _projectGroups.asStateFlow()

    private val _selectedClientId = MutableStateFlow<String?>(defaultClientId)
    val selectedClientId: StateFlow<String?> = _selectedClientId.asStateFlow()

    private val _selectedProjectId = MutableStateFlow<String?>(defaultProjectId)
    val selectedProjectId: StateFlow<String?> = _selectedProjectId.asStateFlow()

    private val _selectedGroupId = MutableStateFlow<String?>(null)
    val selectedGroupId: StateFlow<String?> = _selectedGroupId.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Pending message state (replaces old plain-text pendingMessage)
    private var pendingState: PendingMessageState? = null
    private var retryJob: Job? = null

    // Streaming token accumulation buffer: messageId → accumulated text
    private val streamingBuffer = mutableMapOf<String, String>()

    private val _pendingMessageInfo = MutableStateFlow<PendingMessageInfo?>(null)
    val pendingMessageInfo: StateFlow<PendingMessageInfo?> = _pendingMessageInfo.asStateFlow()

    companion object {
        private val BACKOFF_DELAYS_MS = listOf(0L, 5_000L, 30_000L, 300_000L)
        private const val MAX_AUTO_RETRIES = 4
    }

    // Connection state
    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _isOverlayVisible = MutableStateFlow(false)
    val isOverlayVisible: StateFlow<Boolean> = _isOverlayVisible.asStateFlow()

    private val _isInitialLoading = MutableStateFlow(true)
    val isInitialLoading: StateFlow<Boolean> = _isInitialLoading.asStateFlow()

    /** Reconnect attempt counter — delegated to RpcConnectionManager */
    val reconnectAttemptDisplay: StateFlow<Int> = connectionManager.reconnectAttempt

    /** Job for debouncing overlay show/hide on connect/disconnect */
    private var overlayDebounceJob: Job? = null
    private var overlayShowJob: Job? = null

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

    // History pagination state
    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _compressionBoundaries = MutableStateFlow<List<CompressionBoundaryDto>>(emptyList())
    val compressionBoundaries: StateFlow<List<CompressionBoundaryDto>> = _compressionBoundaries.asStateFlow()

    private var oldestSequence: Long? = null

    // File attachments pending send
    private val _attachments = MutableStateFlow<List<PickedFile>>(emptyList())
    val attachments: StateFlow<List<PickedFile>> = _attachments.asStateFlow()

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

    // User task badge count — number of active user tasks requiring attention
    private val _userTaskCount = MutableStateFlow(0)
    val userTaskCount: StateFlow<Int> = _userTaskCount.asStateFlow()

    /**
     * Sync badge count from server. Call when navigating to UserTasks screen
     * or when badge might be out of sync with reality.
     */
    fun refreshUserTaskCount() {
        val clientId = _selectedClientId.value ?: return
        scope.launch {
            try {
                val countDto = repository.userTasks.activeCount(clientId)
                _userTaskCount.value = countDto.activeCount
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    // User task dialog state — shown when any user task arrives (approval or clarification)
    private val _userTaskDialogEvent = MutableStateFlow<JervisEvent.UserTaskCreated?>(null)
    val userTaskDialogEvent: StateFlow<JervisEvent.UserTaskCreated?> = _userTaskDialogEvent.asStateFlow()

    // Orchestrator task progress (push-based from Python via Kotlin)
    private val _orchestratorProgress = MutableStateFlow<OrchestratorProgressInfo?>(null)
    val orchestratorProgress: StateFlow<OrchestratorProgressInfo?> = _orchestratorProgress.asStateFlow()

    // Qualification progress — live messages from KB processing (Map: taskId → progress)
    private val _qualificationProgress = MutableStateFlow<Map<String, QualificationProgressInfo>>(emptyMap())
    val qualificationProgress: StateFlow<Map<String, QualificationProgressInfo>> = _qualificationProgress.asStateFlow()

    // Task history — COMPLETED tasks, loaded from server with pagination
    private val _taskHistory = MutableStateFlow<List<TaskHistoryEntry>>(emptyList())
    val taskHistory: StateFlow<List<TaskHistoryEntry>> = _taskHistory.asStateFlow()
    private var taskHistoryTotalCount: Long = 0
    private var taskHistoryLoading = false
    private val _taskHistoryHasMore = MutableStateFlow(false)
    val taskHistoryHasMore: StateFlow<Boolean> = _taskHistoryHasMore.asStateFlow()

    // Running task nodes — visible in Agent section, moved to _taskHistory on finalize
    private val _runningTaskNodes = MutableStateFlow<List<NodeEntry>>(emptyList())
    val runningTaskNodes: StateFlow<List<NodeEntry>> = _runningTaskNodes.asStateFlow()
    private var runningTaskStartTime: String? = null

    // Workspace status for selected project (derived from _projects + _selectedProjectId)
    data class WorkspaceInfo(
        val status: String?,    // READY, CLONING, CLONE_FAILED_AUTH/NETWORK/NOT_FOUND/OTHER, NOT_NEEDED, null
        val error: String?,
        val retryCount: Int,
        val nextRetryAt: String?,
    )

    val workspaceInfo: StateFlow<WorkspaceInfo?> = combine(_projects, _selectedProjectId) { projects, projectId ->
        if (projectId == null) return@combine null
        val project = projects.find { it.id == projectId } ?: return@combine null
        if (project.workspaceStatus == null || project.workspaceStatus == "NOT_NEEDED" || project.workspaceStatus == "READY") return@combine null
        WorkspaceInfo(
            status = project.workspaceStatus,
            error = project.workspaceError,
            retryCount = project.workspaceRetryCount,
            nextRetryAt = project.nextWorkspaceRetryAt,
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), null)

    // Orchestrator health (from queue status stream)
    private val _orchestratorHealthy = MutableStateFlow(true)
    val orchestratorHealthy: StateFlow<Boolean> = _orchestratorHealthy.asStateFlow()

    // Environment panel state
    private val _environments = MutableStateFlow<List<EnvironmentDto>>(emptyList())
    val environments: StateFlow<List<EnvironmentDto>> = _environments.asStateFlow()

    private val _resolvedEnvId = MutableStateFlow<String?>(null)
    val resolvedEnvId: StateFlow<String?> = _resolvedEnvId.asStateFlow()

    private val _environmentStatuses = MutableStateFlow<Map<String, EnvironmentStatusDto>>(emptyMap())
    val environmentStatuses: StateFlow<Map<String, EnvironmentStatusDto>> = _environmentStatuses.asStateFlow()

    private val _environmentPanelVisible = MutableStateFlow(false)
    val environmentPanelVisible: StateFlow<Boolean> = _environmentPanelVisible.asStateFlow()

    private val _environmentPanelWidthFraction = MutableStateFlow(0.35f)
    val environmentPanelWidthFraction: StateFlow<Float> = _environmentPanelWidthFraction.asStateFlow()

    private val _expandedEnvIds = MutableStateFlow<Set<String>>(emptySet())
    val expandedEnvIds: StateFlow<Set<String>> = _expandedEnvIds.asStateFlow()

    private val _expandedComponentIds = MutableStateFlow<Set<String>>(emptySet())
    val expandedComponentIds: StateFlow<Set<String>> = _expandedComponentIds.asStateFlow()

    private val _environmentLoading = MutableStateFlow(false)
    val environmentLoading: StateFlow<Boolean> = _environmentLoading.asStateFlow()

    private val _environmentError = MutableStateFlow<String?>(null)
    val environmentError: StateFlow<String?> = _environmentError.asStateFlow()

    // Show K8s badge only when environments actually exist for the client
    val hasEnvironment: Boolean get() = _environments.value.isNotEmpty()

    private var environmentPollingJob: Job? = null

    // Platform notification manager
    val notificationManager = PlatformNotificationManager()

    private var chatJob: Job? = null
    private var eventJob: Job? = null
    private var globalEventJob: Job? = null
    private var queueStatusJob: Job? = null

    init {
        // Initialize notifications
        notificationManager.initialize()

        // Restore pending message from persistent storage (survives app restart)
        pendingState = PendingMessageStorage.load()?.takeIf { !it.isExpired() }
        if (pendingState != null) {
            updatePendingInfo()
        }

        // Observe RpcConnectionManager state → drive local connection state + overlay
        scope.launch {
            connectionManager.state.collect { rpcState ->
                val gen = connectionManager.generation.value
                println("MainViewModel: RpcConnectionState changed to ${rpcState::class.simpleName} (gen=$gen)")
                when (rpcState) {
                    is RpcConnectionState.Connected -> {
                        _connectionState.value = ConnectionState.CONNECTED
                        overlayShowJob?.cancel() // cancel pending show

                        // Wait 500ms to confirm stability before hiding overlay
                        if (gen > 1 && _isOverlayVisible.value) {
                            overlayDebounceJob?.cancel()
                            overlayDebounceJob = scope.launch {
                                delay(500)
                                if (_connectionState.value == ConnectionState.CONNECTED) {
                                    println("MainViewModel: Hiding overlay after 500ms stability check")
                                    _isOverlayVisible.value = false
                                }
                            }
                        } else {
                            _isOverlayVisible.value = false
                        }

                        // Load clients when connected (fixes initial load before connection ready)
                        if (_clients.value.isEmpty()) {
                            loadClients()
                        }

                        // Subscribe to global queue status on every (re)connect
                        subscribeToQueueStatus("_global")

                        // Subscribe to global events (qualification progress, orchestrator)
                        // independent of selected client — these are broadcast to all streams
                        subscribeToGlobalEventStream()
                    }
                    is RpcConnectionState.Connecting -> {
                        _connectionState.value = ConnectionState.RECONNECTING
                        overlayDebounceJob?.cancel()
                        // Don't show overlay immediately on Connecting — wait for Disconnected
                    }
                    is RpcConnectionState.Disconnected -> {
                        _connectionState.value = ConnectionState.DISCONNECTED
                        overlayDebounceJob?.cancel()
                        // Show overlay after 500ms — brief disconnects won't blink
                        if (gen > 0) {
                            overlayShowJob?.cancel()
                            overlayShowJob = scope.launch {
                                delay(500)
                                if (_connectionState.value != ConnectionState.CONNECTED) {
                                    println("MainViewModel: Showing overlay (disconnected, gen=$gen)")
                                    _isOverlayVisible.value = true
                                }
                            }
                        }
                    }
                }
            }
        }

        // Auto-load projects if client is pre-selected
        _selectedClientId.value?.let { clientId ->
            selectClient(clientId)
        }

        // Subscribe to chat stream when both client and project are selected
        scope.launch {
            combine(_selectedClientId, _selectedProjectId) { clientId, projectId ->
                clientId to projectId
            }.collect { (clientId, projectId) ->
                println("MainViewModel: combine(client=$clientId, project=$projectId) emitted")
                if (clientId != null && projectId != null) {
                    subscribeToChatStream(clientId, projectId)
                }
            }
        }

        // Subscribe to global events for the selected client + register FCM token
        scope.launch {
            _selectedClientId.collect { clientId ->
                println("MainViewModel: _selectedClientId changed to $clientId")
                if (clientId != null) {
                    subscribeToEventStream(clientId)
                    // Register push token (FCM on Android, APNs on iOS, desktop)
                    scope.launch {
                        PushTokenRegistrar.registerIfNeeded(clientId, repository.deviceTokens)
                    }
                } else {
                    eventJob?.cancel()
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
                            refreshUserTaskCount()
                        } catch (e: Exception) {
                            println("Failed to approve task ${result.taskId}: ${e.message}")
                            _errorMessage.value = "Schválení selhalo: ${e.message}"
                        }
                        _userTaskDialogEvent.value = null
                    }
                    NotificationAction.DENY -> {
                        // Deny from notification → show in-app dialog for reason input
                        // The user task dialog will handle the actual deny with reason
                        _userTaskDialogEvent.value?.let { event ->
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

    /**
     * Global event stream — subscribes with "__global__" clientId.
     * Receives broadcasts (QualificationProgress, OrchestratorTaskProgress, etc.)
     * independent of selected client. Always active when connected.
     */
    private fun subscribeToGlobalEventStream() {
        globalEventJob?.cancel()
        globalEventJob = scope.launch {
            connectionManager.resilientFlow { services ->
                services.notificationService.subscribeToEvents("__global__")
            }.collect { event ->
                handleGlobalEvent(event)
            }
        }
    }

    private fun subscribeToEventStream(clientId: String) {
        println("MainViewModel: subscribeToEventStream(client=$clientId) — cancelling previous eventJob")
        eventJob?.cancel()
        eventJob = scope.launch {
            connectionManager.resilientFlow { services ->
                println("MainViewModel: resilientFlow subscribing to events (client=$clientId, gen=${connectionManager.generation.value})")
                services.notificationService.subscribeToEvents(clientId)
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
                refreshUserTaskCount()

                // Show platform notification
                notificationManager.showNotification(
                    title = if (event.isApproval) "Schválení vyžadováno" else "Nová úloha",
                    body = event.title,
                    taskId = event.taskId,
                    isApproval = event.isApproval,
                    interruptAction = event.interruptAction,
                )

                // Show in-app dialog for ALL user tasks (approval + clarification)
                _userTaskDialogEvent.value = event
            }

            is JervisEvent.UserTaskCancelled -> {
                _notifications.value =
                    currentNotifications.filter {
                        !(it is JervisEvent.UserTaskCreated && it.taskId == event.taskId)
                    }
                refreshUserTaskCount()
                notificationManager.cancelNotification(event.taskId)
                // Dismiss dialog if this task was cancelled
                if (_userTaskDialogEvent.value?.taskId == event.taskId) {
                    _userTaskDialogEvent.value = null
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
                // Update chat PROGRESS message with current orchestrator status
                replaceChatProgress(event.message, ChatMessage.MessageType.PROGRESS)
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

            is JervisEvent.QualificationProgress -> {
                if (event.step == "done" || event.step == "old_indexed" || event.step == "simple_action_handled") {
                    // Remove from active progress
                    _qualificationProgress.value = _qualificationProgress.value - event.taskId
                } else {
                    _qualificationProgress.value = _qualificationProgress.value + (
                        event.taskId to QualificationProgressInfo(
                            taskId = event.taskId,
                            message = event.message,
                            step = event.step,
                        )
                    )
                }
            }

            is JervisEvent.PendingTaskCreated -> {
                // Handled elsewhere if needed
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
                _userTaskDialogEvent.value = null
                notificationManager.cancelNotification(taskId)
                refreshUserTaskCount()
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
                _userTaskDialogEvent.value = null
                notificationManager.cancelNotification(taskId)
                refreshUserTaskCount()
            } catch (e: Exception) {
                _errorMessage.value = "Zamítnutí selhalo: ${e.message}"
            }
        }
    }

    /**
     * Reply to a clarification user task from the in-app dialog.
     */
    fun replyToTask(taskId: String, reply: String) {
        scope.launch {
            try {
                repository.userTasks.sendToAgent(
                    taskId = taskId,
                    routingMode = TaskRoutingMode.DIRECT_TO_AGENT,
                    additionalInput = reply,
                )
                _userTaskDialogEvent.value = null
                notificationManager.cancelNotification(taskId)
                refreshUserTaskCount()
            } catch (e: Exception) {
                _errorMessage.value = "Odeslání odpovědi selhalo: ${e.message}"
            }
        }
    }

    /**
     * Dismiss the user task dialog without action.
     * The task remains in the user tasks list.
     */
    fun dismissUserTaskDialog() {
        _userTaskDialogEvent.value = null
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
        history.add(0, entry) // newest first (optimistic)
        _taskHistory.value = history

        // Reset accumulator
        _runningTaskNodes.value = emptyList()
        runningTaskStartTime = null

        // Reload from server after short delay to get authoritative data
        scope.launch {
            delay(1000)
            loadTaskHistory()
        }
    }

    private fun subscribeToQueueStatus(clientId: String) {
        println("MainViewModel: subscribeToQueueStatus(client=$clientId) — cancelling previous queueStatusJob")
        queueStatusJob?.cancel()
        queueStatusJob = scope.launch {
            connectionManager.resilientFlow { services ->
                println("MainViewModel: resilientFlow subscribing to queueStatus (client=$clientId, gen=${connectionManager.generation.value})")
                services.agentOrchestratorService.subscribeToQueueStatus(clientId)
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

                    // Orchestrator health
                    val healthy = response.metadata["orchestratorHealthy"]?.toBooleanStrictOrNull() ?: true
                    _orchestratorHealthy.value = healthy
                }
            }
        }
    }

    private fun subscribeToChatStream(
        clientId: String,
        projectId: String,
    ) {
        println("MainViewModel: subscribeToChatStream(client=$clientId, project=$projectId) — cancelling previous chatJob")
        chatJob?.cancel()

        chatJob = scope.launch {
            connectionManager.resilientFlow { services ->
                println("MainViewModel: resilientFlow subscribing to chat (client=$clientId, project=$projectId, gen=${connectionManager.generation.value})")
                services.agentOrchestratorService.subscribeToChat(clientId, projectId)
            }.onStart {
                println("MainViewModel: Chat stream started (client=$clientId, project=$projectId), reloading history")
                _connectionState.value = ConnectionState.CONNECTED
                reloadHistory(clientId, projectId)
            }.collect { response ->
                handleChatResponse(response, clientId, projectId)
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

                ChatResponseType.STREAMING_TOKEN -> {
                    // Handle streaming tokens: accumulate by messageId, show progressive text
                    handleStreamingToken(response, projectId)
                    null // handled separately, skip normal flow
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

                // Clear pending message - server confirmed delivery
                val pending = pendingState
                if (pending != null && pending.text == response.message) {
                    retryJob?.cancel()
                    pendingState = null
                    PendingMessageStorage.save(null)
                    _pendingMessageInfo.value = null
                    println("=== Pending message cleared (confirmed by server) ===")
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
                // Remove ALL progress and streaming messages to clean up before final
                messages.removeAll {
                    it.messageType == ChatMessage.MessageType.PROGRESS ||
                        it.metadata["streaming"] == "true"
                }
                // Clear streaming buffer
                streamingBuffer.clear()

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

    private fun handleStreamingToken(
        response: com.jervis.dto.ChatResponseDto,
        projectId: String,
    ) {
        val messageId = response.messageId ?: return

        // Accumulate token into buffer
        val accumulated = (streamingBuffer[messageId] ?: "") + response.message
        streamingBuffer[messageId] = accumulated

        // Update or create streaming message in chat
        val messages = _chatMessages.value.toMutableList()
        val existingIdx = messages.indexOfLast { it.metadata["streamingId"] == messageId }

        val streamingMessage = ChatMessage(
            from = ChatMessage.Sender.Assistant,
            text = accumulated,
            contextId = projectId,
            messageType = ChatMessage.MessageType.FINAL,
            metadata = mapOf("streaming" to "true", "streamingId" to messageId),
        )

        if (existingIdx >= 0) {
            messages[existingIdx] = streamingMessage
        } else {
            // Remove progress messages when streaming starts
            messages.removeAll { it.messageType == ChatMessage.MessageType.PROGRESS }
            messages.add(streamingMessage)
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
                        sequence = msg.sequence,
                    )
                }
            _chatMessages.value = newMessages
            _hasMore.value = history.hasMore
            oldestSequence = history.oldestSequence
            _compressionBoundaries.value = history.compressionBoundaries

            // After successful reconnect, schedule retry for pending message
            pendingState?.let { state ->
                if (!state.isExpired()) {
                    println("=== Scheduling retry for pending message after reconnect ===")
                    updatePendingInfo()
                    scheduleAutoRetry()
                } else {
                    pendingState = null
                    PendingMessageStorage.save(null)
                    _pendingMessageInfo.value = null
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // kRPC wraps cancellation as IllegalStateException — don't log as error
            if (e is IllegalStateException && e.message?.contains("cancelled") == true) return
            println("Failed to reload history: ${e.message}")
        }
    }

    /**
     * Load older history messages (pagination).
     * Prepends to existing messages.
     */
    fun loadMoreHistory() {
        val clientId = _selectedClientId.value ?: return
        val projectId = _selectedProjectId.value ?: return
        val beforeSeq = oldestSequence ?: return
        if (_isLoadingMore.value) return

        scope.launch {
            _isLoadingMore.value = true
            try {
                val history = repository.agentOrchestrator.getChatHistory(
                    clientId, projectId, limit = 10, beforeSequence = beforeSeq
                )
                val olderMessages = history.messages.map { msg ->
                    ChatMessage(
                        from = if (msg.role == com.jervis.dto.ChatRole.USER) ChatMessage.Sender.Me else ChatMessage.Sender.Assistant,
                        text = msg.content,
                        contextId = projectId,
                        messageType = if (msg.role == com.jervis.dto.ChatRole.USER) ChatMessage.MessageType.USER_MESSAGE else ChatMessage.MessageType.FINAL,
                        metadata = msg.metadata,
                        timestamp = msg.timestamp,
                        workflowSteps = parseWorkflowSteps(msg.metadata),
                        sequence = msg.sequence,
                    )
                }
                _chatMessages.value = olderMessages + _chatMessages.value
                _hasMore.value = history.hasMore
                oldestSequence = history.oldestSequence
                // Merge compression boundaries
                _compressionBoundaries.value = (history.compressionBoundaries + _compressionBoundaries.value).distinctBy { it.afterSequence }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                println("Failed to load more history: ${e.message}")
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    /**
     * Set input text to the given message text for re-editing.
     */
    fun editMessage(text: String) {
        _inputText.value = text
    }

    /**
     * Open file picker and add selected file to attachments.
     */
    fun attachFile() {
        val file = pickFile() ?: return
        // Size limit: reject >10MB
        if (file.sizeBytes > 10 * 1024 * 1024) {
            _errorMessage.value = "Soubor je příliš velký (max 10 MB)"
            return
        }
        _attachments.value = _attachments.value + file
    }

    /**
     * Remove an attachment by index.
     */
    fun removeAttachment(index: Int) {
        val current = _attachments.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _attachments.value = current
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
        println("MainViewModel: selectClient($clientId) — previous: ${_selectedClientId.value}")

        // Cancel existing streams FIRST to prevent stale subscriptions
        chatJob?.cancel()
        chatJob = null

        // Set projectId to null BEFORE clientId to prevent combine() from emitting
        // (newClient, oldProject) which would subscribe to chat with wrong parameters
        _selectedProjectId.value = null
        _selectedGroupId.value = null
        _selectedClientId.value = clientId

        _projects.value = emptyList()
        _projectGroups.value = emptyList()
        _chatMessages.value = emptyList()

        // Reset environment state for new client
        _environments.value = emptyList()
        _resolvedEnvId.value = null
        _environmentStatuses.value = emptyMap()
        closeEnvironmentPanel()

        // Do NOT set _connectionState = DISCONNECTED — the RPC connection is still alive,
        // only the chat stream is being restarted. The connection observer handles real disconnects.

        scope.launch {
            _isLoading.value = true
            try {
                // Load projects and groups for this client
                val projectList = repository.projects.listProjectsForClient(clientId).filterVisible()
                _projects.value = projectList

                val allGroups = repository.projectGroups.getAllGroups()
                val clientGroups = allGroups.filter { it.clientId == clientId }
                _projectGroups.value = clientGroups

                // Restore last selected project if available
                val client = _clients.value.find { it.id == clientId }
                client?.lastSelectedProjectId?.let { lastProjectId ->
                    if (projectList.any { it.id == lastProjectId }) {
                        _selectedProjectId.value = lastProjectId
                    }
                }

                // Eagerly load environments to determine badge visibility
                loadEnvironments(clientId)

                // Load active user task count for badge
                try {
                    val countDto = repository.userTasks.activeCount(clientId)
                    _userTaskCount.value = countDto.activeCount
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    println("Failed to load user task count: ${e.message}")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _errorMessage.value = "Failed to load projects: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun selectGroup(groupId: String) {
        if (_selectedGroupId.value == groupId) return

        _selectedGroupId.value = groupId
        _selectedProjectId.value = null // Clear project selection when group is selected
        _chatMessages.value = emptyList() // Clear messages
        chatJob?.cancel()
    }

    fun selectProject(projectId: String) {
        if (projectId.isBlank()) {
            _selectedProjectId.value = null
            return
        }
        if (_selectedProjectId.value == projectId) return
        println("MainViewModel: selectProject($projectId) — previous: ${_selectedProjectId.value}")

        // Cancel existing stream FIRST
        chatJob?.cancel()
        chatJob = null

        _selectedProjectId.value = projectId
        _selectedGroupId.value = null
        _chatMessages.value = emptyList()

        // Do NOT set _connectionState = DISCONNECTED — the RPC connection is still alive,
        // only the chat stream is being restarted. The connection observer handles real disconnects.

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

    /**
     * Manually trigger reconnection to server.
     * Called when user clicks reconnect button in UI.
     */
    fun manualReconnect() {
        println("MainViewModel: Manual reconnect requested")
        connectionManager.requestReconnect()
    }

    @OptIn(ExperimentalEncodingApi::class, ExperimentalUuidApi::class)
    fun sendMessage() {
        val text = _inputText.value.trim()
        val currentAttachments = _attachments.value
        if (text.isEmpty() && currentAttachments.isEmpty()) return

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

        // Encode attachments to base64 for RPC transport
        val attachmentDtos = currentAttachments.map { file ->
            AttachmentDto(
                id = "",
                filename = file.filename,
                mimeType = file.mimeType,
                sizeBytes = file.sizeBytes,
                contentBase64 = Base64.encode(file.contentBytes),
            )
        }

        val clientMessageId = Uuid.random().toString()

        // Send message - server echo will be deduplicated in handleChatResponse
        scope.launch {
            _isLoading.value = true
            val originalText = text
            _inputText.value = "" // Clear input immediately
            _attachments.value = emptyList() // Clear attachments

            try {
                repository.agentOrchestrator.sendMessage(
                    ChatRequestDto(
                        text = originalText,
                        context =
                            ChatRequestContextDto(
                                clientId = clientId,
                                projectId = projectId,
                            ),
                        attachments = attachmentDtos,
                        clientMessageId = clientMessageId,
                    ),
                )
                println("=== Message sent successfully (RPC) ===")
                // NOTE: pending cleared only when server confirms via stream (handleChatResponse)

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
                if (e is CancellationException) throw e
                println("Error sending message: ${e.message}")
                e.printStackTrace()
                val error = classifySendError(e)
                pendingState = PendingMessageState(
                    text = originalText,
                    clientMessageId = clientMessageId,
                    timestampMs = Clock.System.now().toEpochMilliseconds(),
                    attemptCount = 0,
                    contextClientId = clientId,
                    contextProjectId = projectId,
                    lastErrorType = if (error.isRetryable) "network" else "server",
                    lastErrorMessage = error.displayMessage,
                )
                PendingMessageStorage.save(pendingState)
                // Remove optimistic message on failure
                _chatMessages.value = _chatMessages.value.filter { it !== optimisticMsg }
                _attachments.value = currentAttachments // Restore attachments on failure
                _errorMessage.value = error.displayMessage
                scheduleAutoRetry()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun retrySendMessage() {
        val state = pendingState ?: return
        val clientId = state.contextClientId ?: _selectedClientId.value ?: return
        val projectId = state.contextProjectId ?: _selectedProjectId.value

        retryJob?.cancel()
        scope.launch {
            _isLoading.value = true
            try {
                repository.agentOrchestrator.sendMessage(
                    ChatRequestDto(
                        text = state.text,
                        context = ChatRequestContextDto(
                            clientId = clientId,
                            projectId = projectId,
                        ),
                        clientMessageId = state.clientMessageId,
                    ),
                )
                println("=== Retried message sent successfully ===")
                pendingState = null
                PendingMessageStorage.save(null)
                _pendingMessageInfo.value = null
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                println("Error retrying message: ${e.message}")
                val error = classifySendError(e)
                pendingState = state.copy(
                    attemptCount = state.attemptCount + 1,
                    lastErrorType = if (error.isRetryable) "network" else "server",
                    lastErrorMessage = error.displayMessage,
                )
                PendingMessageStorage.save(pendingState)
                _errorMessage.value = error.displayMessage
                scheduleAutoRetry()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun cancelRetry() {
        retryJob?.cancel()
        pendingState = null
        PendingMessageStorage.save(null)
        _pendingMessageInfo.value = null
    }

    fun retryWorkspace() {
        val projectId = _selectedProjectId.value ?: return
        scope.launch {
            try {
                repository.projects.retryWorkspace(projectId)
                // Refresh projects to pick up workspace status change
                _selectedClientId.value?.let { selectClient(it) }
            } catch (_: Exception) {
                // Ignore — will retry automatically via backoff
            }
        }
    }

    private fun updatePendingInfo(nextRetryInSeconds: Int? = null, isAutoRetrying: Boolean = false) {
        val state = pendingState ?: run {
            _pendingMessageInfo.value = null
            return
        }
        _pendingMessageInfo.value = PendingMessageInfo(
            text = state.text,
            attemptCount = state.attemptCount,
            isAutoRetrying = isAutoRetrying,
            nextRetryInSeconds = nextRetryInSeconds,
            errorMessage = state.lastErrorMessage,
            isRetryable = state.lastErrorType == "network",
        )
    }

    private fun scheduleAutoRetry() {
        retryJob?.cancel()
        val state = pendingState ?: return

        if (state.attemptCount >= MAX_AUTO_RETRIES) {
            updatePendingInfo()
            return
        }

        if (state.lastErrorType == "server") {
            updatePendingInfo()
            return
        }

        val delayMs = BACKOFF_DELAYS_MS.getOrElse(state.attemptCount) { Long.MAX_VALUE }
        if (delayMs == Long.MAX_VALUE) {
            updatePendingInfo()
            return
        }

        retryJob = scope.launch {
            if (delayMs > 0) {
                val startTime = Clock.System.now().toEpochMilliseconds()
                while (true) {
                    val elapsed = Clock.System.now().toEpochMilliseconds() - startTime
                    val remaining = ((delayMs - elapsed) / 1000).toInt()
                    if (remaining <= 0) break
                    updatePendingInfo(nextRetryInSeconds = remaining, isAutoRetrying = true)
                    delay(1000)
                }
            }
            updatePendingInfo(isAutoRetrying = true)
            retrySendMessage()
        }
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
                // Also refresh task history from server
                loadTaskHistory()
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
                if (e is CancellationException) throw e
                if (e is IllegalStateException && e.message?.contains("cancelled") == true) return@launch
                println("Error refreshing queues: ${e.message}")
            }
        }
    }

    /**
     * Load task history from server. Replaces in-memory history.
     */
    fun loadTaskHistory() {
        if (taskHistoryLoading) return
        taskHistoryLoading = true
        scope.launch {
            try {
                val page = repository.agentOrchestrator.getTaskHistory(limit = 20, offset = 0)
                _taskHistory.value = page.items.map { it.toUiEntry() }
                taskHistoryTotalCount = page.totalCount
                _taskHistoryHasMore.value = page.hasMore
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                println("Error loading task history: ${e.message}")
            } finally {
                taskHistoryLoading = false
            }
        }
    }

    /**
     * Load more task history items (pagination).
     */
    fun loadMoreTaskHistory() {
        if (taskHistoryLoading) return
        if (!_taskHistoryHasMore.value) return
        taskHistoryLoading = true
        val currentSize = _taskHistory.value.size
        scope.launch {
            try {
                val page = repository.agentOrchestrator.getTaskHistory(limit = 20, offset = currentSize)
                val current = _taskHistory.value.toMutableList()
                current.addAll(page.items.map { it.toUiEntry() })
                _taskHistory.value = current
                _taskHistoryHasMore.value = page.hasMore
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                println("Error loading more task history: ${e.message}")
            } finally {
                taskHistoryLoading = false
            }
        }
    }

    private fun com.jervis.dto.TaskHistoryEntryDto.toUiEntry(): TaskHistoryEntry {
        return TaskHistoryEntry(
            taskId = taskId,
            taskPreview = taskPreview,
            projectName = projectName,
            startTime = formatIsoTime(startedAt),
            endTime = formatIsoTime(completedAt),
            status = status,
            nodes = nodes.map { NodeEntry(node = it.node, label = it.label, status = NodeStatus.DONE) },
        )
    }

    /** Extract HH:MM:SS from ISO 8601 timestamp string. */
    private fun formatIsoTime(isoTimestamp: String?): String {
        if (isoTimestamp == null) return ""
        // ISO format: 2026-02-17T12:34:56.789Z — extract time part
        val tIndex = isoTimestamp.indexOf('T')
        if (tIndex < 0) return isoTimestamp
        val timePart = isoTimestamp.substring(tIndex + 1).substringBefore('Z').substringBefore('+')
        return timePart.substringBefore('.').take(8) // HH:MM:SS
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

    // --- Environment Panel ---

    fun loadEnvironments(clientId: String) {
        scope.launch {
            _environmentLoading.value = true
            _environmentError.value = null
            try {
                val envList = repository.environments.listEnvironments(clientId)
                _environments.value = envList
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _environmentError.value = "Nepodařilo se načíst prostředí: ${e.message}"
            } finally {
                _environmentLoading.value = false
            }
        }
    }

    fun resolveEnvironment(projectId: String) {
        scope.launch {
            try {
                val env = repository.environments.resolveEnvironmentForProject(projectId)
                _resolvedEnvId.value = env?.id
                // Auto-expand the resolved environment
                if (env != null) {
                    _expandedEnvIds.value = _expandedEnvIds.value + env.id
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _resolvedEnvId.value = null
            }
        }
    }

    fun toggleEnvironmentPanel() {
        val newVisible = !_environmentPanelVisible.value
        _environmentPanelVisible.value = newVisible
        if (newVisible) {
            // Lazy load: fetch environments only when panel is opened
            val clientId = _selectedClientId.value
            if (clientId != null && _environments.value.isEmpty()) {
                loadEnvironments(clientId)
            }
            val projectId = _selectedProjectId.value
            if (projectId != null && _resolvedEnvId.value == null) {
                resolveEnvironment(projectId)
            }
            startEnvironmentPolling()
        } else {
            stopEnvironmentPolling()
        }
    }

    fun closeEnvironmentPanel() {
        _environmentPanelVisible.value = false
        stopEnvironmentPolling()
    }

    fun updatePanelWidthFraction(fraction: Float) {
        _environmentPanelWidthFraction.value = fraction
    }

    fun toggleEnvExpanded(envId: String) {
        val current = _expandedEnvIds.value
        _expandedEnvIds.value = if (current.contains(envId)) current - envId else current + envId
    }

    fun toggleComponentExpanded(componentId: String) {
        val current = _expandedComponentIds.value
        _expandedComponentIds.value = if (current.contains(componentId)) current - componentId else current + componentId
    }

    fun refreshEnvironments() {
        val clientId = _selectedClientId.value ?: return
        loadEnvironments(clientId)
        pollEnvironmentStatuses()
    }

    private fun startEnvironmentPolling() {
        stopEnvironmentPolling()
        environmentPollingJob = scope.launch {
            while (true) {
                pollEnvironmentStatuses()
                delay(30_000)
            }
        }
    }

    private fun stopEnvironmentPolling() {
        environmentPollingJob?.cancel()
        environmentPollingJob = null
    }

    private fun pollEnvironmentStatuses() {
        scope.launch {
            val envs = _environments.value
            val pollableStates = setOf(EnvironmentStateEnum.RUNNING, EnvironmentStateEnum.CREATING)
            val toPoll = envs.filter { it.state in pollableStates }
            val statuses = mutableMapOf<String, EnvironmentStatusDto>()
            // Keep old statuses for envs we don't re-poll
            statuses.putAll(_environmentStatuses.value)
            toPoll.forEach { env ->
                try {
                    val status = repository.environments.getEnvironmentStatus(env.id)
                    statuses[env.id] = status
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // Keep old status on error
                }
            }
            _environmentStatuses.value = statuses
        }
    }

    fun onDispose() {
        stopEnvironmentPolling()
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

/**
 * Live qualification progress info for a single task.
 */
data class QualificationProgressInfo(
    val taskId: String,
    val message: String,
    val step: String,
)
