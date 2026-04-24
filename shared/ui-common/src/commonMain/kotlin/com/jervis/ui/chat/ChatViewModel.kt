package com.jervis.ui.chat

import com.jervis.di.RpcConnectionManager
import com.jervis.di.SseEvent
import com.jervis.di.buildMultipartBody
import com.jervis.di.postSseStream
import com.jervis.ui.audio.AudioPlayer
import com.jervis.ui.audio.AudioRecorder
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.jervis.dto.chat.ChatResponseType
import com.jervis.dto.chat.ChatRole
import com.jervis.dto.chat.CompressionBoundaryDto
import com.jervis.dto.graph.TaskGraphDto
import com.jervis.dto.ui.ChatMessage
import com.jervis.di.JervisRepository
import com.jervis.service.meeting.IJobLogsService
import com.jervis.ui.model.PendingMessageInfo
import com.jervis.ui.model.classifySendError
import com.jervis.ui.storage.PendingMessageState
import com.jervis.ui.storage.PendingMessageStorage
import com.jervis.ui.storage.isExpired
import com.jervis.ui.util.PickedFile
import com.jervis.ui.util.currentVoiceSource
import com.jervis.ui.util.pickFile
import com.jervis.dto.chat.AttachmentDto
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val onStatusDetail: (String?) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + CoroutineExceptionHandler { _, e ->
        if (e !is CancellationException) {
            println("ChatViewModel: uncaught exception: ${e::class.simpleName}: ${e.message}")
        }
    })

    // Current scope for filtering getChatHistory calls
    private val currentFilterClientId get() = selectedClientId.value
    private val currentFilterProjectId get() = selectedProjectId.value
    private val currentFilterGroupId get() = selectedGroupId.value

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    /**
     * Phase 5 chat-as-primary: when non-null, the chat plane is showing the
     * conversation history of one specific task (sidebar drill-in). When null
     * the chat shows the global "main chat" view.
     */
    private val _activeChatTaskId = MutableStateFlow<String?>(null)
    val activeChatTaskId: StateFlow<String?> = _activeChatTaskId.asStateFlow()

    private val _activeChatTaskName = MutableStateFlow<String?>(null)
    val activeChatTaskName: StateFlow<String?> = _activeChatTaskName.asStateFlow()

    /**
     * Live task state for the currently open drill-in — populated by the
     * `subscribeTask(taskId)` stream. Guideline #9: no one-shot `getById`,
     * no stale value; the server pushes on every state change so the
     * breadcrumb (Otevřít znovu / Hotovo) always matches the sidebar.
     */
    private val _activeChatTaskState = MutableStateFlow<String?>(null)
    val activeChatTaskState: StateFlow<String?> = _activeChatTaskState.asStateFlow()

    /**
     * Snapshot of the active drill-in task (task + related tasks). Pushed by
     * the server whenever the task changes. Null when no task is open.
     */
    private val _taskSnapshot = MutableStateFlow<com.jervis.dto.task.TaskSnapshot?>(null)
    val taskSnapshot: StateFlow<com.jervis.dto.task.TaskSnapshot?> = _taskSnapshot.asStateFlow()

    /**
     * Sidebar toggle: show active tasks or DONE history. Changing this
     * re-opens the sidebar stream with the new parameter — no client-side
     * filtering, DB does it.
     */
    private val _showDoneInSidebar = MutableStateFlow(false)
    val showDoneInSidebar: StateFlow<Boolean> = _showDoneInSidebar.asStateFlow()

    fun toggleShowDoneInSidebar() {
        _showDoneInSidebar.value = !_showDoneInSidebar.value
    }

    /**
     * Server-pushed sidebar snapshot. The stream re-opens on clientId /
     * showDone change and on every RpcConnectionManager reconnect (via
     * resilientFlow). Null until the first emit.
     */
    private val _sidebarSnapshot = MutableStateFlow<com.jervis.dto.task.SidebarSnapshot?>(null)
    val sidebarSnapshot: StateFlow<com.jervis.dto.task.SidebarSnapshot?> = _sidebarSnapshot.asStateFlow()

    /** Phase 5 — chat sidebar split fraction (resizable rail). */
    private val _chatSidebarSplitFraction = MutableStateFlow(0.22f)
    val chatSidebarSplitFraction: StateFlow<Float> = _chatSidebarSplitFraction.asStateFlow()

    /** Task IDs recently marked done — sidebar filters these from its list to prevent flicker.
     *  Cleared automatically when server confirms the task is no longer in active states. */
    private val _sidebarRemovedTaskIds = MutableStateFlow<Set<String>>(emptySet())
    val sidebarRemovedTaskIds: StateFlow<Set<String>> = _sidebarRemovedTaskIds.asStateFlow()

    /**
     * Phase 5 — draft persistence: unsent text per conversation. Key = null
     * for main chat, taskId for task conversations. Saved to server via
     * chatService.saveDraft on every conversation switch. Loaded from server
     * on startup via chatService.loadDrafts. Survives app restart.
     */
    private val drafts = mutableMapOf<String?, String>()
    private var draftSaveJob: kotlinx.coroutines.Job? = null

    /** Persist draft to server (fire-and-forget, non-blocking). */
    private fun persistDraftToServer(conversationId: String?, text: String) {
        scope.launch {
            try {
                repository.call { s -> s.chatService.saveDraft(conversationId, text) }
            } catch (_: Exception) { /* non-critical */ }
        }
    }

    fun updateChatSidebarSplitFraction(fraction: Float) {
        val clamped = fraction.coerceIn(0.15f, 0.5f)
        _chatSidebarSplitFraction.value = clamped
        // Persist to server (debounced — only saves when user stops dragging)
        scope.launch {
            try {
                repository.call { s -> s.chatService.saveUiSetting("sidebarSplitFraction", clamped.toString()) }
            } catch (_: Exception) { /* non-critical */ }
        }
    }

    /**
     * Phase 5 — user marks the currently active task as DONE. Same action
     * the chat agent has via its tools (no privileged path). After mark
     * done, refresh the chat plane to show the updated state and bounce
     * back to the main chat (the task is no longer in the active sidebar).
     */
    fun markActiveTaskDone(note: String? = null) {
        val taskId = _activeChatTaskId.value ?: return
        // Optimistic: add to removed set immediately — sidebar filters this synchronously
        _sidebarRemovedTaskIds.update { it + taskId }
        scope.launch {
            try {
                repository.call { services -> services.pendingTaskService.markDone(taskId, note) }
                println("ChatViewModel: marked task $taskId as DONE")
                // Find the next active task to navigate to (instead of main chat)
                val nextTask = findNextActiveTask(taskId)
                if (nextTask != null) {
                    switchToTaskConversation(
                        nextTask.id,
                        nextTask.summary
                            ?: nextTask.taskName.takeIf { it.isNotBlank() && it != "Unnamed Task" }
                            ?: nextTask.sourceLabel.ifBlank { "Úloha" },
                    )
                } else {
                    switchToMainChat()
                }
                // Sidebar snapshot is pushed by the server stream — no manual reload.
            } catch (e: Exception) {
                println("ChatViewModel: markActiveTaskDone failed: ${e.message}")
            }
        }
    }

    /**
     * Load active tasks and find the next one after the given taskId.
     * Returns the next task in sidebar order, or the previous one if it was
     * the last. Returns null if no active tasks remain.
     */
    private suspend fun findNextActiveTask(currentTaskId: String): com.jervis.dto.task.PendingTaskDto? {
        val activeStates = listOf("USER_TASK", "PROCESSING", "QUEUED", "BLOCKED", "INDEXING", "NEW", "ERROR")
        val allTasks = mutableListOf<com.jervis.dto.task.PendingTaskDto>()
        for (state in activeStates) {
            try {
                val page = repository.pendingTasks.listTasksPaged(
                    taskType = null, state = state, page = 0, pageSize = 20,
                    clientId = null, sourceScheme = null, parentTaskId = null, textQuery = null,
                )
                allTasks.addAll(page.items)
            } catch (_: Exception) { /* skip */ }
        }
        // Sort same as sidebar: state priority, then createdAt desc
        val sorted = allTasks
            .filter { it.id != currentTaskId }
            .sortedWith(
                compareBy<com.jervis.dto.task.PendingTaskDto> { sidebarStatePriority(it.state) }
                    .thenByDescending { it.createdAt },
            )
        return sorted.firstOrNull()
    }

    private fun sidebarStatePriority(state: String): Int = when (state) {
        "USER_TASK" -> 0
        "PROCESSING", "CODING" -> 1
        "QUEUED" -> 2
        "NEW", "INDEXING" -> 3
        "BLOCKED" -> 4
        "ERROR" -> 5
        else -> 5
    }

    /**
     * Phase 5 — user reopens the currently active task. Transitions to
     * NEW + needsQualification=true. The re-entrant qualifier picks it up.
     */
    fun reopenActiveTask(note: String? = null) {
        val taskId = _activeChatTaskId.value ?: return
        val taskName = _activeChatTaskName.value
        scope.launch {
            try {
                repository.call { services -> services.pendingTaskService.reopen(taskId, note) }
                println("ChatViewModel: reopened task $taskId")
                switchToTaskConversation(taskId, taskName)
                // Task + sidebar snapshots arrive automatically via streams.
            } catch (e: Exception) {
                println("ChatViewModel: reopenActiveTask failed: ${e.message}")
            }
        }
    }

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    // Voice recording state
    private val _isRecordingVoice = MutableStateFlow(false)
    val isRecordingVoice: StateFlow<Boolean> = _isRecordingVoice.asStateFlow()

    private val _voiceStatus = MutableStateFlow("")
    val voiceStatus: StateFlow<String> = _voiceStatus.asStateFlow()

    /** Real-time audio amplitude from mic (0.0–1.0) for level meter visualization. */
    private val _voiceAudioLevel = MutableStateFlow(0f)
    val voiceAudioLevel: StateFlow<Float> = _voiceAudioLevel.asStateFlow()

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

    /** Active graph shown in the side panel (always = Paměťový graf). */
    private val _activeThinkingGraph = MutableStateFlow<TaskGraphDto?>(null)
    val activeThinkingGraph: StateFlow<TaskGraphDto?> = _activeThinkingGraph.asStateFlow()

    /** Debounce job for memory graph refresh — prevents rapid repeated requests. */

    /** Detail sub-graph shown when user clicks on TASK_REF → thinking graph link. */
    private val _detailThinkingGraph = MutableStateFlow<TaskGraphDto?>(null)
    val detailThinkingGraph: StateFlow<TaskGraphDto?> = _detailThinkingGraph.asStateFlow()

    /** Loading state for sub-graph fetch. */
    private val _subGraphLoading = MutableStateFlow(false)
    val subGraphLoading: StateFlow<Boolean> = _subGraphLoading.asStateFlow()

    /** Task ID for live log streaming (coding agent SSE). */
    private val _liveLogTaskId = MutableStateFlow<String?>(null)
    val liveLogTaskId: StateFlow<String?> = _liveLogTaskId.asStateFlow()

    /** Job logs service for live SSE streaming in thinking graph panel. */
    val jobLogsService: IJobLogsService? get() = try { repository.jobLogs } catch (_: Exception) { null }

    /** Active Thought Map context from spreading activation. */
    data class ActiveThoughtContext(
        val formattedContext: String = "",
        val activatedThoughtIds: List<String> = emptyList(),
        val thoughtCount: Int = 0,
    )

    private val _activeThoughtContext = MutableStateFlow<ActiveThoughtContext?>(null)
    val activeThoughtContext: StateFlow<ActiveThoughtContext?> = _activeThoughtContext.asStateFlow()

    /** Whether the map side panel is visible (user toggle). */
    private val _thinkingGraphPanelVisible = MutableStateFlow(false)
    val thinkingGraphPanelVisible: StateFlow<Boolean> = _thinkingGraphPanelVisible.asStateFlow()

    private val _thinkingGraphPanelWidthFraction = MutableStateFlow(0.35f)
    val thinkingGraphPanelWidthFraction: StateFlow<Float> = _thinkingGraphPanelWidthFraction.asStateFlow()

    fun toggleThinkingGraphPanel() {
        val newVisible = !_thinkingGraphPanelVisible.value
        _thinkingGraphPanelVisible.value = newVisible
        if (newVisible && _activeThinkingGraph.value == null) {
        }
    }

    fun closeThinkingGraphPanel() {
        _thinkingGraphPanelVisible.value = false
        _detailThinkingGraph.value = null
        _liveLogTaskId.value = null
    }

    fun updateThinkingGraphPanelWidthFraction(fraction: Float) {
        _thinkingGraphPanelWidthFraction.value = fraction.coerceIn(0.2f, 0.6f)
    }

    /** Navigate into a sub-graph (thinking graph) from a TASK_REF vertex. */
    fun openSubGraph(subGraphId: String) {
        scope.launch {
            _subGraphLoading.value = true
            try {
                // Try the provided ID first (may be graph ID "tg-..." or task ID)
                var graph = repository.taskGraphs.getGraph(subGraphId)

                // If not found and ID looks like a graph ID, try extracting task ID from it
                if ((graph == null || graph.vertices.isEmpty()) && subGraphId.startsWith("tg-")) {
                    val parts = subGraphId.removePrefix("tg-").split("-")
                    // tg-<taskId>-<random> → extract taskId (24 hex chars)
                    val extractedTaskId = parts.firstOrNull()?.takeIf { it.length == 24 }
                    if (extractedTaskId != null) {
                        graph = repository.taskGraphs.getGraph(extractedTaskId)
                    }
                }

                if (graph != null && graph.vertices.isNotEmpty()) {
                    _detailThinkingGraph.value = graph
                } else {
                    onError("Myšlenkový graf nenalezen (ID: ${subGraphId.take(20)}...)")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onError("Chyba při načítání myšlenkové mapy: ${e.message}")
            } finally {
                _subGraphLoading.value = false
            }
        }
    }

    /** Return from sub-graph detail to memory graph. */
    fun closeSubGraph() {
        _detailThinkingGraph.value = null
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

    /** Show all background task results. */
    private val _showTasks = MutableStateFlow(false)
    val showTasks: StateFlow<Boolean> = _showTasks.asStateFlow()

    /** Show actionable items (failed background + USER_TASK). Default OFF per docs. */
    private val _showNeedReaction = MutableStateFlow(false)
    val showNeedReaction: StateFlow<Boolean> = _showNeedReaction.asStateFlow()

    /** Total background messages in session (for filter chip label). */
    private val _backgroundMessageCount = MutableStateFlow(0)
    val backgroundMessageCount: StateFlow<Int> = _backgroundMessageCount.asStateFlow()

    /** Global USER_TASK count (all clients) — from ChatHistoryDto, matches dock badge. */
    private val _userTaskCount = MutableStateFlow(0)
    val userTaskCount: StateFlow<Int> = _userTaskCount.asStateFlow()

    /**
     * Temporary OpenRouter tier override for this chat session.
     * null = use policy from client/project settings.
     * "NONE" / "FREE" / "PAID" / "PREMIUM" = override until changed or context switch.
     */
    private val _tierOverride = MutableStateFlow<String?>(null)
    val tierOverride: StateFlow<String?> = _tierOverride.asStateFlow()

    /** Set temporary tier override. null = reset to policy. */
    fun setTierOverride(tier: String?) {
        _tierOverride.value = tier
    }

    // pendingUserTasks removed — unified timeline handled server-side via $unionWith

    // Filtering is done at the Compose layer via remember() in screens/MainScreen.kt
    // to avoid stateIn timing issues with the initial empty emission.

    private var oldestMessageId: String? = null
    private val streamingBuffer = mutableMapOf<String, String>()
    private val thinkingHistory = mutableListOf<String>()
    private var pendingState: PendingMessageState? = null
    private var retryJob: Job? = null
    private var chatJob: Job? = null
    private var scopeWatchJob: Job? = null
    private var historyReloadJob: Job? = null
    private var progressTimeoutJob: Job? = null
    private var sidebarStreamJob: Job? = null
    private var taskStreamJob: Job? = null

    companion object {
        private val BACKOFF_DELAYS_MS = listOf(0L, 5_000L, 30_000L, 300_000L)
        private const val MAX_AUTO_RETRIES = 4
        private const val USER_TASK_PAGE_SIZE = 20
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
        historyReloadJob?.cancel()

        // Watch scope changes — reload messages when client/project changes
        scopeWatchJob?.cancel()
        scopeWatchJob = scope.launch {
            var lastClient = selectedClientId.value
            var lastProject = selectedProjectId.value
            kotlinx.coroutines.flow.combine(selectedClientId, selectedProjectId) { c, p -> c to p }
                .collect { (newClient, newProject) ->
                    if (newClient != lastClient || newProject != lastProject) {
                        lastClient = newClient
                        lastProject = newProject
                        println("ChatViewModel: scope changed → client=$newClient project=$newProject, reloading")
                        reloadForCurrentFilter()
                    }
                }
        }

        // (A) Decoupled history reload driven by RpcConnectionManager.generation.
        //     Every time a successful (re)connect bumps the generation, we re-fetch
        //     the last 50 chat messages from the server via repository.call — which
        //     is resilient (waits for Connected state if we're mid-reconnect).
        //     This replaces the old .onStart { } approach that ran only once.
        historyReloadJob = scope.launch {
            connectionManager.generation.collect { gen ->
                if (gen <= 0L) return@collect  // initial state, manager not connected yet
                println("ChatViewModel: generation=$gen — reloading history")
                try {
                    // Wait for client selection if scope not set yet (first boot)
                    if (currentFilterClientId == null) {
                        println("ChatViewModel: waiting for client selection before loading history...")
                        selectedClientId.first { it != null }
                        println("ChatViewModel: client selected: ${currentFilterClientId}")
                    }
                    onConnectionReady()
                    onStatusDetail("stream")
                    val history = repository.call { services ->
                        services.chatService.getChatHistory(
                            limit = 50,
                            showChat = _showChat.value,
                            showTasks = _showTasks.value,
                            showNeedReaction = _showNeedReaction.value,
                            filterClientId = currentFilterClientId,
                            filterProjectId = currentFilterProjectId,
                            filterGroupId = currentFilterGroupId,
                        )
                    }
                    applyHistory(history)
                    // Load server-side drafts on connection ready
                    try {
                        val serverDrafts = repository.call { s -> s.chatService.loadDrafts() }
                        drafts.clear()
                        serverDrafts.forEach { (k, v) ->
                            drafts[if (k == "__main__") null else k] = v
                        }
                        // Restore draft for current conversation
                        _inputText.value = drafts[_activeChatTaskId.value] ?: ""
                    } catch (_: Exception) { /* non-critical */ }
                    // Restore UI settings (sidebar width, etc.)
                    try {
                        val uiSettings = repository.call { s -> s.chatService.loadUiSettings() }
                        uiSettings["sidebarSplitFraction"]?.toFloatOrNull()?.let {
                            _chatSidebarSplitFraction.value = it.coerceIn(0.15f, 0.5f)
                        }
                    } catch (_: Exception) { /* non-critical */ }
                    onStatusDetail("ok")
                    println("ChatViewModel: history loaded (gen=$gen) — ${history.messages.size} msgs, " +
                        "hasMore=${history.hasMore}, chat=${_showChat.value}, tasks=${_showTasks.value}, " +
                        "reaction=${_showNeedReaction.value}")
                    // Load master graph on connection ready
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    onStatusDetail("history err")
                    println("ChatViewModel: history reload failed (gen=$gen): ${e.message}")
                    // Do NOT rethrow — keep the generation watcher alive for the next bump.
                }
            }
        }

        // (B) Chat event stream subscription via resilientFlow — the loop inside
        //     RpcConnectionManager auto-rehydrates on every reconnect, so we just
        //     collect forever. No .onStart, no history load here.
        chatJob = scope.launch {
            connectionManager.resilientFlow { services ->
                services.chatService.subscribeToChatEvents()
            }.collect { response ->
                handleChatResponse(response)
            }
        }

        // (C) Push-only sidebar stream — guideline #9. Server pushes fresh
        //     SidebarSnapshot on every task write; UI renders via collectAsState.
        //     Sidebar is ALWAYS GLOBAL (ignores scope) — user wants to see
        //     everything Jervis is doing, not just the active client.
        //     The stream re-opens only on showDone change and on reconnect.
        sidebarStreamJob?.cancel()
        sidebarStreamJob = scope.launch {
            _showDoneInSidebar.collectLatest { showDone ->
                connectionManager.resilientFlow { services ->
                    services.pendingTaskService.subscribeSidebar(null, showDone)
                }.collect { snap -> _sidebarSnapshot.value = snap }
            }
        }

        // (D) Per-task drill-in stream — active only when the user has opened
        //     a task. Pushes TaskSnapshot (task + related) on every write,
        //     so the breadcrumb state ("Otevřít znovu" / "Hotovo") is always
        //     current. Fixes the stale _activeChatTaskState bug.
        taskStreamJob?.cancel()
        taskStreamJob = scope.launch {
            _activeChatTaskId.collectLatest { taskId ->
                if (taskId.isNullOrBlank()) {
                    _taskSnapshot.value = null
                    return@collectLatest
                }
                connectionManager.resilientFlow { services ->
                    services.pendingTaskService.subscribeTask(taskId)
                }.collect { snap ->
                    _taskSnapshot.value = snap
                    _activeChatTaskState.value = snap.task.state
                }
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
     * Phase 5 chat-as-primary: switch the chat plane to a specific task's
     * conversation. Loads the task's full message history and renders it in
     * place of the main chat. Use [switchToMainChat] to return.
     */
    fun switchToTaskConversation(taskId: String, taskName: String?) {
        // Save current draft before switching (local + server)
        val currentKey = _activeChatTaskId.value
        val currentText = _inputText.value
        if (currentText.isNotBlank()) {
            drafts[currentKey] = currentText
        } else {
            drafts.remove(currentKey)
        }
        persistDraftToServer(currentKey, currentText)
        _activeChatTaskId.value = taskId
        _activeChatTaskName.value = taskName
        // _activeChatTaskState is now driven by the subscribeTask(taskId) stream
        // (see taskStreamJob in subscribeToChatStream) — never cached here.
        _activeChatTaskState.value = null
        _inputText.value = drafts[taskId] ?: ""
        scope.launch {
            _isLoading.value = true
            try {
                // One-off read for the synthetic "brief" header (static fields
                // — summary, sourceLabel, createdAt). The LIVE state used by
                // the breadcrumb flows through the stream, not through this read.
                val task = repository.call { services ->
                    services.pendingTaskService.getById(taskId)
                }
                val history = repository.call { services ->
                    services.chatService.getTaskConversationHistory(taskId, limit = 200)
                }
                // Load related tasks via graph entity matching
                val relatedTasks = try {
                    repository.call { services ->
                        services.pendingTaskService.listRelatedTasks(taskId)
                    }
                } catch (_: Exception) { emptyList() }
                val mapped = mutableListOf<ChatMessage>()
                if (task != null) {
                    // Determine pipeline phase for placeholder logic
                    val awaitingKb = task.state in setOf("NEW", "INDEXING")
                    val awaitingQualification = task.needsQualification ||
                        (task.priorityScore == null && task.state in setOf("NEW", "INDEXING", "QUEUED"))

                    val brief = buildString {
                        // ── Title ─────────────────────────────────────────
                        val title = task.summary
                            ?: task.taskName.takeIf { it.isNotBlank() && it != "Unnamed Task" && it != task.sourceLabel }
                            ?: task.content.lineSequence()
                                .map { it.removePrefix("# ").removePrefix("## ").trim() }
                                .firstOrNull { it.isNotBlank() && it.length > 5 && !it.startsWith("**") && it != task.sourceLabel }
                            ?: task.sourceLabel.ifBlank { "Uloha" }
                        appendLine("# $title")
                        appendLine()

                        // ── Metadata ─────────────────────────────────────
                        if (task.sourceLabel.isNotBlank()) appendLine("**Zdroj:** ${task.sourceLabel}")
                        appendLine("**Stav:** ${stateLabelCs(task.state)} · pipeline: ${pipelineLabelCs(task.taskType)}")
                        appendLine("**Vytvoreno:** ${task.createdAt}")
                        val score = task.priorityScore
                        if (score != null) {
                            val priorityLabel = when {
                                score >= 80 -> "URGENT"
                                score >= 60 -> "HIGH"
                                score >= 40 -> "MEDIUM"
                                else -> "LOW"
                            }
                            append("**Priorita:** $score/100 ($priorityLabel)")
                            if (!task.priorityReason.isNullOrBlank()) append(" — ${task.priorityReason}")
                            appendLine()
                        } else if (awaitingQualification) {
                            appendLine("**Priorita:** _bude urcena kvalifikatorem_")
                        }
                        if (!task.actionType.isNullOrBlank() || !task.estimatedComplexity.isNullOrBlank()) {
                            val parts = listOfNotNull(
                                task.actionType?.takeIf { it.isNotBlank() }?.let { "akce: $it" },
                                task.estimatedComplexity?.takeIf { it.isNotBlank() }?.let { "komplexita: $it" },
                            )
                            if (parts.isNotEmpty()) appendLine("**Klasifikace:** ${parts.joinToString(" · ")}")
                        } else if (awaitingQualification) {
                            appendLine("**Klasifikace:** _bude urcena kvalifikatorem_")
                        }
                        val parentId = task.parentTaskId
                        if (parentId != null) appendLine("**Poduloha** v ramci $parentId")
                        if (task.childCount > 0) appendLine("**Podulohy:** ${task.completedChildCount}/${task.childCount} hotovo")
                        if (task.needsQualification) appendLine("**Re-kvalifikace** je naplanovana")

                        // ── Pending question (USER_TASK) ──────────────────
                        if (!task.pendingUserQuestion.isNullOrBlank()) {
                            appendLine()
                            appendLine("## Otazka pro tebe")
                            appendLine(task.pendingUserQuestion)
                            if (!task.userQuestionContext.isNullOrBlank()) {
                                appendLine()
                                appendLine("_Kontext:_ ${task.userQuestionContext}")
                            }
                        }

                        // ── Original content — actual source data ─────────
                        appendLine()
                        appendLine("---")
                        appendLine()
                        if (task.content.isNotBlank()) {
                            appendLine("## Puvodni obsah")
                            append(task.content)
                        }

                        // ── KB analysis ───────────────────────────────────
                        appendLine()
                        appendLine()
                        appendLine("---")
                        appendLine()
                        appendLine("## KB analyza")
                        if (!task.kbSummary.isNullOrBlank()) {
                            appendLine(task.kbSummary)
                            if (task.kbEntities.isNotEmpty()) {
                                appendLine()
                                appendLine("**Entity:** ${task.kbEntities.joinToString(", ")}")
                            }
                        } else if (awaitingKb) {
                            appendLine("_Probiha indexace v KB — shrnuti a entity budou doplneny._")
                        } else {
                            appendLine("_KB neziskala zadne shrnuti._")
                        }

                        // ── Qualifier analysis ───────────────────────────
                        appendLine()
                        appendLine("## Kvalifikator")
                        if (!task.qualifierContextSummary.isNullOrBlank() || !task.qualifierSuggestedApproach.isNullOrBlank()) {
                            if (!task.qualifierContextSummary.isNullOrBlank()) {
                                appendLine("**Kontext:** ${task.qualifierContextSummary}")
                            }
                            if (!task.qualifierSuggestedApproach.isNullOrBlank()) {
                                appendLine()
                                appendLine("**Navrzeny postup:** ${task.qualifierSuggestedApproach}")
                            }
                        } else if (!task.lastQualificationStep.isNullOrBlank()) {
                            appendLine("_Probiha kvalifikace..._")
                            appendLine("Posledni krok: ${task.lastQualificationStep}")
                        } else if (awaitingQualification) {
                            appendLine("_Ceka na kvalifikaci — kontext, priorita a postup budou doplneny._")
                        } else {
                            appendLine("_Kvalifikator nedodal analyzu._")
                        }

                        // ── Related tasks ─────────────────────────────────
                        if (relatedTasks.isNotEmpty()) {
                            appendLine()
                            appendLine()
                            appendLine("## Souvisejici ulohy")
                            for (rt in relatedTasks.take(10)) {
                                val rtState = stateLabelCs(rt.state)
                                val rtName = rt.summary?.take(80)
                                    ?: rt.taskName.takeIf { it.isNotBlank() && it != "Unnamed Task" }
                                    ?: rt.sourceLabel.ifBlank { "Uloha" }
                                appendLine("- [$rtState] $rtName (${rt.sourceLabel})")
                            }
                        }
                    }
                    mapped.add(
                        ChatMessage(
                            from = ChatMessage.Sender.Assistant,
                            text = brief,
                            contextId = null,
                            timestamp = task.createdAt,
                            messageType = ChatMessage.MessageType.FINAL,
                            id = "task-brief-${task.id}",
                        ),
                    )
                }
                history.messages.forEach { dto ->
                    mapped.add(
                        ChatMessage(
                            from = when (dto.role) {
                                ChatRole.USER -> ChatMessage.Sender.Me
                                else -> ChatMessage.Sender.Assistant
                            },
                            text = dto.content,
                            contextId = null,
                            timestamp = dto.timestamp,
                            messageType = ChatMessage.MessageType.FINAL,
                            sequence = dto.sequence,
                            id = dto.messageId,
                        ),
                    )
                }
                _chatMessages.value = mapped
                _hasMore.value = false
            } catch (e: Exception) {
                println("ChatViewModel: switchToTaskConversation failed: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun stateLabelCs(state: String): String = when (state) {
        "NEW" -> "Nový"
        "INDEXING" -> "Indexace"
        "QUEUED" -> "Ve frontě"
        "PROCESSING" -> "Zpracovává se"
        "CODING" -> "Kódování"
        "USER_TASK" -> "K vyřízení"
        "BLOCKED" -> "Čeká na podúlohy"
        "DONE" -> "Dokončeno"
        "ERROR" -> "Chyba"
        else -> state
    }

    private fun pipelineLabelCs(type: String): String = when (type) {
        "INSTANT" -> "Chat"
        "SCHEDULED" -> "Plán"
        "SYSTEM" -> "Systém"
        else -> type
    }

    /**
     * Phase 5 chat-as-primary: return to the main (global) chat view.
     * Reloads global history via the existing getChatHistory path.
     */
    fun switchToMainChat() {
        if (_activeChatTaskId.value == null) return
        // Save current task draft before switching (local + server)
        val currentKey = _activeChatTaskId.value
        val currentText = _inputText.value
        if (currentText.isNotBlank()) {
            drafts[currentKey] = currentText
        } else {
            drafts.remove(currentKey)
        }
        persistDraftToServer(currentKey, currentText)
        _activeChatTaskId.value = null
        _activeChatTaskName.value = null
        _activeChatTaskState.value = null
        _inputText.value = drafts[null] ?: ""
        // Trigger the existing history reload by bumping connection generation.
        // The generation watcher (line ~320) will pick it up and reload via
        // chatService.getChatHistory with current filter scope.
        scope.launch {
            _isLoading.value = true
            try {
                val history = repository.call { services ->
                    services.chatService.getChatHistory(
                        limit = 50,
                        showChat = _showChat.value,
                        showTasks = _showTasks.value,
                        showNeedReaction = _showNeedReaction.value,
                        filterClientId = currentFilterClientId,
                        filterProjectId = currentFilterProjectId,
                        filterGroupId = currentFilterGroupId,
                    )
                }
                applyHistory(history)
            } catch (e: Exception) {
                println("ChatViewModel: switchToMainChat failed: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
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
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        // Optimistically update chatMessages (BACKGROUND_RESULT or URGENT_ALERT)
        _chatMessages.value = _chatMessages.value.map { msg ->
            if ((msg.messageType == ChatMessage.MessageType.BACKGROUND_RESULT ||
                    msg.messageType == ChatMessage.MessageType.URGENT_ALERT) &&
                (msg.metadata["taskId"] == taskId || msg.metadata["sourceUrn"] == taskId)
            ) {
                msg.copy(userResponse = trimmed)
            } else {
                msg
            }
        }
        _userTaskCount.update { (it - 1).coerceAtLeast(0) }

        // Send to server asynchronously (doesn't block chat)
        scope.launch {
            try {
                repository.call { services -> services.userTaskService.respondToTask(taskId, trimmed) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                println("ChatViewModel: respondToTask failed (task may no longer exist): ${e.message}")
            }
        }
    }

    /**
     * Dismiss (ignore) a user task — moves to DONE without processing.
     * Optimistically removes the alert from chat and decrements badge.
     */
    fun dismissTask(taskId: String) {
        _userTaskCount.update { (it - 1).coerceAtLeast(0) }
        scope.launch {
            try {
                repository.call { services -> services.userTaskService.dismiss(taskId) }
                // Reload chat bubbles — dismissed item filtered out by metadata.dismissed.
                // Sidebar snapshot arrives via the subscribeSidebar stream automatically.
                reloadForCurrentFilter()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                println("ChatViewModel: dismissTask failed: ${e.message}")
            }
        }
    }

    /**
     * Dismiss ALL pending user tasks — bulk move to DONE.
     * Clears all URGENT_ALERT and actionable BACKGROUND_RESULT from chat.
     */
    fun dismissAllTasks() {
        scope.launch {
            try {
                val count = repository.chat.dismissAllActionable()
                _userTaskCount.value = 0
                // Switch back to Chat view after dismissing all actionable items
                _showChat.value = true
                _showNeedReaction.value = false
                // Reload chat — dismissed items filtered at DB.
                // Sidebar snapshot arrives via the subscribeSidebar stream automatically.
                reloadForCurrentFilter()
                println("ChatViewModel: dismissAll — $count items dismissed")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                println("ChatViewModel: dismissAll failed: ${e.message}")
            }
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
                // For master graph, pass clientId for per-client filtering
                val clientId = if (taskId == "master") selectedClientId.value else null
                val graph = repository.taskGraphs.getGraph(taskId, clientId)
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

    // Memory Graph removed — no replacement on the client side. Claude
    // CLI owns session narrative via compact_store (server-only).

    /** Toggle chat messages visibility. */
    /** Toggle "Chat" filter — independent, triggers server reload. */
    fun toggleChat() {
        _showChat.value = !_showChat.value
        reloadForCurrentFilter()
    }

    /** Toggle "Tasky" filter — independent, triggers server reload. */
    fun toggleTasks() {
        _showTasks.value = !_showTasks.value
        reloadForCurrentFilter()
    }

    /** Toggle "K reakci" filter — triggers server reload. DB decides what to return (incl. USER_TASKs). */
    fun toggleNeedReaction() {
        _showNeedReaction.value = !_showNeedReaction.value
        reloadForCurrentFilter()
    }

    /** Reload messages from server based on current filter combination. All filtering in DB. */
    private fun reloadForCurrentFilter() {
        scope.launch {
            _isLoadingMore.value = true
            try {
                // Phase 5 chat-as-primary: when the chat plane is showing a
                // task's conversation, reload that task's history instead of
                // the global chat history. Otherwise SSE events from other
                // tasks (BACKGROUND_RESULT, URGENT_ALERT) would silently
                // reset the user's view back to the main chat — destroying
                // their drill-in.
                val activeTaskId = _activeChatTaskId.value
                if (activeTaskId != null) {
                    val history = repository.chat.getTaskConversationHistory(activeTaskId, limit = 200)
                    val mapped = history.messages.map { dto ->
                        ChatMessage(
                            from = when (dto.role) {
                                ChatRole.USER -> ChatMessage.Sender.Me
                                else -> ChatMessage.Sender.Assistant
                            },
                            text = dto.content,
                            contextId = null,
                            timestamp = dto.timestamp,
                            messageType = ChatMessage.MessageType.FINAL,
                            sequence = dto.sequence,
                            id = dto.messageId,
                        )
                    }
                    _chatMessages.value = mapped
                    onStatusDetail("ok")
                    return@launch
                }
                val history = repository.chat.getChatHistory(
                    limit = 50,
                    showChat = _showChat.value,
                    showTasks = _showTasks.value,
                    showNeedReaction = _showNeedReaction.value,
                    filterClientId = currentFilterClientId,
                    filterProjectId = currentFilterProjectId,
                    filterGroupId = currentFilterGroupId,
                )
                applyHistory(history)
                onStatusDetail("ok")
                println("ChatViewModel: reload chat=${_showChat.value} tasks=${_showTasks.value} reaction=${_showNeedReaction.value} — ${history.messages.size} msgs")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onStatusDetail("reload err")
                println("ChatViewModel: reloadForCurrentFilter failed: ${e.message}")
                // Trigger reconnect on connection errors
                connectionManager.triggerReconnect("reload failed: ${e.message?.take(30)}")
            } finally {
                _isLoadingMore.value = false
            }
        }
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
                // Phase 5 chat-as-primary: when the chat plane is showing a
                // task's conversation (sidebar drill-in) AND the user did not
                // explicitly set a contextTaskId via replyToTask, route the
                // outgoing message into the active task's thread. The server
                // already supports contextTaskId — this just plumbs the active
                // task id through.
                val taskContext = _contextTaskId ?: _activeChatTaskId.value
                val wasInActiveTaskScope = _activeChatTaskId.value != null
                _contextTaskId = null  // Clear after use
                // Resilient call: waits up to 10s for Connected state, wraps send in
                // withTimeout, automatically triggers reconnect on connection-lost errors
                // and throws OfflineException (caught below → pending queue + retry).
                repository.call { services ->
                    services.chatService.sendMessage(
                        text = originalText,
                        clientMessageId = clientMessageId,
                        activeClientId = clientId,
                        activeProjectId = projectId,
                        activeGroupId = groupId,
                        contextTaskId = taskContext,
                        attachments = attachmentDtos,
                        tierOverride = _tierOverride.value,
                        clientTimezone = kotlinx.datetime.TimeZone.currentSystemDefault().id,
                    )
                }
                println("=== Message sent successfully (RPC) ===")

                // UX: sending a message inside a task detail view forwards the task
                // to the qualifier (server-side re-qualification), which moves it
                // out of the user's immediate scope. Return to the task-list view
                // (sidebar) so the next actionable item is visible on both mobile
                // and desktop — consistent with "Hotovo" behavior.
                if (wasInActiveTaskScope) {
                    switchToMainChat()
                }

                val progressMsg = ChatMessage(
                    from = ChatMessage.Sender.Assistant,
                    text = "Zpracovávám...",
                    contextId = projectId,
                    messageType = ChatMessage.MessageType.PROGRESS,
                )
                _chatMessages.value = _chatMessages.value + progressMsg

                // Safety timeout: if orchestrator doesn't respond within 120s,
                // replace PROGRESS with error so UI doesn't hang forever
                progressTimeoutJob?.cancel()
                progressTimeoutJob = scope.launch {
                    kotlinx.coroutines.delay(120_000L)
                    val msgs = _chatMessages.value.toMutableList()
                    val hasProgress = msgs.any { it.messageType == ChatMessage.MessageType.PROGRESS }
                    if (hasProgress) {
                        msgs.removeAll { it.messageType == ChatMessage.MessageType.PROGRESS }
                        msgs.add(
                            ChatMessage(
                                from = ChatMessage.Sender.Assistant,
                                text = "Orchestrátor neodpověděl (timeout). Zkus poslat zprávu znovu.",
                                contextId = projectId,
                                messageType = ChatMessage.MessageType.ERROR,
                            ),
                        )
                        _chatMessages.value = msgs
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                println("Error sending message: ${e.message}")
                e.printStackTrace()
                onStatusDetail("send err")

                // Trigger reconnect on connection errors (not just "cancelled")
                if (e.message?.contains("cancelled", ignoreCase = true) == true ||
                    e.message?.contains("closed", ignoreCase = true) == true ||
                    e.message?.contains("timeout", ignoreCase = true) == true
                ) {
                    connectionManager.triggerReconnect("send failed: ${e.message?.take(30)}")
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
                repository.call { services ->
                    services.chatService.sendMessage(
                        text = state.text,
                        clientMessageId = state.clientMessageId,
                        activeClientId = clientId,
                        activeProjectId = projectId,
                        activeGroupId = groupId,
                    )
                }
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
                    limit = 20,
                    beforeMessageId = beforeId,
                    showChat = _showChat.value,
                    showTasks = _showTasks.value,
                    showNeedReaction = _showNeedReaction.value,
                    filterClientId = currentFilterClientId,
                    filterProjectId = currentFilterProjectId,
                    filterGroupId = currentFilterGroupId,
                )
                val olderMessages = history.messages.map { msg ->
                    val sender = if (msg.role == ChatRole.USER) {
                        ChatMessage.Sender.Me
                    } else {
                        ChatMessage.Sender.Assistant
                    }
                    val msgType = when (msg.role) {
                        ChatRole.USER -> ChatMessage.MessageType.USER_MESSAGE
                        ChatRole.BACKGROUND -> {
                            if (msg.metadata["sender"] == "thinking_graph") {
                                ChatMessage.MessageType.THINKING_GRAPH_UPDATE
                            } else {
                                ChatMessage.MessageType.BACKGROUND_RESULT
                            }
                        }
                        ChatRole.ALERT -> ChatMessage.MessageType.URGENT_ALERT
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

    private suspend fun handleChatResponse(response: com.jervis.dto.chat.ChatResponseDto) {
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
                    val history = repository.chat.getChatHistory(
                        limit = 50,
                        showChat = _showChat.value,
                        showTasks = _showTasks.value,
                        showNeedReaction = _showNeedReaction.value,
                        filterClientId = currentFilterClientId,
                        filterProjectId = currentFilterProjectId,
                        filterGroupId = currentFilterGroupId,
                    )
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
            ChatResponseType.THINKING_GRAPH_UPDATE -> ChatMessage.MessageType.THINKING_GRAPH_UPDATE

            ChatResponseType.THOUGHT_CONTEXT -> {
                // Update Thought Map context state for side panel display
                val thoughtIds = response.metadata["activated_thought_ids"]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                _activeThoughtContext.value = ActiveThoughtContext(
                    formattedContext = response.message,
                    activatedThoughtIds = thoughtIds,
                    thoughtCount = thoughtIds.size,
                )
                null  // Don't add as chat message — shown in side panel
            }

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
                // Reset timeout — orchestrator is still alive
                progressTimeoutJob?.cancel()
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
                progressTimeoutJob?.cancel()
                messages.removeAll {
                    it.messageType == ChatMessage.MessageType.PROGRESS ||
                        it.metadata["streaming"] == "true"
                }
                streamingBuffer.clear()
                thinkingHistory.clear()

                // Enrich last user message with memory graph info (user bubble shows memory graph)
                val memoryGraphId = response.metadata["memory_graph_id"]
                val memoryGraphVertexId = response.metadata["memory_graph_vertex_id"]
                if (!memoryGraphId.isNullOrBlank()) {
                    val lastUserIdx = messages.indexOfLast { it.from == ChatMessage.Sender.Me }
                    if (lastUserIdx >= 0) {
                        val userMsg = messages[lastUserIdx]
                        messages[lastUserIdx] = userMsg.copy(
                            metadata = userMsg.metadata + buildMap {
                                put("memory_graph_id", memoryGraphId)
                                if (!memoryGraphVertexId.isNullOrBlank()) put("memory_graph_vertex_id", memoryGraphVertexId)
                            },
                        )
                    }
                    // Proactively load memory graph for user bubble
                    if (memoryGraphId !in _taskGraphs.value) {
                        loadTaskGraph(memoryGraphId)
                    }
                }

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
                // Refresh master graph after chat completion
            }

            ChatMessage.MessageType.ERROR -> {
                progressTimeoutJob?.cancel()
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
                // SSE only informs — trigger DB reload. Counters come from server (applyHistory).
                // No client-side counter increment — prevents phantom badge.

                // Clean up stale progress indicators
                messages.removeAll { it.messageType == ChatMessage.MessageType.PROGRESS }
                thinkingHistory.clear()
                _chatMessages.value = messages

                // Reload from DB — server applies correct filter (showChat/showTasks/showNeedReaction).
                // Return early so the final _chatMessages.value = messages at the end doesn't overwrite reload results.
                reloadForCurrentFilter()
                return
            }

            ChatMessage.MessageType.THINKING_GRAPH_UPDATE -> {
                val isBackgroundPush = response.metadata["sender"] == "thinking_graph"
                val taskId = response.metadata["taskId"]
                val graphId = response.metadata["graph_id"]
                val status = response.metadata["status"]

                if (isBackgroundPush && taskId != null && (_showTasks.value || _showNeedReaction.value)) {
                    // Background thinking graph push — show/update chat bubble (only when tasks/reaction filter active)
                    val existingIdx = messages.indexOfLast {
                        it.messageType == ChatMessage.MessageType.THINKING_GRAPH_UPDATE
                                && it.metadata["taskId"] == taskId
                    }
                    val bubble = ChatMessage(
                        from = ChatMessage.Sender.Assistant,
                        text = response.message,
                        contextId = projectId,
                        messageType = messageType,
                        metadata = response.metadata,
                        timestamp = response.metadata["timestamp"],
                    )
                    if (existingIdx >= 0) {
                        messages[existingIdx] = bubble  // Update in-place
                    } else {
                        messages.add(bubble)
                    }
                }

                // Update sub-graph data if panel is already open (never auto-open — user controls panel visibility)
                if (!graphId.isNullOrBlank() && _thinkingGraphPanelVisible.value) {
                    openSubGraph(graphId)
                }
                // Reload task graph for inline display on completion
                if (status in listOf("completed", "failed") && taskId != null) {
                    loadTaskGraph(taskId)
                }
            }

            ChatMessage.MessageType.THOUGHT_CONTEXT -> {
                // Handled above (set _activeThoughtContext, not added to chat)
            }
        }
        _chatMessages.value = messages
    }

    private fun handleStreamingToken(response: com.jervis.dto.chat.ChatResponseDto, projectId: String) {
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
    private fun applyHistory(history: com.jervis.dto.chat.ChatHistoryDto) {
        val projectId = selectedProjectId.value ?: ""
        _backgroundMessageCount.value = history.backgroundMessageCount
        _userTaskCount.value = history.userTaskCount
        val newMessages = history.messages.map { msg ->
            val sender = if (msg.role == ChatRole.USER) {
                ChatMessage.Sender.Me
            } else {
                ChatMessage.Sender.Assistant
            }
            val msgType = when (msg.role) {
                ChatRole.USER -> ChatMessage.MessageType.USER_MESSAGE
                ChatRole.BACKGROUND -> {
                    // Distinguish thinking graph updates from regular background results
                    if (msg.metadata["sender"] == "thinking_graph") {
                        ChatMessage.MessageType.THINKING_GRAPH_UPDATE
                    } else {
                        ChatMessage.MessageType.BACKGROUND_RESULT
                    }
                }
                ChatRole.ALERT -> ChatMessage.MessageType.URGENT_ALERT
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
                isOutOfScope = msg.isOutOfScope,
                isDecomposed = msg.isDecomposed,
                parentRequestId = msg.parentRequestId,
            )
        }
        // Merge: preserve in-flight messages (not yet in DB) so they don't vanish on reconnect.
        // Exclude synthetic task-brief messages — they are local-only, rendered for a task
        // detail view, and must NOT leak into main chat history when switching back.
        val inFlight = _chatMessages.value.filter { msg ->
            msg.sequence == null &&
                msg.metadata["streaming"] != "true" &&
                msg.id?.startsWith("task-brief-") != true &&
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

    // ── Voice Session (WebSocket) ───────────────────────────────────────

    private val voiceSessionManager = com.jervis.ui.voice.VoiceSessionManager(scope)

    init {
        // Observe voice session state → update ChatViewModel state
        scope.launch {
            voiceSessionManager.state.collect { state ->
                _isRecordingVoice.value = state != com.jervis.ui.voice.VoiceSessionState.IDLE
                        && state != com.jervis.ui.voice.VoiceSessionState.ERROR
            }
        }
        scope.launch {
            voiceSessionManager.statusText.collect { _voiceStatus.value = it }
        }
        scope.launch {
            voiceSessionManager.audioLevel.collect { _voiceAudioLevel.value = it }
        }
        scope.launch {
            voiceSessionManager.transcript.collect { transcript ->
                if (transcript.isNotBlank()) {
                    _chatMessages.update { msgs ->
                        msgs.mapIndexed { i, m ->
                            if (i == msgs.lastIndex && m.source != null) m.copy(text = transcript) else m
                        }
                    }
                }
            }
        }
        scope.launch {
            voiceSessionManager.responseText.collect { response ->
                if (response.isNotBlank()) {
                    // Update existing voice response message in-place, or add new one
                    _chatMessages.update { msgs ->
                        val lastAssistantIdx = msgs.indexOfLast {
                            it.from == ChatMessage.Sender.Assistant
                        }
                        if (lastAssistantIdx >= 0 && lastAssistantIdx == msgs.lastIndex) {
                            // Update last assistant message (same utterance)
                            msgs.toMutableList().apply {
                                set(lastAssistantIdx, get(lastAssistantIdx).copy(text = response))
                            }
                        } else {
                            // Add new assistant response message
                            msgs + ChatMessage(
                                from = ChatMessage.Sender.Assistant,
                                text = response,
                                messageType = ChatMessage.MessageType.FINAL,
                            )
                        }
                    }
                }
            }
        }
    }

    fun toggleVoiceRecording() {
        if (_isRecordingVoice.value) {
            voiceSessionManager.stop()
        } else {
            startVoiceSession()
        }
    }

    fun cancelVoiceRecording() {
        voiceSessionManager.cancel()
    }

    private fun startVoiceSession() {
        // Add voice message placeholder
        _chatMessages.value = _chatMessages.value + ChatMessage(
            from = ChatMessage.Sender.Me,
            text = "Hlasová zpráva",
            messageType = ChatMessage.MessageType.USER_MESSAGE,
            source = currentVoiceSource,
        )

        // Build WebSocket URL from HTTP base URL
        val httpUrl = connectionManager.baseUrl.trimEnd('/')
        val wsUrl = httpUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://") + "/api/v1/voice/ws"

        voiceSessionManager.start(
            wsUrl = wsUrl,
            source = currentVoiceSource?.name ?: "app",
            clientId = selectedClientId.value ?: "",
            projectId = selectedProjectId.value ?: "",
        )
    }

    // Old HTTP-based voice methods removed — replaced by WebSocket VoiceSessionManager above.

    // ── TTS Playback for chat bubbles ────────────────────────────────────

    private val _isTtsPlaying = MutableStateFlow(false)
    val isTtsPlaying: StateFlow<Boolean> = _isTtsPlaying.asStateFlow()
    private var ttsJob: Job? = null
    private val ttsPlayer = AudioPlayer()

    @OptIn(ExperimentalEncodingApi::class)
    fun playTts(text: String) {
        println("TTS: playTts called, textLen=${text.length}, isTtsPlaying=${_isTtsPlaying.value}")
        if (_isTtsPlaying.value) {
            ttsJob?.cancel()
            ttsPlayer.stop()
            _isTtsPlaying.value = false
            return
        }

        _isTtsPlaying.value = true
        ttsJob = scope.launch {
            try {
                println("TTS: streamTts via kRPC text=${text.take(50)}")
                // One-shot stream — do NOT use resilientFlow (which auto-resubscribes
                // when the inner flow completes). TTS terminates with a single DONE
                // event, which resilientFlow would treat as "stream dropped" and
                // kick off an infinite re-subscribe loop — the exact pathology
                // seen in the UI log (HEADER → DONE → re-subscribe, repeated).
                val services = connectionManager.awaitConnected()
                // No hard timeout — TTS is a push stream with absolute
                // priority on the router; if it's taking a while it's
                // because the router is preempting background work for
                // us, not because something is broken. The user can
                // cancel manually by tapping the speaker button again.
                services.chatService.streamTts(
                    text = text,
                    activeClientId = currentFilterClientId,
                    activeProjectId = currentFilterProjectId,
                ).collect { event ->
                    when (event.type) {
                        com.jervis.dto.chat.TtsChunkEventType.HEADER -> {
                            val sampleRate = if (event.sampleRate > 0) event.sampleRate else 24000
                            println("TTS: opening audio stream, sampleRate=$sampleRate")
                            withContext(Dispatchers.Default) {
                                ttsPlayer.startStream(sampleRate)
                            }
                        }
                        com.jervis.dto.chat.TtsChunkEventType.PCM -> {
                            val audioB64 = event.audioData
                            if (audioB64.isNotBlank()) {
                                val pcmBytes = Base64.decode(audioB64)
                                withContext(Dispatchers.Default) {
                                    ttsPlayer.streamPcm(pcmBytes)
                                }
                            }
                        }
                        com.jervis.dto.chat.TtsChunkEventType.DONE -> {
                            println("TTS: stream done, draining audio")
                            withContext(Dispatchers.Default) {
                                ttsPlayer.finishStream()
                            }
                            return@collect
                        }
                        com.jervis.dto.chat.TtsChunkEventType.ERROR -> {
                            println("TTS stream error: ${event.errorMessage}")
                            withContext(Dispatchers.Default) {
                                ttsPlayer.stopStream()
                            }
                            return@collect
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("TTS play error: ${e::class.simpleName}: ${e.message}")
            } finally {
                _isTtsPlaying.value = false
                ttsPlayer.stopStream()
                println("TTS: finished, isTtsPlaying=false")
            }
        }
    }
}
