package com.jervis.meeting

import com.jervis.dto.meeting.HelperMessageDto
import com.jervis.dto.meeting.HelperMessageType
import io.ktor.server.routing.Routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}
private val json = Json { ignoreUnknownKeys = true }

/**
 * WebSocket endpoint for Meeting Helper device connections.
 *
 * WebSocket: /ws/meeting-helper/{meetingId} — device connects to receive helper messages.
 * Python orchestrator pushes results via gRPC ServerMeetingHelperCallbacksService.
 */
fun Routing.installMeetingHelperApi(
    helperService: MeetingHelperService,
) {
    webSocket("/ws/meeting-helper/{meetingId}") {
        val meetingId = call.parameters["meetingId"]
        if (meetingId.isNullOrBlank()) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing meetingId"))
            return@webSocket
        }

        logger.info { "MEETING_HELPER_WS_CONNECT | meetingId=$meetingId" }
        helperService.registerConnection(meetingId, this)

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
            for (frame in incoming) {
                when (frame) {
                    is Frame.Text -> {
                        val text = frame.readText()
                        logger.debug { "MEETING_HELPER_WS_MSG | meetingId=$meetingId | $text" }
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
}
