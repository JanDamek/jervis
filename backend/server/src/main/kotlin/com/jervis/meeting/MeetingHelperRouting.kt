package com.jervis.meeting

import com.jervis.dto.meeting.HelperMessageDto
import com.jervis.dto.meeting.HelperMessageType
import com.jervis.dto.meeting.HelperSessionStartDto
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

/**
 * WebSocket endpoint for Meeting Helper device connections + internal REST API.
 *
 * WebSocket: /ws/meeting-helper/{meetingId} — device connects to receive helper messages
 * Internal REST: /internal/meeting-helper/* — Python orchestrator pushes results
 */
fun Routing.installMeetingHelperApi(
    helperService: MeetingHelperService,
) {
    // ── WebSocket: Device connects to receive real-time helper messages ──
    webSocket("/ws/meeting-helper/{meetingId}") {
        val meetingId = call.parameters["meetingId"]
        if (meetingId.isNullOrBlank()) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing meetingId"))
            return@webSocket
        }

        logger.info { "MEETING_HELPER_WS_CONNECT | meetingId=$meetingId" }
        helperService.registerConnection(meetingId, this)

        // Check if there's an active session
        val session = helperService.getActiveSession(meetingId)
        if (session != null) {
            send(Frame.Text(json.encodeToString(HelperMessageDto(
                type = HelperMessageType.STATUS,
                text = "Připojeno k asistenci",
                fromLang = session.sourceLang,
                toLang = session.targetLang,
                timestamp = java.time.Instant.now().toString(),
            ))))
        }

        try {
            // Keep connection alive, listen for client pings/messages
            for (frame in incoming) {
                when (frame) {
                    is Frame.Text -> {
                        val text = frame.readText()
                        logger.debug { "MEETING_HELPER_WS_MSG | meetingId=$meetingId | $text" }
                        // Client can send ping or acknowledgements — no action needed
                    }
                    is Frame.Ping -> send(Frame.Pong(frame.data))
                    else -> {}
                }
            }
        } catch (e: Exception) {
            logger.debug { "MEETING_HELPER_WS_CLOSED | meetingId=$meetingId | ${e.message}" }
        } finally {
            helperService.unregisterConnection(meetingId, this)
            logger.info { "MEETING_HELPER_WS_DISCONNECT | meetingId=$meetingId" }
        }
    }

    // ── Internal REST: Python orchestrator pushes helper messages ──

    post("/internal/meeting-helper/push") {
        try {
            val body = call.receive<HelperPushRequest>()
            val message = HelperMessageDto(
                type = when (body.type) {
                    "translation" -> HelperMessageType.TRANSLATION
                    "suggestion" -> HelperMessageType.SUGGESTION
                    "question_predict" -> HelperMessageType.QUESTION_PREDICT
                    else -> HelperMessageType.STATUS
                },
                text = body.text,
                context = body.context,
                fromLang = body.fromLang,
                toLang = body.toLang,
                timestamp = body.timestamp,
            )
            helperService.pushMessage(body.meetingId, message)
            call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
        } catch (e: Exception) {
            logger.error(e) { "MEETING_HELPER_PUSH_ERROR" }
            call.respondText(
                """{"status":"error","error":"${e.message?.take(200)}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    post("/internal/meeting-helper/start") {
        try {
            val body = call.receive<HelperSessionStartDto>()
            val session = helperService.startHelper(body)
            call.respondText(
                json.encodeToString(session),
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.error(e) { "MEETING_HELPER_START_ERROR" }
            call.respondText(
                """{"status":"error","error":"${e.message?.take(200)}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    post("/internal/meeting-helper/stop") {
        try {
            val meetingId = call.parameters["meetingId"]
                ?: call.receive<HelperStopRequest>().meetingId
            val stopped = helperService.stopHelper(meetingId)
            call.respondText(
                """{"status":"ok","stopped":$stopped}""",
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            logger.error(e) { "MEETING_HELPER_STOP_ERROR" }
            call.respondText(
                """{"status":"error","error":"${e.message?.take(200)}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }
}

@Serializable
data class HelperPushRequest(
    val meetingId: String,
    val type: String,
    val text: String,
    val context: String = "",
    val fromLang: String = "",
    val toLang: String = "",
    val timestamp: String = "",
)

@Serializable
data class HelperStopRequest(
    val meetingId: String,
)
