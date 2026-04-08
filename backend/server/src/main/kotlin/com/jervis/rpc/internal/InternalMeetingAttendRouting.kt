package com.jervis.rpc.internal

import com.jervis.common.types.TaskId
import com.jervis.dto.task.TaskTypeEnum
import com.jervis.meeting.MeetingAttendApprovalService
import com.jervis.task.TaskRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mu.KotlinLogging
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Internal REST endpoints driving the meeting-attend approval flow from MCP / Python.
 *
 * - GET  /internal/meetings/upcoming  → list CALENDAR_PROCESSING tasks with meetingMetadata
 *                                        whose scheduledAt falls in the next N hours.
 * - POST /internal/meetings/attend/approve { taskId }            → approve via service
 * - POST /internal/meetings/attend/deny    { taskId, reason? }   → deny via service
 *
 * Approve/deny are routed through MeetingAttendApprovalService.handleApprovalResponse
 * so the queue entry, chat bubble, and notification cancel happen exactly the same
 * way as when the user taps the in-app dialog. There is no separate code path.
 */
fun Routing.installInternalMeetingAttendApi(
    taskRepository: TaskRepository,
    meetingAttendApprovalService: MeetingAttendApprovalService,
) {
    get("/internal/meetings/upcoming") {
        try {
            val hoursAhead = call.request.queryParameters["hours_ahead"]?.toLongOrNull() ?: 24L
            val clientId = call.request.queryParameters["client_id"]
            val projectId = call.request.queryParameters["project_id"]
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50

            val now = Instant.now()
            val window = now.plus(Duration.ofHours(hoursAhead))

            // Reuse the scheduler's existing index — type+state filter, scheduledAt ≤ window.
            // We then filter to (a) has meetingMetadata, (b) scheduledAt ≥ now, (c) optional client/project.
            val due = taskRepository.findByScheduledAtLessThanEqualAndTypeAndStateOrderByScheduledAtAsc(
                scheduledAt = window,
                type = TaskTypeEnum.CALENDAR_PROCESSING,
                state = com.jervis.dto.task.TaskStateEnum.NEW,
            )

            val items = mutableListOf<Map<String, String>>()
            due.collect { task ->
                val meta = task.meetingMetadata ?: return@collect
                if (task.scheduledAt == null || task.scheduledAt!!.isBefore(now)) return@collect
                if (clientId != null && task.clientId.toString() != clientId) return@collect
                if (projectId != null && task.projectId?.toString() != projectId) return@collect
                if (items.size >= limit) return@collect
                items += mapOf(
                    "taskId" to task.id.toString(),
                    "title" to task.taskName,
                    "clientId" to task.clientId.toString(),
                    "projectId" to (task.projectId?.toString() ?: ""),
                    "startTime" to meta.startTime.toString(),
                    "endTime" to meta.endTime.toString(),
                    "provider" to meta.provider.name,
                    "joinUrl" to (meta.joinUrl ?: ""),
                    "organizer" to (meta.organizer ?: ""),
                    "isRecurring" to meta.isRecurring.toString(),
                )
            }

            val json = buildJsonArray {
                for (m in items) add(buildJsonObject { m.forEach { (k, v) -> put(k, v) } })
            }
            call.respondText(json.toString(), ContentType.Application.Json)
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=meetings/upcoming" }
            call.respondText("""{"error":"${e.message}"}""", ContentType.Application.Json, HttpStatusCode.InternalServerError)
        }
    }

    post("/internal/meetings/attend/approve") {
        try {
            val body = call.receive<MeetingAttendDecisionRequest>()
            val task = taskRepository.getById(TaskId.fromString(body.taskId))
                ?: run {
                    call.respondText("""{"error":"Task not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
                    return@post
                }
            if (task.type != TaskTypeEnum.CALENDAR_PROCESSING || task.meetingMetadata == null) {
                call.respondText(
                    """{"error":"Task is not a meeting-attend task"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }
            val updated = meetingAttendApprovalService.handleApprovalResponse(
                task = task,
                approved = true,
                reason = null,
            )
            call.respondText(
                buildJsonObject {
                    put("taskId", updated.id.toString())
                    put("status", "APPROVED")
                    put("state", updated.state.name)
                }.toString(),
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=meetings/attend/approve" }
            call.respondText("""{"error":"${e.message}"}""", ContentType.Application.Json, HttpStatusCode.InternalServerError)
        }
    }

    post("/internal/meetings/attend/deny") {
        try {
            val body = call.receive<MeetingAttendDecisionRequest>()
            val task = taskRepository.getById(TaskId.fromString(body.taskId))
                ?: run {
                    call.respondText("""{"error":"Task not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
                    return@post
                }
            if (task.type != TaskTypeEnum.CALENDAR_PROCESSING || task.meetingMetadata == null) {
                call.respondText(
                    """{"error":"Task is not a meeting-attend task"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }
            val updated = meetingAttendApprovalService.handleApprovalResponse(
                task = task,
                approved = false,
                reason = body.reason,
            )
            call.respondText(
                buildJsonObject {
                    put("taskId", updated.id.toString())
                    put("status", "DENIED")
                    put("state", updated.state.name)
                    body.reason?.let { put("reason", it) }
                }.toString(),
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=meetings/attend/deny" }
            call.respondText("""{"error":"${e.message}"}""", ContentType.Application.Json, HttpStatusCode.InternalServerError)
        }
    }
}

@Serializable
private data class MeetingAttendDecisionRequest(
    val taskId: String,
    val reason: String? = null,
)
