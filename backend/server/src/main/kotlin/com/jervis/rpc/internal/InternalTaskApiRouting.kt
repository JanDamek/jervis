package com.jervis.rpc.internal

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.common.types.SourceUrn
import com.jervis.common.types.TaskId
import com.jervis.dto.task.TaskStateEnum
import com.jervis.dto.task.TaskTypeEnum
import com.jervis.task.TaskRepository
import com.jervis.task.TaskService
import com.jervis.task.UserTaskService
import com.jervis.task.ProcessingMode
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.bson.types.ObjectId
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

private val logger = KotlinLogging.logger {}

/**
 * Internal REST endpoints for task operations.
 *
 * Provides task status, search, and recent tasks for the Python chat agent's tools
 * (get_task_status, search_tasks, list_recent_tasks).
 *
 * All endpoints return JSON and are called from Python orchestrator's kotlin_client.
 */
fun Routing.installInternalTaskApi(
    taskRepository: TaskRepository,
    taskService: TaskService,
    userTaskService: UserTaskService,
) {
    // Create a background task — for chat tool create_background_task / scheduled tasks
    post("/internal/tasks/create") {
        try {
            val body = call.receive<InternalCreateTaskRequest>()
            val clientId = ClientId(ObjectId(body.clientId))
            val projectId = body.projectId?.let { ProjectId(ObjectId(it)) }

            // Support both legacy (query) and new (title+description) field naming
            val content = body.description ?: body.query
                ?: throw IllegalArgumentException("Either 'description' or 'query' must be provided")
            val taskName = body.title ?: content.take(100)

            // Parse scheduledAt if provided → SCHEDULED_TASK type
            val scheduledInstant = body.scheduledAt?.let {
                try { java.time.Instant.parse(it) } catch (_: Exception) { null }
            }
            val isScheduled = scheduledInstant != null
            val skipKb = body.skipIndexing == true
            val taskType = if (isScheduled) TaskTypeEnum.SCHEDULED_TASK else TaskTypeEnum.USER_INPUT_PROCESSING
            val taskState = when {
                isScheduled -> TaskStateEnum.NEW
                skipKb -> TaskStateEnum.QUEUED      // MCP/explicit → skip KB, go straight to orchestrator
                else -> TaskStateEnum.INDEXING
            }

            // Dedup: for SCHEDULED_TASK from orchestrator, limit to max 3 per client per day.
            // The orchestrator tends to create many follow-up tasks with varying titles
            // for the same topic (e.g., invoice follow-ups with different wording).
            if (isScheduled && body.createdBy == "orchestrator_agent") {
                val recentCutoff = java.time.Instant.now().minus(java.time.Duration.ofHours(24))
                val recentScheduled = taskRepository.findByClientIdAndType(clientId, TaskTypeEnum.SCHEDULED_TASK)
                    .toList()
                    .filter { it.createdAt?.isAfter(recentCutoff) == true && it.state != TaskStateEnum.ERROR }
                if (recentScheduled.size >= 3) {
                    logger.warn {
                        "TASK_RATE_LIMIT | clientId=${body.clientId} | recentCount=${recentScheduled.size} " +
                            "| title=$taskName — too many scheduled tasks in 24h, rejecting"
                    }
                    call.respondText(
                        Json.encodeToString(mapOf(
                            "taskId" to recentScheduled.first().id.toString(),
                            "state" to recentScheduled.first().state.name,
                            "name" to recentScheduled.first().taskName,
                            "deduplicated" to "true",
                            "reason" to "Rate limited: max 3 scheduled tasks per client per 24h",
                        )),
                        ContentType.Application.Json,
                    )
                    return@post
                }
            }

            val normalizedTitle = taskName.lowercase().replace(Regex("[^a-z0-9]"), "").take(60)
            val dedupCorrelationId = "sched:${body.clientId.takeLast(8)}:$normalizedTitle"

            val task = taskService.createTask(
                taskType = taskType,
                content = content,
                clientId = clientId,
                correlationId = dedupCorrelationId,
                sourceUrn = SourceUrn(body.createdBy?.let { "agent://$it" } ?: "chat://foreground"),
                projectId = projectId,
                state = taskState,
                taskName = taskName,
                scheduledAt = scheduledInstant,
            )
            call.respondText(
                Json.encodeToString(mapOf(
                    "taskId" to task.id.toString(),
                    "state" to task.state.name,
                    "name" to task.taskName,
                )),
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=tasks/create" }
            call.respondText(
                """{"ok":false,"error":"${e.message?.replace("\"", "\\\"")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // Respond to a USER_TASK — for chat tool respond_to_user_task
    post("/internal/tasks/{taskId}/respond") {
        try {
            val taskIdStr = call.parameters["taskId"] ?: ""
            val body = call.receive<InternalRespondToTaskRequest>()
            val taskId = TaskId(ObjectId(taskIdStr))
            val task = taskRepository.getById(taskId)
                ?: return@post call.respondText(
                    """{"ok":false,"error":"Task not found"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )

            // USER_TASK responses go directly to QUEUED — skip KB indexing.
            // The graph agent will detect [User response] and resume BLOCKED vertices.
            val updated = task.copy(
                state = TaskStateEnum.QUEUED,
                content = "${task.content}\n\n[User response]: ${body.response}",
                pendingUserQuestion = null,
            )
            taskRepository.save(updated)
            call.respondText("""{"ok":true}""", ContentType.Application.Json)
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=tasks/respond | taskId=${call.parameters["taskId"]}" }
            call.respondText(
                """{"ok":false,"error":"${e.message?.replace("\"", "\\\"")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // Get full task data by ID — includes agentJob fields for log streaming
    get("/internal/tasks/{taskId}") {
        try {
            val taskIdStr = call.parameters["taskId"] ?: ""
            val taskId = TaskId(ObjectId(taskIdStr))
            val task = taskRepository.getById(taskId)

            if (task != null) {
                val taskMap = mapOf(
                    "id" to task.id.toString(),
                    "state" to task.state.name,
                    "agentJobName" to (task.agentJobName ?: ""),
                    "agentJobState" to (task.agentJobState ?: ""),
                    "agentJobWorkspacePath" to (task.agentJobWorkspacePath ?: ""),
                    "agentJobAgentType" to (task.agentJobAgentType ?: ""),
                    "clientId" to task.clientId.toString(),
                    "projectId" to (task.projectId?.toString() ?: ""),
                    "sourceUrn" to (task.sourceUrn?.value ?: ""),
                )
                call.respondText(
                    Json.encodeToString(taskMap),
                    ContentType.Application.Json,
                )
            } else {
                call.respondText(
                    """{"error":"Task not found"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to get task", e)
            call.respondText(
                """{"error":"${e.message}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // Get task status by ID — for chat tool get_task_status
    get("/internal/tasks/{taskId}/status") {
        try {
            val taskIdStr = call.parameters["taskId"] ?: ""
            val taskId = TaskId(ObjectId(taskIdStr))
            val task = taskRepository.getById(taskId)

            if (task != null) {
                val taskMap = mapOf(
                    "id" to task.id.toString(),
                    "title" to task.taskName,
                    "state" to task.state.name,
                    "content" to task.content.take(500),
                    "clientId" to task.clientId.toString(),
                    "projectId" to (task.projectId?.toString() ?: ""),
                    "createdAt" to task.createdAt.toString(),
                    "processingMode" to task.processingMode.name,
                    "question" to (task.pendingUserQuestion ?: ""),
                    "errorMessage" to (task.errorMessage ?: ""),
                )
                call.respondText(
                    Json.encodeToString(taskMap),
                    ContentType.Application.Json,
                )
            } else {
                call.respondText(
                    """{"error":"Task not found"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )
            }
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=tasks/status | taskId=${call.parameters["taskId"]}" }
            call.respondText(
                """{"error":"${e.message}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // Search tasks by query and optional state — for chat tool search_tasks
    get("/internal/tasks/search") {
        try {
            val query = call.request.queryParameters["q"] ?: ""
            val stateStr = call.request.queryParameters["state"]
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 5

            val stateFilter = stateStr?.let {
                try {
                    TaskStateEnum.valueOf(it.uppercase())
                } catch (_: Exception) {
                    null
                }
            }

            val result = userTaskService.searchAllTasks(
                query = query.ifBlank { null },
                offset = 0,
                limit = limit,
                stateFilter = stateFilter,
            )

            val tasksJson = result.items.map { task ->
                mapOf(
                    "id" to task.id.toString(),
                    "title" to task.taskName,
                    "state" to task.state.name,
                    "content" to task.content.take(200),
                    "clientId" to task.clientId.toString(),
                    "projectId" to (task.projectId?.toString() ?: ""),
                    "createdAt" to task.createdAt.toString(),
                )
            }
            call.respondText(
                Json.encodeToString(tasksJson),
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=tasks/search | q=${call.request.queryParameters["q"]}" }
            call.respondText("[]", ContentType.Application.Json)
        }
    }

    // Create a hierarchical work plan — root task (BLOCKED) + child tasks (BLOCKED/INDEXING)
    post("/internal/tasks/create-work-plan") {
        try {
            val body = call.receive<InternalCreateWorkPlanRequest>()
            val clientId = ClientId(ObjectId(body.clientId))
            val projectId = body.projectId?.let { ProjectId(ObjectId(it)) }
            val correlationPrefix = "workplan-${java.util.UUID.randomUUID().toString().take(8)}"

            // 1. Create root task (BLOCKED state)
            val rootTask = taskService.createTask(
                taskType = TaskTypeEnum.USER_INPUT_PROCESSING,
                content = buildString {
                    appendLine("# Work Plan: ${body.title}")
                    appendLine()
                    body.phases.forEachIndexed { pi, phase ->
                        appendLine("## Phase ${pi + 1}: ${phase.name}")
                        phase.tasks.forEachIndexed { ti, task ->
                            val deps = task.dependsOn?.joinToString(", ") ?: "none"
                            appendLine("- [${task.actionType ?: "TASK"}] ${task.title} (depends: $deps)")
                        }
                        appendLine()
                    }
                },
                clientId = clientId,
                correlationId = correlationPrefix,
                sourceUrn = SourceUrn("chat://work-plan"),
                projectId = projectId,
                state = TaskStateEnum.BLOCKED,
                taskName = body.title,
            )

            // 2. Create child tasks per phase
            // First pass: create all tasks to get their IDs, map title → TaskId
            val titleToId = mutableMapOf<String, TaskId>()
            val childTasks = mutableListOf<com.jervis.task.TaskDocument>()
            var totalChildren = 0

            for ((phaseIndex, phase) in body.phases.withIndex()) {
                for ((taskIndex, taskReq) in phase.tasks.withIndex()) {
                    val childTask = taskService.createTask(
                        taskType = TaskTypeEnum.USER_INPUT_PROCESSING,
                        content = taskReq.description,
                        clientId = clientId,
                        correlationId = "$correlationPrefix-p${phaseIndex}-t${taskIndex}",
                        sourceUrn = SourceUrn("workplan://${rootTask.id}"),
                        projectId = projectId,
                        state = TaskStateEnum.BLOCKED, // Will adjust below
                        taskName = taskReq.title,
                    )
                    titleToId[taskReq.title] = childTask.id
                    childTasks.add(childTask)
                    totalChildren++
                }
            }

            // 3. Second pass: set parentTaskId, blockedByTaskIds, phase, orderInPhase
            var childIdx = 0
            for ((phaseIndex, phase) in body.phases.withIndex()) {
                for ((taskIndex, taskReq) in phase.tasks.withIndex()) {
                    val child = childTasks[childIdx]
                    val blockedBy = taskReq.dependsOn
                        ?.mapNotNull { depTitle -> titleToId[depTitle] }
                        ?: emptyList()

                    // First phase tasks with no dependencies start as INDEXING
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
                        actionType = taskReq.actionType,
                    )
                    taskRepository.save(updated)
                    childIdx++
                }
            }

            call.respondText(
                Json.encodeToString(mapOf(
                    "rootTaskId" to rootTask.id.toString(),
                    "phaseCount" to body.phases.size.toString(),
                    "childCount" to totalChildren.toString(),
                )),
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=tasks/create-work-plan" }
            call.respondText(
                """{"ok":false,"error":"${e.message?.replace("\"", "\\\"")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // List recent tasks — for chat tool list_recent_tasks
    get("/internal/tasks/recent") {
        try {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10
            val stateStr = call.request.queryParameters["state"]
            val since = call.request.queryParameters["since"] ?: "today"
            val clientIdStr = call.request.queryParameters["clientId"]

            val sinceInstant = parseSince(since)

            val tasks = when {
                clientIdStr != null -> taskRepository.findByClientIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                    ClientId(ObjectId(clientIdStr)),
                    sinceInstant,
                )
                stateStr != null && stateStr != "all" -> {
                    val state = try { TaskStateEnum.valueOf(stateStr.uppercase()) } catch (_: Exception) { null }
                    if (state != null) {
                        taskRepository.findByStateAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(state, sinceInstant)
                    } else {
                        taskRepository.findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(sinceInstant)
                    }
                }
                else -> taskRepository.findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(sinceInstant)
            }

            val taskList = mutableListOf<Map<String, String>>()
            tasks.collect { task ->
                if (taskList.size < limit) {
                    taskList.add(
                        mapOf(
                            "id" to task.id.toString(),
                            "title" to task.taskName,
                            "state" to task.state.name,
                            "content" to task.content.take(200),
                            "clientId" to task.clientId.toString(),
                            "projectId" to (task.projectId?.toString() ?: ""),
                            "createdAt" to task.createdAt.toString(),
                            "processingMode" to task.processingMode.name,
                        ),
                    )
                }
            }

            call.respondText(
                Json.encodeToString(taskList),
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=tasks/recent | since=${call.request.queryParameters["since"]}" }
            call.respondText("[]", ContentType.Application.Json)
        }
    }
    // --- Queue inspection & priority management (for graph agent PLANNER) ---

    // Get queued tasks across all clients/projects (for LLM prioritization)
    get("/internal/tasks/queue") {
        try {
            val mode = call.request.queryParameters["mode"]?.uppercase()
            val clientIdStr = call.request.queryParameters["clientId"]
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20

            val tasks = mutableListOf<Map<String, String>>()

            // Query BACKGROUND tasks ordered by priority (same order as BackgroundEngine)
            val flow = if (clientIdStr != null) {
                taskRepository.findByClientIdAndProcessingModeAndStateInOrderByPriorityScoreDescCreatedAtAsc(
                    ClientId(ObjectId(clientIdStr)),
                    ProcessingMode.BACKGROUND,
                    listOf(
                        TaskStateEnum.INDEXING,
                        TaskStateEnum.QUEUED,
                        TaskStateEnum.PROCESSING,
                        TaskStateEnum.BLOCKED,
                    ),
                )
            } else {
                taskRepository.findByProcessingModeAndStateInOrderByPriorityScoreDescCreatedAtAsc(
                    ProcessingMode.BACKGROUND,
                    listOf(
                        TaskStateEnum.INDEXING,
                        TaskStateEnum.QUEUED,
                        TaskStateEnum.PROCESSING,
                        TaskStateEnum.BLOCKED,
                    ),
                )
            }

            flow.collect { task ->
                if (tasks.size < limit) {
                    tasks.add(mapOf(
                        "id" to task.id.toString(),
                        "title" to task.taskName,
                        "state" to task.state.name,
                        "content" to task.content.take(300),
                        "clientId" to task.clientId.toString(),
                        "projectId" to (task.projectId?.toString() ?: ""),
                        "createdAt" to task.createdAt.toString(),
                        "priorityScore" to (task.priorityScore?.toString() ?: "50"),
                        "processingMode" to task.processingMode.name,
                        "parentTaskId" to (task.parentTaskId?.toString() ?: ""),
                        "phase" to (task.phase ?: ""),
                        "estimatedComplexity" to (task.estimatedComplexity ?: ""),
                    ))
                }
            }

            call.respondText(
                Json.encodeToString(tasks),
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=tasks/queue" }
            call.respondText("[]", ContentType.Application.Json)
        }
    }

    // Retry a failed (ERROR) task — resets state to QUEUED for re-processing
    post("/internal/tasks/{taskId}/retry") {
        try {
            val taskIdStr = call.parameters["taskId"] ?: ""
            val taskId = TaskId(ObjectId(taskIdStr))
            val task = taskRepository.getById(taskId)
                ?: return@post call.respondText(
                    """{"ok":false,"error":"Task not found"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )

            if (task.state != TaskStateEnum.ERROR) {
                return@post call.respondText(
                    """{"ok":false,"error":"Task is not in ERROR state (current: ${task.state.name})"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            }

            // Extract original content from error-wrapped content (failAndEscalateToUserTask wraps it)
            // Use lastIndexOf to handle double-wrapped content from multiple failures
            val originalContent = task.content.let { content ->
                val marker = "Obsah úlohy:\n"
                val idx = content.lastIndexOf(marker)
                if (idx >= 0) content.substring(idx + marker.length).trim() else content
            }

            // Strip error prefix from taskName (e.g., "Typ selhalo: error msg" → original name)
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
            logger.info("TASK_RETRY | taskId=$taskIdStr | title=${updated.taskName} | previousError=${task.errorMessage?.take(100)}")
            call.respondText(
                """{"ok":true,"taskId":"$taskIdStr","state":"QUEUED"}""",
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=tasks/retry | taskId=${call.parameters["taskId"]}" }
            call.respondText(
                """{"ok":false,"error":"${e.message?.replace("\"", "\\\"")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // Cancel a task — force state to DONE regardless of current state
    post("/internal/tasks/{taskId}/cancel") {
        try {
            val taskIdStr = call.parameters["taskId"] ?: ""
            val taskId = TaskId(ObjectId(taskIdStr))
            val task = taskRepository.getById(taskId)
                ?: return@post call.respondText(
                    """{"ok":false,"error":"Task not found"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )

            if (task.state == TaskStateEnum.DONE) {
                return@post call.respondText(
                    """{"ok":true,"taskId":"$taskIdStr","state":"DONE","message":"Already done"}""",
                    ContentType.Application.Json,
                )
            }

            val updated = task.copy(state = TaskStateEnum.DONE)
            taskRepository.save(updated)
            logger.info("TASK_CANCELLED | taskId=$taskIdStr | previousState=${task.state} | title=${task.taskName}")
            call.respondText(
                """{"ok":true,"taskId":"$taskIdStr","state":"DONE","previousState":"${task.state}"}""",
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=tasks/cancel | taskId=${call.parameters["taskId"]}" }
            call.respondText(
                """{"ok":false,"error":"${e.message?.replace("\"", "\\\"")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    // Set priority score for a task — for graph agent LLM prioritization
    post("/internal/tasks/{taskId}/priority") {
        try {
            val taskIdStr = call.parameters["taskId"] ?: ""
            val body = call.receive<InternalSetPriorityRequest>()
            val taskId = TaskId(ObjectId(taskIdStr))
            val task = taskRepository.getById(taskId)
                ?: return@post call.respondText(
                    """{"ok":false,"error":"Task not found"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )

            val updated = task.copy(
                priorityScore = body.priorityScore.coerceIn(0, 100),
            )
            taskRepository.save(updated)
            logger.info("TASK_PRIORITY_SET | taskId=$taskIdStr | score=${body.priorityScore} | title=${task.taskName}")
            call.respondText(
                """{"ok":true,"taskId":"$taskIdStr","priorityScore":${updated.priorityScore}}""",
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=tasks/priority | taskId=${call.parameters["taskId"]}" }
            call.respondText(
                """{"ok":false,"error":"${e.message?.replace("\"", "\\\"")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }
}

private fun parseSince(since: String): Instant = when (since) {
    "today" -> Instant.now().atZone(ZoneId.systemDefault())
        .toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant()
    "this_week" -> Instant.now().minus(Duration.ofDays(7))
    "this_month" -> Instant.now().minus(Duration.ofDays(30))
    else -> Instant.now().atZone(ZoneId.systemDefault())
        .toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant()
}

// --- DTOs for internal task endpoints ---

@Serializable
data class InternalCreateTaskRequest(
    val query: String? = null,
    val clientId: String,
    val projectId: String? = null,
    val title: String? = null,
    val description: String? = null,
    val schedule: String? = null,
    val daysOffset: Int? = null,
    val scheduledAt: String? = null,
    val createdBy: String? = null,
    val metadata: Map<String, String>? = null,
    val priority: Int? = null,
    val skipIndexing: Boolean? = null,
)

@Serializable
data class InternalCreateWorkPlanRequest(
    val title: String,
    val phases: List<WorkPlanPhase>,
    val clientId: String,
    val projectId: String? = null,
)

@Serializable
data class WorkPlanPhase(
    val name: String,
    val tasks: List<WorkPlanTask>,
)

@Serializable
data class WorkPlanTask(
    val title: String,
    val description: String,
    val actionType: String? = null,
    val dependsOn: List<String>? = null,
)

@Serializable
data class InternalRespondToTaskRequest(
    val response: String,
)

@Serializable
data class InternalSetPriorityRequest(
    val priorityScore: Int,
)
