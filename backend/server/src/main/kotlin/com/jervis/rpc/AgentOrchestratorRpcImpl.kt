package com.jervis.rpc

import com.jervis.dto.ChatResponseDto
import com.jervis.dto.ChatResponseType
import com.jervis.repository.TaskHistoryRepository
import com.jervis.repository.TaskRepository
import com.jervis.service.IAgentOrchestratorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
class AgentOrchestratorRpcImpl(
    private val taskRepository: TaskRepository,
    private val taskHistoryRepository: TaskHistoryRepository,
    private val taskService: com.jervis.service.background.TaskService,
    private val projectService: com.jervis.service.project.ProjectService,
    private val pythonOrchestratorClient: com.jervis.configuration.PythonOrchestratorClient,
) : IAgentOrchestratorService {
    private val logger = KotlinLogging.logger {}
    private val backgroundScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val queueStatusStreams = ConcurrentHashMap<String, MutableSharedFlow<ChatResponseDto>>()

    /**
     * Emit queue status to ALL connected client streams.
     * Queue/agent status is global — all clients see the same orchestrator state.
     */
    suspend fun emitQueueStatusToAll(response: ChatResponseDto) {
        for ((_, stream) in queueStatusStreams) {
            stream.emit(response)
        }
    }

    override fun subscribeToQueueStatus(clientId: String): Flow<ChatResponseDto> {
        logger.info { "SUBSCRIBE_TO_QUEUE_STATUS | clientId=$clientId" }

        val sharedFlow =
            queueStatusStreams.getOrPut(clientId) {
                MutableSharedFlow(
                    replay = 1,
                    extraBufferCapacity = 10,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                )
            }

        // Emit initial global queue status
        backgroundScope.launch {
            try {
                val response = buildGlobalQueueStatusResponse()
                sharedFlow.emit(response)
                logger.info {
                    "INITIAL_QUEUE_STATUS_EMITTED | clientId=$clientId | ${response.metadata}"
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to emit initial queue status for clientId=$clientId" }
            }
        }

        return sharedFlow
    }

    // --- Queue Management RPC Methods ---

    override suspend fun getPendingTasks(clientId: String): com.jervis.dto.PendingTasksDto {
        logger.info { "GET_PENDING_TASKS | clientId=$clientId (global)" }

        val running = taskService.getCurrentRunningTask()

        val foregroundTasks = taskService.getPendingForegroundTasks()
        val (backgroundTasks, backgroundTotal) = taskService.getPendingBackgroundTasksPaginated(limit = 20, offset = 0)

        val foregroundItems = foregroundTasks.map { task -> task.toPendingTaskItemDto() }
        val backgroundItems = backgroundTasks.map { task -> task.toPendingTaskItemDto() }

        val runningItem = running?.toPendingTaskItemDto()

        logger.info {
            "PENDING_TASKS_RESULT | clientId=$clientId | foreground=${foregroundItems.size} | background=${backgroundItems.size} | backgroundTotal=$backgroundTotal | hasRunning=${runningItem != null}"
        }

        return com.jervis.dto.PendingTasksDto(
            foreground = foregroundItems,
            background = backgroundItems,
            runningTask = runningItem,
            backgroundTotalCount = backgroundTotal,
        )
    }

    override suspend fun getBackgroundTasksPage(limit: Int, offset: Int): com.jervis.dto.PendingTasksPageDto {
        logger.info { "GET_BACKGROUND_TASKS_PAGE | limit=$limit | offset=$offset" }

        val (tasks, totalCount) = taskService.getPendingBackgroundTasksPaginated(limit, offset)
        val items = tasks.map { task -> task.toPendingTaskItemDto() }

        logger.info { "BACKGROUND_TASKS_PAGE_RESULT | items=${items.size} | totalCount=$totalCount | offset=$offset" }

        return com.jervis.dto.PendingTasksPageDto(
            items = items,
            totalCount = totalCount,
            hasMore = (offset + limit) < totalCount,
        )
    }

    override suspend fun reorderTask(taskId: String, newPosition: Int) {
        logger.info { "REORDER_TASK | taskId=$taskId | newPosition=$newPosition" }

        val taskIdTyped = com.jervis.common.types.TaskId.fromString(taskId)
        val task = taskRepository.getById(taskIdTyped)
            ?: run {
                logger.warn { "REORDER_TASK_NOT_FOUND | taskId=$taskId" }
                return
            }

        if (task.state != com.jervis.dto.TaskStateEnum.READY_FOR_GPU) {
            logger.warn { "REORDER_TASK_INVALID_STATE | taskId=$taskId | state=${task.state}" }
            return
        }

        taskService.reorderTaskInQueue(task, newPosition)

        // Emit updated queue status
        try {
            emitGlobalQueueStatus()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to emit queue status after reorder" }
        }
    }

    override suspend fun moveTask(taskId: String, targetMode: String) {
        logger.info { "MOVE_TASK | taskId=$taskId | targetMode=$targetMode" }

        val taskIdTyped = com.jervis.common.types.TaskId.fromString(taskId)
        val task = taskRepository.getById(taskIdTyped)
            ?: run {
                logger.warn { "MOVE_TASK_NOT_FOUND | taskId=$taskId" }
                return
            }

        if (task.state != com.jervis.dto.TaskStateEnum.READY_FOR_GPU) {
            logger.warn { "MOVE_TASK_INVALID_STATE | taskId=$taskId | state=${task.state}" }
            return
        }

        val mode = when (targetMode) {
            "FOREGROUND" -> com.jervis.entity.ProcessingMode.FOREGROUND
            "BACKGROUND" -> com.jervis.entity.ProcessingMode.BACKGROUND
            else -> {
                logger.warn { "MOVE_TASK_INVALID_MODE | taskId=$taskId | targetMode=$targetMode" }
                return
            }
        }

        taskService.moveTaskToQueue(task, mode)

        // Emit updated queue status
        try {
            emitGlobalQueueStatus()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to emit queue status after move" }
        }
    }

    override suspend fun cancelOrchestration(taskId: String) {
        val taskIdTyped = com.jervis.common.types.TaskId.fromString(taskId)
        val task = taskRepository.getById(taskIdTyped)
            ?: throw IllegalArgumentException("Task not found: $taskId")
        val threadId = task.orchestratorThreadId
            ?: throw IllegalStateException("Task has no orchestrator thread: $taskId")
        pythonOrchestratorClient.cancelOrchestration(threadId)
        logger.info { "ORCHESTRATION_CANCELLED: taskId=$taskId threadId=$threadId" }
    }

    // --- Task History ---

    override suspend fun getTaskHistory(limit: Int, offset: Int): com.jervis.dto.TaskHistoryPageDto {
        val totalCount = taskHistoryRepository.countAllBy()
        val items = taskHistoryRepository
            .findAllByOrderByCompletedAtDesc(PageRequest.of(offset / limit.coerceAtLeast(1), limit.coerceAtLeast(1)))
            .toList()
            .map { doc ->
                // Prefer orchestratorSteps (has durations) over workflowSteps
                val nodes = doc.orchestratorStepsJson?.let { json ->
                    try {
                        val elements = Json.decodeFromString<List<kotlinx.serialization.json.JsonObject>>(json)
                        elements.map { obj ->
                            com.jervis.dto.TaskHistoryNodeDto(
                                node = obj["node"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: "",
                                label = obj["label"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: "",
                                durationMs = obj["durationMs"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() },
                            )
                        }
                    } catch (_: Exception) { null }
                } ?: doc.workflowStepsJson?.let { json ->
                    try {
                        Json.decodeFromString<List<com.jervis.dto.ui.ChatMessage.WorkflowStep>>(json)
                            .map { step ->
                                com.jervis.dto.TaskHistoryNodeDto(
                                    node = step.node,
                                    label = step.label,
                                )
                            }
                    } catch (_: Exception) { emptyList() }
                } ?: emptyList()

                com.jervis.dto.TaskHistoryEntryDto(
                    id = doc.id.toString(),
                    taskId = doc.taskId,
                    taskPreview = doc.taskPreview,
                    projectName = doc.projectName,
                    clientName = doc.clientName,
                    startedAt = doc.startedAt?.toString(),
                    completedAt = doc.completedAt.toString(),
                    status = doc.status,
                    processingMode = doc.processingMode,
                    nodes = nodes,
                )
            }

        return com.jervis.dto.TaskHistoryPageDto(
            items = items,
            totalCount = totalCount,
            hasMore = (offset + limit) < totalCount,
        )
    }

    // --- Internal Helpers ---

    /**
     * Convert TaskDocument to PendingTaskItemDto for queue display.
     */
    private suspend fun com.jervis.entity.TaskDocument.toPendingTaskItemDto(): com.jervis.dto.PendingTaskItemDto {
        val projectName = this.projectId?.let { pid ->
            try { projectService.getProjectById(pid).name } catch (_: Exception) { null }
        } ?: "General"

        val preview = this.content.take(60).let {
            if (this.content.length > 60) "$it..." else it
        }

        val taskTypeLabel = when (this.type) {
            com.jervis.dto.TaskTypeEnum.USER_INPUT_PROCESSING -> "Asistent"
            com.jervis.dto.TaskTypeEnum.WIKI_PROCESSING -> "Wiki"
            com.jervis.dto.TaskTypeEnum.BUGTRACKER_PROCESSING -> "BugTracker"
            com.jervis.dto.TaskTypeEnum.EMAIL_PROCESSING -> "Email"
            else -> this.type.toString()
        }

        return com.jervis.dto.PendingTaskItemDto(
            taskId = this.id.toString(),
            projectName = projectName,
            preview = preview,
            taskType = taskTypeLabel,
            processingMode = this.processingMode.name,
            queuePosition = this.queuePosition,
        )
    }

    /**
     * Build a global queue status response (all clients, all queues).
     */
    suspend fun buildGlobalQueueStatusResponse(): ChatResponseDto {
        val (runningTask, queueSize) = taskService.getGlobalQueueStatus()

        val foregroundTasks = taskService.getPendingForegroundTasks()
        val backgroundTasks = taskService.getPendingBackgroundTasks()

        val pendingItems = buildMap {
            put("pendingItemCount", foregroundTasks.size.toString())
            foregroundTasks.forEachIndexed { index, task ->
                val preview = task.content.take(60).let {
                    if (task.content.length > 60) "$it..." else it
                }
                val pName = task.projectId?.let { pid ->
                    try { projectService.getProjectById(pid).name } catch (_: Exception) { null }
                } ?: "General"
                put("pendingItem_${index}_preview", preview)
                put("pendingItem_${index}_project", pName)
                put("pendingItem_${index}_taskId", task.id.toString())
            }
            put("backgroundItemCount", backgroundTasks.size.toString())
            backgroundTasks.forEachIndexed { index, task ->
                val preview = task.content.take(60).let {
                    if (task.content.length > 60) "$it..." else it
                }
                val pName = task.projectId?.let { pid ->
                    try { projectService.getProjectById(pid).name } catch (_: Exception) { null }
                } ?: "General"
                put("backgroundItem_${index}_preview", preview)
                put("backgroundItem_${index}_project", pName)
                put("backgroundItem_${index}_taskId", task.id.toString())
            }
        }

        val orchestratorHealthy = pythonOrchestratorClient.circuitBreaker.currentState() !=
            com.jervis.configuration.CircuitBreaker.State.OPEN

        val metadata = if (runningTask != null) {
            val projectName = runningTask.projectId?.let { pid ->
                try { projectService.getProjectById(pid).name } catch (_: Exception) { null }
            } ?: "General"
            val taskPreview = runningTask.content.take(50).let {
                if (runningTask.content.length > 50) "$it..." else it
            }
            val taskTypeLabel = when (runningTask.type) {
                com.jervis.dto.TaskTypeEnum.USER_INPUT_PROCESSING -> "Asistent"
                com.jervis.dto.TaskTypeEnum.WIKI_PROCESSING -> "Wiki"
                com.jervis.dto.TaskTypeEnum.BUGTRACKER_PROCESSING -> "BugTracker"
                com.jervis.dto.TaskTypeEnum.EMAIL_PROCESSING -> "Email"
                else -> runningTask.type.toString()
            }
            mapOf(
                "runningProjectId" to (runningTask.projectId?.toString() ?: "__background__"),
                "runningProjectName" to projectName,
                "runningTaskPreview" to taskPreview,
                "runningTaskType" to taskTypeLabel,
                "queueSize" to queueSize.toString(),
                "orchestratorHealthy" to orchestratorHealthy.toString(),
            )
        } else {
            mapOf(
                "runningProjectId" to "none",
                "runningProjectName" to "None",
                "runningTaskPreview" to "",
                "runningTaskType" to "",
                "queueSize" to queueSize.toString(),
                "orchestratorHealthy" to orchestratorHealthy.toString(),
            )
        }

        return ChatResponseDto(
            message = "Queue status update",
            type = ChatResponseType.QUEUE_STATUS,
            metadata = metadata + pendingItems,
        )
    }

    /**
     * Emit global queue status to all connected clients.
     */
    suspend fun emitGlobalQueueStatus() {
        val response = buildGlobalQueueStatusResponse()
        emitQueueStatusToAll(response)
    }
}
