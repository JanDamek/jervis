package com.jervis.rpc.internal

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.common.types.SourceUrn
import com.jervis.common.types.TaskId
import com.jervis.dto.TaskStateEnum
import com.jervis.dto.TaskTypeEnum
import com.jervis.repository.TaskRepository
import com.jervis.service.background.TaskService
import com.jervis.service.task.UserTaskService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
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
    // Create a background task — for chat tool create_background_task
    post("/internal/tasks/create") {
        try {
            val body = call.receive<InternalCreateTaskRequest>()
            val clientId = ClientId(ObjectId(body.clientId))
            val projectId = body.projectId?.let { ProjectId(ObjectId(it)) }
            val task = taskService.createTask(
                taskType = TaskTypeEnum.STANDARD,
                content = body.query,
                clientId = clientId,
                correlationId = "chat-tool-${java.util.UUID.randomUUID().toString().take(8)}",
                sourceUrn = SourceUrn("chat://foreground"),
                projectId = projectId,
                state = TaskStateEnum.READY_FOR_QUALIFICATION,
                taskName = body.query.take(100),
            )
            call.respondText(
                Json.encodeToString(mapOf(
                    "id" to task.id.toString(),
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

            val updated = task.copy(
                state = TaskStateEnum.READY_FOR_QUALIFICATION,
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
    val query: String,
    val clientId: String,
    val projectId: String? = null,
)

@Serializable
data class InternalRespondToTaskRequest(
    val response: String,
)
