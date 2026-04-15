package com.jervis.rpc.internal

import com.jervis.common.types.TaskId
import com.jervis.dto.urgency.UrgencyConfigDto
import com.jervis.task.TaskRepository
import com.jervis.urgency.UrgencyConfigRpcImpl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.bson.types.ObjectId
import java.time.Instant

private val logger = KotlinLogging.logger {}
private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

/**
 * Internal REST endpoints for the Python orchestrator tools on top of `IUrgencyConfigRpc`.
 *
 * Auth: internal network only (K8s service, not ingress-exposed).
 *
 *   GET  /internal/urgency-config?clientId=...                 → UrgencyConfigDto
 *   PUT  /internal/urgency-config                              → UrgencyConfigDto (full replace)
 *   GET  /internal/urgency-presence?userId=...&platform=...    → UserPresenceDto
 *   POST /internal/urgency-bump-deadline                       → {"ok":true}
 */
fun Routing.installInternalUrgencyApi(
    urgencyConfig: UrgencyConfigRpcImpl,
    taskRepository: TaskRepository,
) {
    get("/internal/urgency-config") {
        try {
            val clientId = call.parameters["clientId"]
                ?: return@get call.respondText(
                    """{"error":"missing clientId"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            val dto = urgencyConfig.getUrgencyConfig(clientId)
            call.respondText(json.encodeToString(dto), ContentType.Application.Json)
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=urgency-config (GET)" }
            call.respondText(
                """{"error":"${e.message?.replace("\"", "\\\"")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    put("/internal/urgency-config") {
        try {
            val dto = json.decodeFromString<UrgencyConfigDto>(call.receive<String>())
            val saved = urgencyConfig.updateUrgencyConfig(dto)
            call.respondText(json.encodeToString(saved), ContentType.Application.Json)
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=urgency-config (PUT)" }
            call.respondText(
                """{"error":"${e.message?.replace("\"", "\\\"")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    get("/internal/urgency-presence") {
        try {
            val userId = call.parameters["userId"].orEmpty()
            val platform = call.parameters["platform"].orEmpty()
            val dto = urgencyConfig.getUserPresence(userId, platform)
            call.respondText(json.encodeToString(dto), ContentType.Application.Json)
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=urgency-presence (GET)" }
            call.respondText(
                """{"error":"${e.message?.replace("\"", "\\\"")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    post("/internal/urgency-bump-deadline") {
        try {
            val body = json.decodeFromString<BumpDeadlineBody>(call.receive<String>())
            val taskId = TaskId(ObjectId(body.taskId))
            val existing = taskRepository.findById(taskId)
                ?: return@post call.respondText(
                    """{"error":"task not found"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )
            val newDeadline = Instant.parse(body.deadlineIso)
            taskRepository.save(existing.copy(deadline = newDeadline))
            logger.info {
                "URGENCY_BUMP: task=${body.taskId} deadline=${body.deadlineIso} reason=${body.reason ?: "(none)"}"
            }
            call.respondText("""{"ok":true}""", ContentType.Application.Json)
        } catch (e: Exception) {
            logger.warn(e) { "INTERNAL_API_ERROR | endpoint=urgency-bump-deadline (POST)" }
            call.respondText(
                """{"error":"${e.message?.replace("\"", "\\\"")}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }
}

@Serializable
private data class BumpDeadlineBody(
    val taskId: String,
    val deadlineIso: String,
    val reason: String? = null,
)
