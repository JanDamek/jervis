package com.jervis.rpc.internal

import com.jervis.meeting.BrowserPodMeetingClient
import com.jervis.meeting.MeetingRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mu.KotlinLogging
import org.bson.types.ObjectId
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Alone-in-meeting chat bubble endpoints (product §10a).
 *
 * - POST /internal/meetings/{meetingId}/leave  → orchestrator MCP
 *   `meeting_alone_leave`. Dispatches `/instruction/{connectionId} leave_meeting`
 *   to the pod; the agent's leave_meeting tool stops recording, clicks Leave,
 *   reports presence=false.
 *
 * - POST /internal/meetings/{meetingId}/stay  → orchestrator MCP
 *   `meeting_alone_stay`. Records a server-side suppression window so the
 *   pod's subsequent `meeting_alone_check` emissions get deduped for
 *   `suppressMinutes` (default 30).
 *
 * Suppression is kept in-process — it resets on server restart, which is
 * acceptable: the pod re-emits a fresh alone_check after its own
 * `O365_POOL_MEETING_USER_ALONE_NOTIFY_WAIT_MIN` window, and the fresh
 * notify is exactly what we want after a server bounce.
 */
fun Routing.installInternalMeetingAloneApi(
    meetingRepository: MeetingRepository,
    browserPodMeetingClient: BrowserPodMeetingClient,
) {
    post("/internal/meetings/{meetingId}/leave") {
        val raw = call.parameters["meetingId"].orEmpty()
        val meetingId = try {
            ObjectId(raw)
        } catch (_: Exception) {
            call.respondText(
                """{"error":"invalid meetingId"}""",
                ContentType.Application.Json, HttpStatusCode.BadRequest,
            )
            return@post
        }
        val body = try {
            call.receive<LeaveRequest>()
        } catch (_: Exception) {
            LeaveRequest()
        }
        val meeting = meetingRepository.findById(meetingId) ?: run {
            call.respondText(
                """{"error":"meeting not found"}""",
                ContentType.Application.Json, HttpStatusCode.NotFound,
            )
            return@post
        }
        // The MeetingDocument's clientId is a ClientId not ConnectionId, but
        // in the pod-recording case the agent uses connection_id==client_id
        // (per-connection pod). When a separate ConnectionId is stored on the
        // task metadata, prefer that; for now the pod resolves by clientId.
        val connectionId = meeting.clientId?.toString() ?: run {
            call.respondText(
                """{"error":"meeting has no clientId"}""",
                ContentType.Application.Json, HttpStatusCode.BadRequest,
            )
            return@post
        }
        val ok = browserPodMeetingClient.dispatchLeave(
            connectionId = connectionId,
            meetingId = raw,
            reason = body.reason.ifBlank { "user_asked_to_leave" },
        )
        aloneSuppression.remove(raw)
        call.respondText(
            buildJsonObject {
                put("meetingId", raw)
                put("state", if (ok) "DISPATCHED" else "FAILED")
                put("reason", body.reason.ifBlank { "user_asked_to_leave" })
            }.toString(),
            ContentType.Application.Json,
            if (ok) HttpStatusCode.OK else HttpStatusCode.BadGateway,
        )
    }

    post("/internal/meetings/{meetingId}/stay") {
        val raw = call.parameters["meetingId"].orEmpty()
        val body = try {
            call.receive<StayRequest>()
        } catch (_: Exception) {
            StayRequest()
        }
        val minutes = body.suppressMinutes.coerceIn(1, 180)
        val until = System.currentTimeMillis() + minutes * 60_000L
        aloneSuppression[raw] = until
        logger.info {
            "ALONE_STAY | meeting=$raw suppressMinutes=$minutes until=$until"
        }
        call.respondText(
            buildJsonObject {
                put("meetingId", raw)
                put("state", "SUPPRESSED")
                put("suppressMinutes", minutes)
            }.toString(),
            ContentType.Application.Json,
        )
    }
}

/**
 * In-process alone-check suppression. `notify_user(kind='meeting_alone_check')`
 * callers should consult `isAloneSuppressed(meetingId)` before delivering the
 * push to the user. (Wired in InternalO365SessionRouting notify handler.)
 */
val aloneSuppression: MutableMap<String, Long> = ConcurrentHashMap()

fun isAloneSuppressed(meetingId: String): Boolean {
    val until = aloneSuppression[meetingId] ?: return false
    if (System.currentTimeMillis() >= until) {
        aloneSuppression.remove(meetingId)
        return false
    }
    return true
}

@Serializable
private data class LeaveRequest(
    val reason: String = "",
)

@Serializable
private data class StayRequest(
    val suppressMinutes: Int = 30,
)
