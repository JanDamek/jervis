package com.jervis.ui.queue

import com.jervis.di.RpcConnectionManager
import com.jervis.dto.ChatResponseType
import com.jervis.dto.events.JervisEvent
import com.jervis.repository.JervisRepository
import com.jervis.ui.model.AgentActivityEntry
import com.jervis.ui.model.AgentActivityLog
import com.jervis.ui.model.NodeEntry
import com.jervis.ui.model.NodeStatus
import com.jervis.ui.model.PendingQueueItem
import com.jervis.ui.model.TaskHistoryEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * ViewModel for orchestrator queue, task history, orchestrator progress, qualification progress.
 *
 * Manages all queue-related state and subscriptions.
 */
class QueueViewModel(
    private val repository: JervisRepository,
    private val connectionManager: RpcConnectionManager,
    private val selectedClientId: StateFlow<String?>,
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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

    private val _pendingQueueItems = MutableStateFlow<List<PendingQueueItem>>(emptyList())
    val pendingQueueItems: StateFlow<List<PendingQueueItem>> = _pendingQueueItems.asStateFlow()

    private val _foregroundQueue = MutableStateFlow<List<PendingQueueItem>>(emptyList())
    val foregroundQueue: StateFlow<List<PendingQueueItem>> = _foregroundQueue.asStateFlow()

    private val _backgroundQueue = MutableStateFlow<List<PendingQueueItem>>(emptyList())
    val backgroundQueue: StateFlow<List<PendingQueueItem>> = _backgroundQueue.asStateFlow()

    private val _backgroundTotalCount = MutableStateFlow(0L)
    val backgroundTotalCount: StateFlow<Long> = _backgroundTotalCount.asStateFlow()

    private val _isLoadingMoreBackground = MutableStateFlow(false)
    val isLoadingMoreBackground: StateFlow<Boolean> = _isLoadingMoreBackground.asStateFlow()

    val activityLog = AgentActivityLog()
    private val _activityEntries = MutableStateFlow<List<AgentActivityEntry>>(emptyList())
    val activityEntries: StateFlow<List<AgentActivityEntry>> = _activityEntries.asStateFlow()

    private var previousRunningProjectId: String? = null

    private val _orchestratorProgress = MutableStateFlow<OrchestratorProgressInfo?>(null)
    val orchestratorProgress: StateFlow<OrchestratorProgressInfo?> = _orchestratorProgress.asStateFlow()

    private val _qualificationProgress = MutableStateFlow<Map<String, QualificationProgressInfo>>(emptyMap())
    val qualificationProgress: StateFlow<Map<String, QualificationProgressInfo>> = _qualificationProgress.asStateFlow()

    private val _orchestratorHealthy = MutableStateFlow(true)
    val orchestratorHealthy: StateFlow<Boolean> = _orchestratorHealthy.asStateFlow()

    private val _taskHistory = MutableStateFlow<List<TaskHistoryEntry>>(emptyList())
    val taskHistory: StateFlow<List<TaskHistoryEntry>> = _taskHistory.asStateFlow()
    private var taskHistoryTotalCount: Long = 0
    private var taskHistoryLoading = false
    private val _taskHistoryHasMore = MutableStateFlow(false)
    val taskHistoryHasMore: StateFlow<Boolean> = _taskHistoryHasMore.asStateFlow()

    private val _runningTaskNodes = MutableStateFlow<List<NodeEntry>>(emptyList())
    val runningTaskNodes: StateFlow<List<NodeEntry>> = _runningTaskNodes.asStateFlow()
    private var runningTaskStartTime: String? = null

    private var queueStatusJob: Job? = null

    /** Callback to update chat progress message (set by MainViewModel). */
    var onChatProgressUpdate: ((text: String, isError: Boolean) -> Unit)? = null

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

    // --- Queue Status Subscription ---

    fun subscribeToQueueStatus(clientId: String) {
        println("QueueViewModel: subscribeToQueueStatus(client=$clientId)")
        queueStatusJob?.cancel()
        queueStatusJob = scope.launch {
            connectionManager.resilientFlow { services ->
                services.agentOrchestratorService.subscribeToQueueStatus(clientId)
            }.collect { response ->
                if (response.type == ChatResponseType.QUEUE_STATUS) {
                    handleQueueStatusUpdate(response.metadata, clientId)
                }
            }
        }
    }

    private fun handleQueueStatusUpdate(metadata: Map<String, String>, clientId: String) {
        val newRunningId = metadata["runningProjectId"]
        val newProjectName = metadata["runningProjectName"]
        val newTaskPreview = metadata["runningTaskPreview"]
        val newTaskType = metadata["runningTaskType"]
        val newQueueSize = metadata["queueSize"]?.toIntOrNull() ?: 0

        // Detect state transitions and log activity
        val wasRunning = previousRunningProjectId != null && previousRunningProjectId != "none"
        val isRunning = newRunningId != null && newRunningId != "none"
        val displayTaskType = newTaskType?.takeIf { it != "Asistent" }
        val prevDisplayTaskType = _runningTaskType.value?.takeIf { it != "Asistent" }

        if (!wasRunning && isRunning) {
            activityLog.add(
                type = AgentActivityEntry.Type.TASK_STARTED,
                description = newTaskPreview ?: "Zpracování úlohy",
                projectName = newProjectName,
                taskType = displayTaskType,
                clientId = clientId,
            )
            _activityEntries.value = activityLog.entries
        } else if (wasRunning && !isRunning) {
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

        // Parse FOREGROUND pending queue items
        val pendingCount = metadata["pendingItemCount"]?.toIntOrNull() ?: 0
        val foregroundItems = (0 until pendingCount).mapNotNull { i ->
            val preview = metadata["pendingItem_${i}_preview"]
            val taskId = metadata["pendingItem_${i}_taskId"] ?: ""
            if (preview != null) {
                PendingQueueItem(
                    taskId = taskId,
                    preview = preview,
                    projectName = metadata["pendingItem_${i}_project"] ?: "General",
                    processingMode = "FOREGROUND",
                )
            } else null
        }
        _foregroundQueue.value = foregroundItems
        _pendingQueueItems.value = foregroundItems

        // Parse BACKGROUND pending queue items
        val backgroundCount = metadata["backgroundItemCount"]?.toIntOrNull() ?: 0
        val backgroundItems = (0 until backgroundCount).mapNotNull { i ->
            val preview = metadata["backgroundItem_${i}_preview"]
            val taskId = metadata["backgroundItem_${i}_taskId"] ?: ""
            if (preview != null) {
                PendingQueueItem(
                    taskId = taskId,
                    preview = preview,
                    projectName = metadata["backgroundItem_${i}_project"] ?: "General",
                    processingMode = "BACKGROUND",
                )
            } else null
        }
        _backgroundQueue.value = backgroundItems
        _backgroundTotalCount.value = backgroundItems.size.toLong()

        _orchestratorHealthy.value = metadata["orchestratorHealthy"]?.toBooleanStrictOrNull() ?: true
    }

    // --- Event Handlers (called from MainViewModel.handleGlobalEvent) ---

    fun handleOrchestratorProgress(event: JervisEvent.OrchestratorTaskProgress) {
        val nowMs = Clock.System.now().toEpochMilliseconds()
        val existing = _orchestratorProgress.value
        val existingSteps = if (existing?.taskId == event.taskId) existing.steps else emptyList()
        val startedAt = if (existing?.taskId == event.taskId && existing.startedAtMs > 0) existing.startedAtMs else nowMs

        val newSteps = if (existingSteps.isEmpty() || existingSteps.last().node != event.node) {
            val label = nodeLabels[event.node] ?: event.node
            existingSteps + OrchestratorProgressStep(
                timestampMs = nowMs,
                node = event.node,
                label = label,
                message = event.message,
            )
        } else {
            existingSteps.toMutableList().also {
                it[it.lastIndex] = it.last().copy(message = event.message)
            }
        }

        _orchestratorProgress.value = OrchestratorProgressInfo(
            taskId = event.taskId,
            node = event.node,
            message = event.message,
            percent = event.percent,
            goalIndex = event.goalIndex,
            totalGoals = event.totalGoals,
            stepIndex = event.stepIndex,
            totalSteps = event.totalSteps,
            startedAtMs = startedAt,
            steps = newSteps,
        )
        updateRunningTaskNodes(event.taskId, event.node)
        onChatProgressUpdate?.invoke(event.message, false)
    }

    fun handleOrchestratorStatusChange(event: JervisEvent.OrchestratorTaskStatusChange) {
        when (event.status) {
            "done" -> {
                _orchestratorProgress.value = null
                finalizeTaskHistory(event.taskId, event.status)
            }
            "error" -> {
                _orchestratorProgress.value = null
                finalizeTaskHistory(event.taskId, event.status)
                onChatProgressUpdate?.invoke("Chyba při zpracování úlohy", true)
            }
            "interrupted" -> {
                _orchestratorProgress.value = _orchestratorProgress.value?.copy(
                    message = "Čekám na schválení...",
                )
                finalizeTaskHistory(event.taskId, "interrupted")
                onChatProgressUpdate?.invoke("Čekám na vaše schválení...", false)
            }
        }
    }

    fun handleQualificationProgress(event: JervisEvent.QualificationProgress) {
        val existing = _qualificationProgress.value[event.taskId]
        val newStep = QualificationProgressStep(
            timestamp = event.metadata["epochMs"]?.toLongOrNull()
                ?: Clock.System.now().toEpochMilliseconds(),
            message = event.message,
            step = event.step,
            metadata = event.metadata,
        )
        _qualificationProgress.value = _qualificationProgress.value + (
            event.taskId to QualificationProgressInfo(
                taskId = event.taskId,
                message = event.message,
                step = event.step,
                steps = (existing?.steps ?: emptyList()) + newStep,
            )
        )

        if (event.step == "done" || event.step == "old_indexed" || event.step == "simple_action_handled") {
            val taskIdToRemove = event.taskId
            scope.launch {
                delay(5_000)
                _qualificationProgress.value = _qualificationProgress.value - taskIdToRemove
            }
        }
    }

    // --- Queue Management ---

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

    fun refreshQueues() {
        val clientId = selectedClientId.value ?: return
        scope.launch {
            try {
                val pending = repository.agentOrchestrator.getPendingTasks(clientId)
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
                _backgroundTotalCount.value = pending.backgroundTotalCount
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is IllegalStateException && e.message?.contains("cancelled") == true) return@launch
                println("Error refreshing queues: ${e.message}")
            }
        }
    }

    fun loadMoreBackgroundTasks() {
        if (_isLoadingMoreBackground.value) return
        val currentItems = _backgroundQueue.value
        if (currentItems.size.toLong() >= _backgroundTotalCount.value) return
        _isLoadingMoreBackground.value = true
        scope.launch {
            try {
                val page = repository.agentOrchestrator.getBackgroundTasksPage(
                    limit = 20,
                    offset = currentItems.size,
                )
                _backgroundQueue.value = currentItems + page.items.map { dto ->
                    PendingQueueItem(
                        taskId = dto.taskId,
                        preview = dto.preview,
                        projectName = dto.projectName,
                        taskType = dto.taskType,
                        processingMode = dto.processingMode,
                        queuePosition = dto.queuePosition,
                    )
                }
                _backgroundTotalCount.value = page.totalCount
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                println("Error loading more background tasks: ${e.message}")
            } finally {
                _isLoadingMoreBackground.value = false
            }
        }
    }

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

    fun moveTaskDown(taskId: String) {
        val allItems = _foregroundQueue.value + _backgroundQueue.value
        val item = allItems.find { it.taskId == taskId } ?: return
        val currentPos = item.queuePosition ?: return
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

    // --- Task History ---

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

    // --- Internal Helpers ---

    private fun updateRunningTaskNodes(taskId: String, node: String) {
        val label = nodeLabels[node] ?: node
        val current = _runningTaskNodes.value.toMutableList()
        for (i in current.indices) {
            if (current[i].status == NodeStatus.RUNNING) {
                current[i] = current[i].copy(status = NodeStatus.DONE)
            }
        }
        val existingIdx = current.indexOfFirst { it.node == node }
        if (existingIdx < 0) {
            current.add(NodeEntry(node = node, label = label, status = NodeStatus.RUNNING))
        } else {
            current[existingIdx] = current[existingIdx].copy(status = NodeStatus.RUNNING)
        }
        _runningTaskNodes.value = current
        if (runningTaskStartTime == null) {
            runningTaskStartTime = AgentActivityEntry.formatNow()
        }
    }

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
        history.add(0, entry)
        _taskHistory.value = history
        _runningTaskNodes.value = emptyList()
        runningTaskStartTime = null
        scope.launch {
            delay(1000)
            loadTaskHistory()
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
            nodes = nodes.map { NodeEntry(node = it.node, label = it.label, status = NodeStatus.DONE, durationMs = it.durationMs) },
        )
    }

    private fun formatIsoTime(isoTimestamp: String?): String {
        if (isoTimestamp == null) return ""
        val tIndex = isoTimestamp.indexOf('T')
        if (tIndex < 0) return isoTimestamp
        val timePart = isoTimestamp.substring(tIndex + 1).substringBefore('Z').substringBefore('+')
        return timePart.substringBefore('.').take(8)
    }
}

data class OrchestratorProgressStep(
    val timestampMs: Long,
    val node: String,
    val label: String,
    val message: String,
)

data class OrchestratorProgressInfo(
    val taskId: String,
    val node: String,
    val message: String,
    val percent: Double = 0.0,
    val goalIndex: Int = 0,
    val totalGoals: Int = 0,
    val stepIndex: Int = 0,
    val totalSteps: Int = 0,
    val startedAtMs: Long = 0,
    val steps: List<OrchestratorProgressStep> = emptyList(),
)

data class QualificationProgressStep(
    val timestamp: Long,
    val message: String,
    val step: String,
    val metadata: Map<String, String> = emptyMap(),
)

data class QualificationProgressInfo(
    val taskId: String,
    val message: String,
    val step: String,
    val steps: List<QualificationProgressStep> = emptyList(),
)
