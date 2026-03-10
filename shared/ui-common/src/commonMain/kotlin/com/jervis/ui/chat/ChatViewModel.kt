package com.jervis.ui.chat

import com.jervis.di.RpcConnectionManager
import com.jervis.dto.ChatResponseType
import com.jervis.dto.CompressionBoundaryDto
import com.jervis.dto.graph.TaskGraphDto
import com.jervis.dto.ui.ChatMessage
import com.jervis.repository.JervisRepository
import com.jervis.ui.model.PendingMessageInfo
import com.jervis.ui.model.classifySendError
import com.jervis.ui.storage.PendingMessageState
import com.jervis.ui.storage.PendingMessageStorage
import com.jervis.ui.storage.isExpired
import com.jervis.ui.util.PickedFile
import com.jervis.ui.util.pickFile
import com.jervis.dto.AttachmentDto
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * ViewModel for chat — messages, streaming, history, attachments, pending message retry.
 *
 * Receives selectedClientId/selectedProjectId as read-only StateFlows from MainViewModel.
 */
class ChatViewModel(
    private val repository: JervisRepository,
    private val connectionManager: RpcConnectionManager,
    private val selectedClientId: StateFlow<String?>,
    private val selectedProjectId: StateFlow<String?>,
    private val selectedGroupId: StateFlow<String?>,
    private val onScopeChange: (clientId: String, projectId: String?, projectsJson: String?, groupId: String?) -> Unit,
    private val onConnectionReady: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _compressionBoundaries = MutableStateFlow<List<CompressionBoundaryDto>>(emptyList())
    val compressionBoundaries: StateFlow<List<CompressionBoundaryDto>> = _compressionBoundaries.asStateFlow()

    private val _attachments = MutableStateFlow<List<PickedFile>>(emptyList())
    val attachments: StateFlow<List<PickedFile>> = _attachments.asStateFlow()

    private val _pendingMessageInfo = MutableStateFlow<PendingMessageInfo?>(null)
    val pendingMessageInfo: StateFlow<PendingMessageInfo?> = _pendingMessageInfo.asStateFlow()

    /** Context task ID for "Reagovat" — set when replying to a background result. */
    private var _contextTaskId: String? = null

    /** Pending approval request from chat — action, tool, preview text */
    data class ApprovalRequest(
        val action: String,
        val tool: String,
        val preview: String,
    )

    private val _approvalRequest = MutableStateFlow<ApprovalRequest?>(null)
    val approvalRequest: StateFlow<ApprovalRequest?> = _approvalRequest.asStateFlow()

    /** Cached task graphs keyed by taskId. null value = loading in progress. */
    private val _taskGraphs = MutableStateFlow<Map<String, TaskGraphDto?>>(emptyMap())
    val taskGraphs: StateFlow<Map<String, TaskGraphDto?>> = _taskGraphs.asStateFlow()

    /** Active map shown in the side panel (always = Paměťová mapa). */
    private val _activeThinkingMap = MutableStateFlow<TaskGraphDto?>(null)
    val activeThinkingMap: StateFlow<TaskGraphDto?> = _activeThinkingMap.asStateFlow()

    /** Debounce job for memory map refresh — prevents rapid repeated requests. */
    private var memoryMapLoadJob: Job? = null

    /** Detail sub-graph shown when user clicks on TASK_REF → thinking map link. */
    private val _detailThinkingMap = MutableStateFlow<TaskGraphDto?>(null)
    val detailThinkingMap: StateFlow<TaskGraphDto?> = _detailThinkingMap.asStateFlow()

    /** Task ID for live log streaming (coding agent SSE). */
    private val _liveLogTaskId = MutableStateFlow<String?>(null)
    val liveLogTaskId: StateFlow<String?> = _liveLogTaskId.asStateFlow()

    /** Job logs service for live SSE streaming in ThinkingMapPanel. */
    val jobLogsService get() = repository.jobLogs

    /** Whether the map side panel is visible (user toggle). */
    private val _thinkingMapPanelVisible = MutableStateFlow(false)
    val thinkingMapPanelVisible: StateFlow<Boolean> = _thinkingMapPanelVisible.asStateFlow()

    private val _thinkingMapPanelWidthFraction = MutableStateFlow(0.35f)
    val thinkingMapPanelWidthFraction: StateFlow<Float> = _thinkingMapPanelWidthFraction.asStateFlow()

    fun toggleThinkingMapPanel() {
        val newVisible = !_thinkingMapPanelVisible.value
        _thinkingMapPanelVisible.value = newVisible
        if (newVisible && _activeThinkingMap.value == null) {
            loadMemoryMap()
        }
    }

    fun closeThinkingMapPanel() {
        _thinkingMapPanelVisible.value = false
        _detailThinkingMap.value = null
        _liveLogTaskId.value = null
    }

    fun updateThinkingMapPanelWidthFraction(fraction: Float) {
        _thinkingMapPanelWidthFraction.value = fraction.coerceIn(0.2f, 0.6f)
    }

    /** Navigate into a sub-graph (thinking map) from a TASK_REF vertex. */
    fun openSubGraph(subGraphId: String) {
        scope.launch {
            try {
                val graph = repository.taskGraphs.getGraph(subGraphId)
                if (graph != null && graph.vertices.isNotEmpty()) {
                    _detailThinkingMap.value = graph
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                println("ChatViewModel: failed to load sub-graph $subGraphId: ${e.message}")
            }
        }
    }

    /** Return from sub-graph detail to memory map. */
    fun closeSubGraph() {
        _detailThinkingMap.value = null
    }

    /** Open live log streaming for a coding agent task. */
    fun openLiveLog(taskId: String) {
        _liveLogTaskId.value = taskId
    }

    /** Close live log panel. */
    fun closeLiveLog() {
        _liveLogTaskId.value = null
    }

    /** Show chat messages (user/assistant). Default ON. */
    private val _showChat = MutableStateFlow(true)
    val showChat: StateFlow<Boolean> = _showChat.asStateFlow()

    /** Show all background task results in chat. */
    private val _showTasks = MutableStateFlow(false)
    val showTasks: StateFlow<Boolean> = _showTasks.asStateFlow()

    /** Show only background tasks needing user reaction. Default ON. */
    private val _showNeedReaction = MutableStateFlow(true)
    val showNeedReaction: StateFlow<Boolean> = _showNeedReaction.asStateFlow()

    /** Total background messages in session (for filter chip label). */
    private val _backgroundMessageCount = MutableStateFlow(0)
    val backgroundMessageCount: StateFlow<Int> = _backgroundMessageCount.asStateFlow()

    /** Global USER_TASK count (all clients) — from ChatHistoryDto, matches dock badge. */
    private val _userTaskCount = MutableStateFlow(0)
    val userTaskCount: StateFlow<Int> = _userTaskCount.asStateFlow()

    // Filtering is done at the Compose layer via remember() in screens/MainScreen.kt
    // to avoid stateIn timing issues with the initial empty emission.

    private var oldestMessageId: String? = null
    private val streamingBuffer = mutableMapOf<String, String>()
    private val thinkingHistory = mutableListOf<String>()
    private var pendingState: PendingMessageState? = null
    private var retryJob: Job? = null
    private var chatJob: Job? = null

    companion object {
        private val BACKOFF_DELAYS_MS = listOf(0L, 5_000L, 30_000L, 300_000L)
        private const val MAX_AUTO_RETRIES = 4
    }

    init {
        // Restore pending message from persistent storage (survives app restart)
        pendingState = PendingMessageStorage.load()?.takeIf { !it.isExpired() }
        if (pendingState != null) {
            updatePendingInfo()
        }
    }

    fun subscribeToChatStream() {
        println("ChatViewModel: subscribeToChatStream() — global chat")
        chatJob?.cancel()
        chatJob = scope.launch {
            connectionManager.resilientFlow { services ->
                // Return a flow that first loads history, then streams events.
                // We're inside resilientFlow — services are connected and ready.
                services.chatService.subscribeToChatEvents().onStart {
                    onConnectionReady()
                    try {
                        val history = services.chatService.getChatHistory(limit = 50)
                        applyHistory(history)
                        println("ChatViewModel: history loaded — ${history.messages.size} msgs, hasMore=${history.hasMore}")
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        println("ChatViewModel: history load failed: ${e.message}")
                    }
                    // Load master map on connection ready
                    loadMemoryMap()
                }
            }.collect { response ->
                handleChatResponse(response)
            }
        }
    }

    /**
     * Replace the current PROGRESS chat message with new text/type.
     * Called from queue events via MainViewModel callback.
     */
    fun replaceChatProgress(text: String, messageType: ChatMessage.MessageType) {
        val messages = _chatMessages.value.toMutableList()
        val progressIdx = messages.indexOfLast {
            it.messageType == ChatMessage.MessageType.PROGRESS
        }
        if (progressIdx >= 0) {
            messages[progressIdx] = ChatMessage(
                from = ChatMessage.Sender.Assistant,
                text = text,
                contextId = selectedProjectId.value,
                messageType = messageType,
            )
            _chatMessages.value = messages
        }
    }

    fun updateInputText(text: String) {
        _inputText.value = text
    }

    fun editMessage(text: String) {
        _inputText.value = text
    }

    /**
     * Set context for replying to a background task result.
     * Pre-fills input and stores contextTaskId for sendMessage().
     */
    fun replyToTask(taskId: String) {
        _contextTaskId = taskId
        _inputText.value = ""  // Focus input, user types their reply
    }

    /**
     * Send reply to a background task result directly (inline from the bubble).
     * Bypasses chat pipeline — calls server directly via kRPC respondToTask.
     */
    fun sendReplyToTask(taskId: String, text: String) {
        if (_isLoading.value) return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        _isLoading.value = true

        scope.launch {
            try {
                repository.userTasks.respondToTask(taskId, trimmed)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Task may have been deleted/cancelled — still mark response in UI
                println("ChatViewModel: respondToTask failed (task may no longer exist): ${e.message}")
            }
            // Always update UI — user wrote the response regardless of server state
            _chatMessages.value = _chatMessages.value.map { msg ->
                if (msg.messageType == ChatMessage.MessageType.BACKGROUND_RESULT &&
                    msg.metadata["taskId"] == taskId
                ) {
                    msg.copy(userResponse = trimmed)
                } else {
                    msg
                }
            }
            _isLoading.value = false
        }
    }

    /**
     * Load task graph on demand. Caches result so subsequent calls are no-ops.
     * null value in the map means "loading in progress".
     */
    fun loadTaskGraph(taskId: String) {
        val existing = _taskGraphs.value[taskId]
        if (existing != null) return // already loaded (or confirmed not found)
        _taskGraphs.update { it + (taskId to null) } // mark loading
        scope.launch {
            try {
                val graph = repository.taskGraphs.getGraph(taskId)
                if (graph != null && graph.vertices.isNotEmpty()) {
                    println("ChatViewModel: graph loaded for taskId=$taskId — ${graph.vertices.size} vertices")
                    _taskGraphs.update { it + (taskId to graph) }
                } else {
                    println("ChatViewModel: graph NOT FOUND for taskId=$taskId")
                    // Store empty graph as sentinel — prevents repeated load attempts
                    _taskGraphs.update { it + (taskId to TaskGraphDto()) }
                }
            } catch (e: Exception) {
                println("ChatViewModel: graph load FAILED for taskId=$taskId — ${e.message}")
                _taskGraphs.update { it + (taskId to TaskGraphDto()) }
            }
        }
    }

    /**
     * Load or refresh the Paměťová mapa (memory map) from orchestrator.
     * Called on connection ready, after FINAL/BACKGROUND_RESULT, and on toggle.
     */
    internal fun loadMemoryMap() {
        memoryMapLoadJob?.cancel()
        memoryMapLoadJob = scope.launch {
            delay(500)
            try {
                val graph = repository.taskGraphs.getGraph("master")
                if (graph != null && graph.vertices.isNotEmpty()) {
                    println("ChatViewModel: memory map refreshed — ${graph.vertices.size} vertices")
                    _activeThinkingMap.value = graph
                } else {
                    println("ChatViewModel: memory map not found or empty")
                }
            } catch (e: Exception) {
                println("ChatViewModel: memory map refresh failed: ${e.message}")
            }
        }
    }

    /** Toggle chat messages visibility. */
    fun toggleChat() {
        _showChat.value = !_showChat.value
    }

    /** Toggle "Tasky" filter — all background task results. */
    fun toggleTasks() {
        _showTasks.value = !_showTasks.value
    }

    /** Toggle "K reakci" filter — backgrounds needing user reaction. */
    fun toggleNeedReaction() {
        _showNeedReaction.value = !_showNeedReaction.value
    }

    fun attachFile() {
        val file = pickFile() ?: return
        if (file.sizeBytes > 10 * 1024 * 1024) {
            onError("Soubor je příliš velký (max 10 MB)")
            return
        }
        _attachments.value = _attachments.value + file
    }

    fun removeAttachment(index: Int) {
        val current = _attachments.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _attachments.value = current
        }
    }

    @OptIn(ExperimentalEncodingApi::class, ExperimentalUuidApi::class)
    fun sendMessage() {
        // Guard: prevent duplicate sends while request is in-flight
        if (_isLoading.value) return

        val text = _inputText.value.trim()
        val currentAttachments = _attachments.value
        if (text.isEmpty() && currentAttachments.isEmpty()) return

        // Set loading immediately (before coroutine) to prevent race condition
        _isLoading.value = true

        val clientId = selectedClientId.value
        val projectId = selectedProjectId.value
        val groupId = selectedGroupId.value

        val attachmentMeta = if (currentAttachments.isNotEmpty()) {
            mapOf("attachments" to currentAttachments.joinToString(", ") { it.filename })
        } else {
            emptyMap()
        }
        val optimisticMsg = ChatMessage(
            from = ChatMessage.Sender.Me,
            text = text,
            contextId = projectId,
            messageType = ChatMessage.MessageType.USER_MESSAGE,
            metadata = attachmentMeta,
        )
        _chatMessages.value = _chatMessages.value + optimisticMsg

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

        scope.launch {
            val originalText = text
            _inputText.value = ""
            _attachments.value = emptyList()

            try {
                val taskContext = _contextTaskId
                _contextTaskId = null  // Clear after use
                repository.chat.sendMessage(
                    text = originalText,
                    clientMessageId = clientMessageId,
                    activeClientId = clientId,
                    activeProjectId = projectId,
                    activeGroupId = groupId,
                    contextTaskId = taskContext,
                )
                println("=== Message sent successfully (RPC) ===")

                val progressMsg = ChatMessage(
                    from = ChatMessage.Sender.Assistant,
                    text = "Zpracovávám...",
                    contextId = projectId,
                    messageType = ChatMessage.MessageType.PROGRESS,
                )
                _chatMessages.value = _chatMessages.value + progressMsg
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                println("Error sending message: ${e.message}")
                e.printStackTrace()

                // "RpcClient was cancelled" means WebSocket is dead — trigger immediate reconnect
                // instead of waiting 30s for the next health ping to detect the stale connection.
                if (e.message?.contains("cancelled", ignoreCase = true) == true) {
                    connectionManager.triggerReconnect("send failed: cancelled")
                }

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
                _chatMessages.value = _chatMessages.value.filter { it !== optimisticMsg }
                _attachments.value = currentAttachments
                onError(error.displayMessage)
                scheduleAutoRetry()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun retrySendMessage() {
        val state = pendingState ?: return
        val clientId = state.contextClientId ?: selectedClientId.value ?: return
        val projectId = state.contextProjectId ?: selectedProjectId.value
        val groupId = selectedGroupId.value

        retryJob?.cancel()
        scope.launch {
            _isLoading.value = true
            try {
                repository.chat.sendMessage(
                    text = state.text,
                    clientMessageId = state.clientMessageId,
                    activeClientId = clientId,
                    activeProjectId = projectId,
                    activeGroupId = groupId,
                )
                println("=== Retried message sent successfully ===")
                pendingState = null
                PendingMessageStorage.save(null)
                _pendingMessageInfo.value = null
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                println("Error retrying message: ${e.message}")

                // Trigger immediate reconnect on stale connection (same as sendMessage)
                if (e.message?.contains("cancelled", ignoreCase = true) == true) {
                    connectionManager.triggerReconnect("retry failed: cancelled")
                }

                val error = classifySendError(e)
                pendingState = state.copy(
                    attemptCount = state.attemptCount + 1,
                    lastErrorType = if (error.isRetryable) "network" else "server",
                    lastErrorMessage = error.displayMessage,
                )
                PendingMessageStorage.save(pendingState)
                onError(error.displayMessage)
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

    /** Approve the pending chat action (once or always). */
    fun approveChatAction(always: Boolean = false) {
        val request = _approvalRequest.value ?: return
        _approvalRequest.value = null
        scope.launch {
            try {
                repository.chat.approveChatAction(approved = true, always = always, action = request.action)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                println("Error approving chat action: ${e.message}")
            }
        }
    }

    /** Deny the pending chat action. */
    fun denyChatAction() {
        val request = _approvalRequest.value ?: return
        _approvalRequest.value = null
        scope.launch {
            try {
                repository.chat.approveChatAction(approved = false, always = false, action = request.action)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                println("Error denying chat action: ${e.message}")
            }
        }
    }

    fun loadMoreHistory() {
        val projectId = selectedProjectId.value ?: ""
        val beforeId = oldestMessageId ?: return
        if (_isLoadingMore.value) return

        scope.launch {
            _isLoadingMore.value = true
            try {
                val history = repository.chat.getChatHistory(
                    limit = 20, beforeMessageId = beforeId,
                )
                val olderMessages = history.messages.map { msg ->
                    val sender = if (msg.role == com.jervis.dto.ChatRole.USER) {
                        ChatMessage.Sender.Me
                    } else {
                        ChatMessage.Sender.Assistant
                    }
                    val msgType = when (msg.role) {
                        com.jervis.dto.ChatRole.USER -> ChatMessage.MessageType.USER_MESSAGE
                        com.jervis.dto.ChatRole.BACKGROUND -> ChatMessage.MessageType.BACKGROUND_RESULT
                        com.jervis.dto.ChatRole.ALERT -> ChatMessage.MessageType.URGENT_ALERT
                        else -> ChatMessage.MessageType.FINAL
                    }
                    ChatMessage(
                        from = sender,
                        text = msg.content,
                        contextId = projectId,
                        messageType = msgType,
                        metadata = msg.metadata,
                        timestamp = msg.timestamp,
                        workflowSteps = parseWorkflowSteps(msg.metadata),
                        sequence = msg.sequence,
                        id = msg.messageId,
                    )
                }
                _chatMessages.value = olderMessages + _chatMessages.value
                _hasMore.value = history.hasMore
                oldestMessageId = history.oldestMessageId
                _compressionBoundaries.value =
                    (history.compressionBoundaries + _compressionBoundaries.value)
                        .distinctBy { it.afterSequence }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                println("Failed to load more history: ${e.message}")
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    // --- Internal ---

    private suspend fun handleChatResponse(response: com.jervis.dto.ChatResponseDto) {
        val projectId = selectedProjectId.value ?: ""
        if (response.metadata["status"] == "synchronized" ||
            response.type == ChatResponseType.QUEUE_STATUS
        ) return

        val messageType = when (response.type) {
            ChatResponseType.USER_MESSAGE -> ChatMessage.MessageType.USER_MESSAGE
            ChatResponseType.PLANNING,
            ChatResponseType.EVIDENCE_GATHERING,
            ChatResponseType.EXECUTING,
            ChatResponseType.REVIEWING,
            -> ChatMessage.MessageType.PROGRESS

            ChatResponseType.FINAL -> ChatMessage.MessageType.FINAL
            ChatResponseType.ERROR -> ChatMessage.MessageType.ERROR

            ChatResponseType.STREAMING_TOKEN -> {
                handleStreamingToken(response, projectId)
                null
            }

            ChatResponseType.CHAT_CHANGED -> {
                println("=== Chat session changed, reloading history... ===")
                try {
                    val history = repository.chat.getChatHistory(limit = 50)
                    applyHistory(history)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    println("Chat changed reload failed: ${e.message}")
                }
                null
            }

            ChatResponseType.SCOPE_CHANGE -> {
                val newClientId = response.metadata["clientId"]
                val newProjectId = response.metadata["projectId"]?.takeIf { it.isNotBlank() }
                val projectsJson = response.metadata["projects"]
                val newGroupId = response.metadata["groupId"]?.takeIf { it.isNotBlank() }
                println("=== Chat scope change: client=$newClientId project=$newProjectId group=$newGroupId ===")
                if (!newClientId.isNullOrBlank()) {
                    onScopeChange(newClientId, newProjectId, projectsJson, newGroupId)
                }
                null
            }

            ChatResponseType.APPROVAL_REQUEST -> {
                val action = response.metadata["action"] ?: ""
                val tool = response.metadata["tool"] ?: ""
                println("=== Chat approval request: action=$action tool=$tool ===")
                _approvalRequest.value = ApprovalRequest(
                    action = action,
                    tool = tool,
                    preview = response.message,
                )
                null
            }

            ChatResponseType.BACKGROUND_RESULT -> ChatMessage.MessageType.BACKGROUND_RESULT
            ChatResponseType.URGENT_ALERT -> ChatMessage.MessageType.URGENT_ALERT
            ChatResponseType.THINKING_MAP_UPDATE -> ChatMessage.MessageType.THINKING_MAP_UPDATE

            else -> null
        }

        if (messageType == null) return

        println("=== Received chat message (${response.type}): ${response.message.take(100)} ===")
        val messages = _chatMessages.value.toMutableList()

        when (messageType) {
            ChatMessage.MessageType.USER_MESSAGE -> {
                val alreadyShown = messages.any {
                    it.from == ChatMessage.Sender.Me &&
                        it.text == response.message &&
                        it.messageType == ChatMessage.MessageType.USER_MESSAGE &&
                        it.timestamp == null
                }
                if (alreadyShown) {
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
                // Accumulate thinking steps (skip duplicates)
                val step = response.message
                if (step.isNotBlank() && (thinkingHistory.isEmpty() || thinkingHistory.last() != step)) {
                    thinkingHistory.add(step)
                }
                val progressMessage = ChatMessage(
                    from = ChatMessage.Sender.Assistant,
                    text = step,
                    contextId = projectId,
                    messageType = messageType,
                    metadata = response.metadata,
                    thinkingSteps = thinkingHistory.toList(),
                )
                val existingProgressIndex = messages.indexOfLast {
                    it.messageType == ChatMessage.MessageType.PROGRESS
                }
                if (existingProgressIndex >= 0) {
                    messages[existingProgressIndex] = progressMessage
                } else {
                    messages.add(progressMessage)
                }
            }

            ChatMessage.MessageType.FINAL -> {
                messages.removeAll {
                    it.messageType == ChatMessage.MessageType.PROGRESS ||
                        it.metadata["streaming"] == "true"
                }
                streamingBuffer.clear()
                thinkingHistory.clear()
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
                // Refresh master map after chat completion
                loadMemoryMap()
            }

            ChatMessage.MessageType.ERROR -> {
                messages.removeAll { it.messageType == ChatMessage.MessageType.PROGRESS }
                thinkingHistory.clear()
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

            ChatMessage.MessageType.APPROVAL_REQUEST -> {
                // Handled earlier via _approvalRequest StateFlow, not as a chat message
            }

            ChatMessage.MessageType.BACKGROUND_RESULT,
            ChatMessage.MessageType.URGENT_ALERT,
            -> {
                // Track new background messages for filter chip counter
                if (messageType == ChatMessage.MessageType.BACKGROUND_RESULT) {
                    _backgroundMessageCount.value++
                }
                // Append directly — no deduplication needed, these are push-only
                messages.add(
                    ChatMessage(
                        from = ChatMessage.Sender.Assistant,
                        text = response.message,
                        contextId = projectId,
                        messageType = messageType,
                        metadata = response.metadata,
                        timestamp = response.metadata["timestamp"],
                    ),
                )
                // Refresh master map after background task completion
                loadMemoryMap()
            }

            ChatMessage.MessageType.THINKING_MAP_UPDATE -> {
                // Refresh memory map when background updates arrive
                loadMemoryMap()
            }
        }
        _chatMessages.value = messages
    }

    private fun handleStreamingToken(response: com.jervis.dto.ChatResponseDto, projectId: String) {
        val messageId = response.messageId ?: return
        val accumulated = (streamingBuffer[messageId] ?: "") + response.message
        streamingBuffer[messageId] = accumulated

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
            messages.removeAll { it.messageType == ChatMessage.MessageType.PROGRESS }
            messages.add(streamingMessage)
        }
        _chatMessages.value = messages
    }

    /**
     * Apply loaded chat history to UI state.
     * Called from subscribeToChatStream (with connected services) and loadMoreHistory.
     */
    private fun applyHistory(history: com.jervis.dto.ChatHistoryDto) {
        val projectId = selectedProjectId.value ?: ""
        _backgroundMessageCount.value = history.backgroundMessageCount
        _userTaskCount.value = history.userTaskCount
        val newMessages = history.messages.map { msg ->
            val sender = if (msg.role == com.jervis.dto.ChatRole.USER) {
                ChatMessage.Sender.Me
            } else {
                ChatMessage.Sender.Assistant
            }
            val msgType = when (msg.role) {
                com.jervis.dto.ChatRole.USER -> ChatMessage.MessageType.USER_MESSAGE
                com.jervis.dto.ChatRole.BACKGROUND -> ChatMessage.MessageType.BACKGROUND_RESULT
                com.jervis.dto.ChatRole.ALERT -> ChatMessage.MessageType.URGENT_ALERT
                else -> ChatMessage.MessageType.FINAL
            }
            ChatMessage(
                from = sender,
                text = msg.content,
                contextId = projectId,
                messageType = msgType,
                metadata = msg.metadata,
                timestamp = msg.timestamp,
                workflowSteps = parseWorkflowSteps(msg.metadata),
                sequence = msg.sequence,
                id = msg.messageId,
            )
        }
        // Merge: preserve in-flight messages (not yet in DB) so they don't vanish on reconnect.
        val inFlight = _chatMessages.value.filter { msg ->
            msg.sequence == null &&
                msg.metadata["streaming"] != "true" &&
                (msg.messageType == ChatMessage.MessageType.USER_MESSAGE ||
                    msg.messageType == ChatMessage.MessageType.PROGRESS ||
                    msg.messageType == ChatMessage.MessageType.FINAL)
        }
        val deduped = inFlight.filter { flight ->
            newMessages.none { db -> db.text == flight.text && db.from == flight.from }
        }
        _chatMessages.value = newMessages + deduped
        _hasMore.value = history.hasMore
        oldestMessageId = history.oldestMessageId
        _compressionBoundaries.value = history.compressionBoundaries

        // Restore UI scope from chat session (persisted client/project/group)
        val restoredClientId = history.activeClientId
        if (!restoredClientId.isNullOrBlank()) {
            onScopeChange(restoredClientId, history.activeProjectId, null, history.activeGroupId)
        }

        pendingState?.let { state ->
            if (!state.isExpired()) {
                println("=== Scheduling retry for pending message after reconnect ===")
                if (state.lastErrorType == "server") {
                    pendingState = state.copy(lastErrorType = "network")
                    PendingMessageStorage.save(pendingState)
                }
                updatePendingInfo()
                scheduleAutoRetry()
            } else {
                pendingState = null
                PendingMessageStorage.save(null)
                _pendingMessageInfo.value = null
            }
        }
    }

    private fun parseWorkflowSteps(metadata: Map<String, String>): List<ChatMessage.WorkflowStep> {
        val json = metadata["workflowSteps"] ?: return emptyList()
        return try {
            Json.decodeFromString<List<ChatMessage.WorkflowStep>>(json)
        } catch (e: Exception) {
            println("Failed to deserialize workflow steps: ${e.message}")
            emptyList()
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
}
