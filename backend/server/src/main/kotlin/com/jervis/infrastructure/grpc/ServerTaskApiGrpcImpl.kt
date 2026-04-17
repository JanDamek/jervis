package com.jervis.infrastructure.grpc

import com.jervis.chat.ChatRpcImpl
import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.common.types.SourceUrn
import com.jervis.common.types.TaskId
import com.jervis.contracts.server.CreateTaskRequest
import com.jervis.contracts.server.CreateTaskResponse
import com.jervis.contracts.server.CreateWorkPlanRequest
import com.jervis.contracts.server.CreateWorkPlanResponse
import com.jervis.contracts.server.GetQueueRequest
import com.jervis.contracts.server.GetTaskResponse
import com.jervis.contracts.server.GetTaskStatusResponse
import com.jervis.contracts.server.PushBackgroundResultRequest
import com.jervis.contracts.server.PushBackgroundResultResponse
import com.jervis.contracts.server.PushNotificationRequest
import com.jervis.contracts.server.PushNotificationResponse
import com.jervis.contracts.server.RecentTasksRequest
import com.jervis.contracts.server.RespondToTaskRequest
import com.jervis.contracts.server.RespondToTaskResponse
import com.jervis.contracts.server.SearchTasksRequest
import com.jervis.contracts.server.ServerTaskApiServiceGrpcKt
import com.jervis.contracts.server.SetPriorityRequest
import com.jervis.contracts.server.SetPriorityResponse
import com.jervis.contracts.server.SimpleTaskActionResponse
import com.jervis.contracts.server.TaskIdRequest
import com.jervis.contracts.server.TaskListResponse
import com.jervis.contracts.server.TaskNoteRequest
import com.jervis.dto.task.TaskStateEnum
import com.jervis.dto.task.TaskTypeEnum
import com.jervis.infrastructure.notification.ApnsPushService
import com.jervis.infrastructure.notification.FcmPushService
import com.jervis.preferences.PreferenceService
import com.jervis.task.PendingTaskService
import com.jervis.task.ProcessingMode
import com.jervis.task.TaskDocument
import com.jervis.task.TaskRepository
import com.jervis.task.TaskService
import com.jervis.task.UserTaskService
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@Component
class ServerTaskApiGrpcImpl(
    private val taskRepository: TaskRepository,
    private val taskService: TaskService,
    private val userTaskService: UserTaskService,
    private val preferenceService: PreferenceService,
    private val pendingTaskService: PendingTaskService,
    private val fcmPushService: FcmPushService,
    private val apnsPushService: ApnsPushService,
    private val chatRpcImpl: ChatRpcImpl? = null,
) : ServerTaskApiServiceGrpcKt.ServerTaskApiServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}

    override suspend fun createTask(request: CreateTaskRequest): CreateTaskResponse {
        val clientId = ClientId(ObjectId(request.clientId))
        val projectId = request.projectId.takeIf { it.isNotBlank() }?.let { ProjectId(ObjectId(it)) }
        val content = request.description.takeIf { it.isNotBlank() }
            ?: request.query.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Either 'description' or 'query' must be provided")
        val taskName = request.title.takeIf { it.isNotBlank() } ?: content.take(100)

        val scheduledInstant = request.scheduledAt.takeIf { it.isNotBlank() }?.let {
            try { Instant.parse(it) } catch (_: Exception) { null }
        }
        val isScheduled = scheduledInstant != null
        val skipKb = request.skipIndexing
        val taskType = if (isScheduled) TaskTypeEnum.SCHEDULED else TaskTypeEnum.INSTANT
        val taskState = when {
            isScheduled -> TaskStateEnum.NEW
            skipKb -> TaskStateEnum.QUEUED
            else -> TaskStateEnum.INDEXING
        }

        if (isScheduled && request.createdBy == "orchestrator_agent") {
            val recentCutoff = Instant.now().minus(Duration.ofHours(24))
            val recentScheduled = taskRepository.findByClientIdAndType(clientId, TaskTypeEnum.SCHEDULED)
                .toList()
                .filter { it.createdAt?.isAfter(recentCutoff) == true && it.state != TaskStateEnum.ERROR }
            if (recentScheduled.size >= 3) {
                logger.warn {
                    "TASK_RATE_LIMIT | clientId=${request.clientId} | recentCount=${recentScheduled.size}"
                }
                val head = recentScheduled.first()
                return CreateTaskResponse.newBuilder()
                    .setTaskId(head.id.toString())
                    .setState(head.state.name)
                    .setName(head.taskName)
                    .setDeduplicated(true)
                    .setReason("Rate limited: max 3 scheduled tasks per client per 24h")
                    .build()
            }
        }

        val normalizedTitle = taskName.lowercase().replace(Regex("[^a-z0-9]"), "").take(60)
        val dedupCorrelationId = "sched:${request.clientId.takeLast(8)}:$normalizedTitle"

        val task = taskService.createTask(
            taskType = taskType,
            content = content,
            clientId = clientId,
            correlationId = dedupCorrelationId,
            sourceUrn = SourceUrn(request.createdBy.takeIf { it.isNotBlank() }?.let { "agent://$it" } ?: "chat://foreground"),
            projectId = projectId,
            state = taskState,
            taskName = taskName,
            scheduledAt = scheduledInstant,
            cronTimezone = request.cronTimezone.takeIf { it.isNotBlank() },
            followUserTimezone = request.followUserTimezone,
            scheduledLocalTime = request.scheduledLocalTime.takeIf { it.isNotBlank() },
        )
        return CreateTaskResponse.newBuilder()
            .setTaskId(task.id.toString())
            .setState(task.state.name)
            .setName(task.taskName)
            .build()
    }

    override suspend fun respondToTask(request: RespondToTaskRequest): RespondToTaskResponse {
        val taskId = TaskId(ObjectId(request.taskId))
        val task = taskRepository.getById(taskId)
            ?: return RespondToTaskResponse.newBuilder().setOk(false).setError("Task not found").build()
        val updated = task.copy(
            state = TaskStateEnum.QUEUED,
            content = "${task.content}\n\n[User response]: ${request.response}",
            pendingUserQuestion = null,
        )
        taskRepository.save(updated)
        return RespondToTaskResponse.newBuilder().setOk(true).build()
    }

    override suspend fun getTask(request: TaskIdRequest): GetTaskResponse {
        val taskId = TaskId(ObjectId(request.taskId))
        val task = taskRepository.getById(taskId)
            ?: return GetTaskResponse.newBuilder().setOk(false).setError("Task not found").build()
        return GetTaskResponse.newBuilder()
            .setOk(true)
            .setId(task.id.toString())
            .setState(task.state.name)
            .setAgentJobName(task.agentJobName.orEmpty())
            .setAgentJobState(task.agentJobState.orEmpty())
            .setAgentJobWorkspacePath(task.agentJobWorkspacePath.orEmpty())
            .setAgentJobAgentType(task.agentJobAgentType.orEmpty())
            .setClientId(task.clientId.toString())
            .setProjectId(task.projectId?.toString().orEmpty())
            .setSourceUrn(task.sourceUrn?.value.orEmpty())
            .build()
    }

    override suspend fun getTaskStatus(request: TaskIdRequest): GetTaskStatusResponse {
        val taskId = TaskId(ObjectId(request.taskId))
        val task = taskRepository.getById(taskId)
            ?: return GetTaskStatusResponse.newBuilder().setOk(false).setError("Task not found").build()
        return GetTaskStatusResponse.newBuilder()
            .setOk(true)
            .setId(task.id.toString())
            .setTitle(task.taskName)
            .setState(task.state.name)
            .setContent(task.content)
            .setClientId(task.clientId.toString())
            .setProjectId(task.projectId?.toString().orEmpty())
            .setCreatedAt(task.createdAt.toString())
            .setProcessingMode(task.processingMode.name)
            .setQuestion(task.pendingUserQuestion.orEmpty())
            .setErrorMessage(task.errorMessage.orEmpty())
            .build()
    }

    override suspend fun searchTasks(request: SearchTasksRequest): TaskListResponse {
        val stateFilter = request.state.takeIf { it.isNotBlank() }?.let {
            try { TaskStateEnum.valueOf(it.uppercase()) } catch (_: Exception) { null }
        }
        val limit = if (request.limit > 0) request.limit else 5
        val result = userTaskService.searchAllTasks(
            query = request.query.takeIf { it.isNotBlank() },
            offset = 0,
            limit = limit,
            stateFilter = stateFilter,
        )
        val items = result.items.map { task -> task.toSummaryMap() }
        return TaskListResponse.newBuilder()
            .setItemsJson(json.encodeToString(items))
            .build()
    }

    override suspend fun createWorkPlan(request: CreateWorkPlanRequest): CreateWorkPlanResponse {
        val clientId = ClientId(ObjectId(request.clientId))
        val projectId = request.projectId.takeIf { it.isNotBlank() }?.let { ProjectId(ObjectId(it)) }
        val correlationPrefix = "workplan-${UUID.randomUUID().toString().take(8)}"

        val rootTask = taskService.createTask(
            taskType = TaskTypeEnum.INSTANT,
            content = buildString {
                appendLine("# Work Plan: ${request.title}")
                appendLine()
                request.phasesList.forEachIndexed { pi, phase ->
                    appendLine("## Phase ${pi + 1}: ${phase.name}")
                    phase.tasksList.forEach { task ->
                        val deps = task.dependsOnList.joinToString(", ").ifBlank { "none" }
                        val actionType = task.actionType.takeIf { it.isNotBlank() } ?: "TASK"
                        appendLine("- [$actionType] ${task.title} (depends: $deps)")
                    }
                    appendLine()
                }
            },
            clientId = clientId,
            correlationId = correlationPrefix,
            sourceUrn = SourceUrn("chat://work-plan"),
            projectId = projectId,
            state = TaskStateEnum.BLOCKED,
            taskName = request.title,
        )

        val titleToId = mutableMapOf<String, TaskId>()
        val childTasks = mutableListOf<TaskDocument>()
        var totalChildren = 0
        for ((phaseIndex, phase) in request.phasesList.withIndex()) {
            for ((taskIndex, taskReq) in phase.tasksList.withIndex()) {
                val childTask = taskService.createTask(
                    taskType = TaskTypeEnum.INSTANT,
                    content = taskReq.description,
                    clientId = clientId,
                    correlationId = "$correlationPrefix-p${phaseIndex}-t${taskIndex}",
                    sourceUrn = SourceUrn("workplan://${rootTask.id}"),
                    projectId = projectId,
                    state = TaskStateEnum.BLOCKED,
                    taskName = taskReq.title,
                )
                titleToId[taskReq.title] = childTask.id
                childTasks.add(childTask)
                totalChildren++
            }
        }
        var childIdx = 0
        for ((phaseIndex, phase) in request.phasesList.withIndex()) {
            for ((taskIndex, taskReq) in phase.tasksList.withIndex()) {
                val child = childTasks[childIdx]
                val blockedBy = taskReq.dependsOnList.mapNotNull { titleToId[it] }
                val state = if (phaseIndex == 0 && blockedBy.isEmpty()) {
                    TaskStateEnum.INDEXING
                } else {
                    TaskStateEnum.BLOCKED
                }
                val updated = child.copy(
                    parentTaskId = rootTask.id,
                    blockedByTaskIds = blockedBy,
                    phase = phase.name,
                    orderInPhase = taskIndex,
                    state = state,
                    processingMode = ProcessingMode.BACKGROUND,
                    actionType = taskReq.actionType.takeIf { it.isNotBlank() },
                )
                taskRepository.save(updated)
                childIdx++
            }
        }

        return CreateWorkPlanResponse.newBuilder()
            .setOk(true)
            .setRootTaskId(rootTask.id.toString())
            .setPhaseCount(request.phasesCount)
            .setChildCount(totalChildren)
            .build()
    }

    override suspend fun recentTasks(request: RecentTasksRequest): TaskListResponse {
        val limit = if (request.limit > 0) request.limit else 10
        val since = request.since.takeIf { it.isNotBlank() } ?: "today"
        val userZone = preferenceService.getUserTimezone()
        val sinceInstant = parseSince(since, userZone)
        val clientId = request.clientId.takeIf { it.isNotBlank() }
        val stateStr = request.state.takeIf { it.isNotBlank() && it != "all" }

        val flow = when {
            clientId != null -> taskRepository
                .findByClientIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                    ClientId(ObjectId(clientId)),
                    sinceInstant,
                )
            stateStr != null -> {
                val state = try { TaskStateEnum.valueOf(stateStr.uppercase()) } catch (_: Exception) { null }
                if (state != null) {
                    taskRepository.findByStateAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(state, sinceInstant)
                } else {
                    taskRepository.findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(sinceInstant)
                }
            }
            else -> taskRepository.findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(sinceInstant)
        }
        val items = mutableListOf<Map<String, String>>()
        flow.collect { task ->
            if (items.size < limit) items.add(task.toSummaryMap(includeProcessingMode = true))
        }
        return TaskListResponse.newBuilder().setItemsJson(json.encodeToString(items)).build()
    }

    override suspend fun getQueue(request: GetQueueRequest): TaskListResponse {
        val limit = if (request.limit > 0) request.limit else 20
        val clientId = request.clientId.takeIf { it.isNotBlank() }
        val allowedStates = listOf(
            TaskStateEnum.INDEXING,
            TaskStateEnum.QUEUED,
            TaskStateEnum.PROCESSING,
            TaskStateEnum.BLOCKED,
        )
        val flow = if (clientId != null) {
            taskRepository.findByClientIdAndProcessingModeAndStateInOrderByPriorityScoreDescCreatedAtAsc(
                ClientId(ObjectId(clientId)),
                ProcessingMode.BACKGROUND,
                allowedStates,
            )
        } else {
            taskRepository.findByProcessingModeAndStateInOrderByPriorityScoreDescCreatedAtAsc(
                ProcessingMode.BACKGROUND,
                allowedStates,
            )
        }
        val items = mutableListOf<Map<String, String>>()
        flow.collect { task ->
            if (items.size < limit) {
                items.add(
                    task.toSummaryMap(includeProcessingMode = true) + mapOf(
                        "priorityScore" to (task.priorityScore?.toString() ?: "50"),
                        "parentTaskId" to (task.parentTaskId?.toString() ?: ""),
                        "phase" to (task.phase ?: ""),
                        "estimatedComplexity" to (task.estimatedComplexity ?: ""),
                    ),
                )
            }
        }
        return TaskListResponse.newBuilder().setItemsJson(json.encodeToString(items)).build()
    }

    override suspend fun retryTask(request: TaskIdRequest): SimpleTaskActionResponse {
        val taskId = TaskId(ObjectId(request.taskId))
        val task = taskRepository.getById(taskId)
            ?: return SimpleTaskActionResponse.newBuilder().setOk(false)
                .setTaskId(request.taskId).setError("Task not found").build()
        if (task.state != TaskStateEnum.ERROR) {
            return SimpleTaskActionResponse.newBuilder().setOk(false).setTaskId(request.taskId)
                .setError("Task is not in ERROR state (current: ${task.state.name})").build()
        }
        val originalContent = task.content.let { content ->
            val marker = "Obsah úlohy:\n"
            val idx = content.lastIndexOf(marker)
            if (idx >= 0) content.substring(idx + marker.length).trim() else content
        }
        val originalTitle = task.taskName.let { name ->
            val failedMarker = " selhalo: "
            val idx = name.indexOf(failedMarker)
            if (idx >= 0) originalContent.take(100) else name
        }
        val updated = task.copy(
            state = TaskStateEnum.QUEUED,
            content = originalContent,
            taskName = originalTitle,
            errorMessage = null,
            orchestratorThreadId = null,
            dispatchRetryCount = 0,
            nextDispatchRetryAt = null,
        )
        taskRepository.save(updated)
        logger.info {
            "TASK_RETRY | taskId=${request.taskId} | title=${updated.taskName} | previousError=${task.errorMessage?.take(100)}"
        }
        return SimpleTaskActionResponse.newBuilder().setOk(true)
            .setTaskId(request.taskId).setState("QUEUED").build()
    }

    override suspend fun cancelTask(request: TaskIdRequest): SimpleTaskActionResponse {
        val taskId = TaskId(ObjectId(request.taskId))
        val task = taskRepository.getById(taskId)
            ?: return SimpleTaskActionResponse.newBuilder().setOk(false)
                .setTaskId(request.taskId).setError("Task not found").build()
        if (task.state == TaskStateEnum.DONE) {
            return SimpleTaskActionResponse.newBuilder().setOk(true)
                .setTaskId(request.taskId).setState("DONE").build()
        }
        val updated = task.copy(state = TaskStateEnum.DONE)
        taskRepository.save(updated)
        logger.info { "TASK_CANCELLED | taskId=${request.taskId} | previousState=${task.state}" }
        return SimpleTaskActionResponse.newBuilder().setOk(true)
            .setTaskId(request.taskId).setState("DONE").build()
    }

    override suspend fun markDone(request: TaskNoteRequest): SimpleTaskActionResponse {
        val result = pendingTaskService.markDone(request.taskId, request.note.takeIf { it.isNotBlank() })
        return if (result != null) {
            logger.info { "TASK_MARK_DONE | taskId=${request.taskId}" }
            SimpleTaskActionResponse.newBuilder().setOk(true)
                .setTaskId(request.taskId).setState("DONE").build()
        } else {
            SimpleTaskActionResponse.newBuilder().setOk(false)
                .setTaskId(request.taskId).setError("Task not found").build()
        }
    }

    override suspend fun reopen(request: TaskNoteRequest): SimpleTaskActionResponse {
        val result = pendingTaskService.reopen(request.taskId, request.note.takeIf { it.isNotBlank() })
        return if (result != null) {
            logger.info { "TASK_REOPEN | taskId=${request.taskId}" }
            SimpleTaskActionResponse.newBuilder().setOk(true)
                .setTaskId(request.taskId).setState("NEW").build()
        } else {
            SimpleTaskActionResponse.newBuilder().setOk(false)
                .setTaskId(request.taskId).setError("Task not found").build()
        }
    }

    override suspend fun setPriority(request: SetPriorityRequest): SetPriorityResponse {
        val taskId = TaskId(ObjectId(request.taskId))
        val task = taskRepository.getById(taskId)
            ?: return SetPriorityResponse.newBuilder().setOk(false)
                .setTaskId(request.taskId).setError("Task not found").build()
        val clamped = request.priorityScore.coerceIn(0, 100)
        val updated = task.copy(priorityScore = clamped)
        taskRepository.save(updated)
        logger.info { "TASK_PRIORITY_SET | taskId=${request.taskId} | score=$clamped" }
        return SetPriorityResponse.newBuilder().setOk(true)
            .setTaskId(request.taskId).setPriorityScore(updated.priorityScore ?: clamped).build()
    }

    override suspend fun pushNotification(request: PushNotificationRequest): PushNotificationResponse {
        val fcm = try {
            fcmPushService.sendPushNotification(
                clientId = request.clientId,
                title = request.title,
                body = request.body,
                data = request.dataMap,
            )
            "ok"
        } catch (e: Exception) {
            "fcm_error: ${e.message}"
        }
        val apns = try {
            apnsPushService.sendPushNotification(
                clientId = request.clientId,
                title = request.title,
                body = request.body,
                data = request.dataMap,
            )
            "ok"
        } catch (e: Exception) {
            "apns_error: ${e.message}"
        }
        logger.info { "PUSH_NOTIFICATION | clientId=${request.clientId} | title=${request.title} | fcm=$fcm | apns=$apns" }
        return PushNotificationResponse.newBuilder().setOk(true).setFcm(fcm).setApns(apns).build()
    }

    override suspend fun pushBackgroundResult(
        request: PushBackgroundResultRequest,
    ): PushBackgroundResultResponse {
        chatRpcImpl?.pushBackgroundResult(
            taskTitle = request.taskTitle,
            summary = request.summary,
            success = request.success,
            taskId = request.taskId,
            clientId = request.clientId.takeIf { it.isNotBlank() },
            projectId = request.projectId.takeIf { it.isNotBlank() },
        )
        return PushBackgroundResultResponse.newBuilder().setOk(true).build()
    }

    // ── Helpers ──

    private fun TaskDocument.toSummaryMap(includeProcessingMode: Boolean = false): Map<String, String> {
        val base = mapOf(
            "id" to id.toString(),
            "title" to taskName,
            "state" to state.name,
            "content" to content,
            "clientId" to clientId.toString(),
            "projectId" to (projectId?.toString() ?: ""),
            "createdAt" to createdAt.toString(),
        )
        return if (includeProcessingMode) base + ("processingMode" to processingMode.name) else base
    }

    private fun parseSince(since: String, userZone: ZoneId): Instant = when (since) {
        "today" -> Instant.now().atZone(userZone).toLocalDate().atStartOfDay(userZone).toInstant()
        "this_week" -> Instant.now().minus(Duration.ofDays(7))
        "this_month" -> Instant.now().minus(Duration.ofDays(30))
        else -> Instant.now().atZone(userZone).toLocalDate().atStartOfDay(userZone).toInstant()
    }

    companion object {
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    }
}
