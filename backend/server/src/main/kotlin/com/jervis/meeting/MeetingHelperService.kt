package com.jervis.meeting

import com.jervis.dto.events.JervisEvent
import com.jervis.dto.meeting.DeviceInfoDto
import com.jervis.dto.meeting.HelperMessageDto
import com.jervis.dto.meeting.HelperMessageType
import com.jervis.dto.meeting.HelperSessionDto
import com.jervis.dto.meeting.HelperSessionStartDto
import com.jervis.preferences.DeviceTokenRepository
import com.jervis.preferences.DeviceType
import com.jervis.rpc.NotificationRpcImpl
import com.jervis.service.meeting.IMeetingHelperService
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Manages Meeting Helper sessions and WebSocket connections.
 *
 * Flow:
 * 1. Desktop starts recording with helper enabled → calls startHelper(meetingId, deviceId)
 * 2. Target device connects via WebSocket /ws/meeting-helper/{meetingId}
 * 3. Python orchestrator processes transcript chunks → POSTs helper messages to internal API
 * 4. This service broadcasts messages to connected WebSocket sessions
 */
@Component
class MeetingHelperService(
    private val deviceTokenRepository: DeviceTokenRepository,
    private val notificationRpc: NotificationRpcImpl,
) : IMeetingHelperService {

    /** Active helper sessions: meetingId → session info */
    private val sessions = ConcurrentHashMap<String, HelperSessionInfo>()

    /** WebSocket connections per meeting: meetingId → list of sessions */
    private val wsConnections = ConcurrentHashMap<String, MutableList<DefaultWebSocketSession>>()

    data class HelperSessionInfo(
        val meetingId: String,
        val deviceId: String,
        val sourceLang: String,
        val targetLang: String,
        var active: Boolean = true,
    )

    override suspend fun startHelper(request: HelperSessionStartDto): HelperSessionDto {
        val info = HelperSessionInfo(
            meetingId = request.meetingId,
            deviceId = request.deviceId,
            sourceLang = request.sourceLang,
            targetLang = request.targetLang,
        )
        sessions[request.meetingId] = info
        logger.info { "Meeting helper started: meeting=${request.meetingId}, device=${request.deviceId}, ${request.sourceLang}→${request.targetLang}" }

        // Push status message to connected device
        pushMessage(request.meetingId, HelperMessageDto(
            type = HelperMessageType.STATUS,
            text = "Asistence zahájena",
            timestamp = java.time.Instant.now().toString(),
        ))

        return info.toDto()
    }

    override suspend fun stopHelper(meetingId: String): Boolean {
        val session = sessions.remove(meetingId)
        if (session != null) {
            session.active = false
            // Notify connected devices
            pushMessage(meetingId, HelperMessageDto(
                type = HelperMessageType.STATUS,
                text = "Asistence ukončena",
                timestamp = java.time.Instant.now().toString(),
            ))
            logger.info { "Meeting helper stopped: meeting=$meetingId" }
            return true
        }
        return false
    }

    override suspend fun getHelperSession(meetingId: String): HelperSessionDto? {
        return sessions[meetingId]?.toDto()
    }

    override suspend fun listHelperDevices(): List<DeviceInfoDto> {
        // List all devices that have "helper" capability or are iPhones/iPads
        return deviceTokenRepository.findAll().toList()
            .filter { doc ->
                doc.capabilities.contains("helper") ||
                doc.deviceType in listOf(DeviceType.IPHONE, DeviceType.IPAD)
            }
            .map { doc ->
                DeviceInfoDto(
                    deviceId = doc.deviceId,
                    deviceName = doc.deviceName.ifBlank { doc.platform },
                    deviceType = doc.deviceType.name,
                    platform = doc.platform,
                    capabilities = doc.capabilities,
                    lastSeen = doc.lastSeen.toString(),
                )
            }
    }

    /** Register a WebSocket connection for a meeting helper session. */
    fun registerConnection(meetingId: String, session: DefaultWebSocketSession) {
        wsConnections.getOrPut(meetingId) { mutableListOf() }.add(session)
        logger.info { "WebSocket connected for meeting helper: meeting=$meetingId" }
    }

    /** Unregister a WebSocket connection. */
    fun unregisterConnection(meetingId: String, session: DefaultWebSocketSession) {
        wsConnections[meetingId]?.remove(session)
        if (wsConnections[meetingId]?.isEmpty() == true) {
            wsConnections.remove(meetingId)
        }
        logger.info { "WebSocket disconnected from meeting helper: meeting=$meetingId" }
    }

    /** Push a helper message to all connected WebSocket sessions for a meeting
     *  AND emit as a JervisEvent so desktop/mobile apps receive it via RPC event stream. */
    suspend fun pushMessage(meetingId: String, message: HelperMessageDto) {
        // Skip empty suggestion/prediction messages (non-actionable)
        if (message.text.isBlank() && message.type != HelperMessageType.STATUS) return

        // Emit via RPC event stream — works for all platforms (desktop, mobile) identically
        notificationRpc.emitMeetingHelperMessage(
            meetingId = meetingId,
            message = message,
        )

        // Also push via WebSocket for direct device connections (watches, secondary devices)
        val connections = wsConnections[meetingId]
        if (connections.isNullOrEmpty()) return

        val json = Json.encodeToString(message)
        val deadConnections = mutableListOf<DefaultWebSocketSession>()

        for (ws in connections) {
            try {
                ws.send(Frame.Text(json))
            } catch (e: Exception) {
                logger.debug { "Failed to push helper message to WebSocket: ${e.message}" }
                deadConnections.add(ws)
            }
        }

        // Clean up dead connections
        if (deadConnections.isNotEmpty()) {
            connections.removeAll(deadConnections)
        }
    }

    /** Get active session info (used by internal routing for Python orchestrator callbacks). */
    fun getActiveSession(meetingId: String): HelperSessionInfo? = sessions[meetingId]

    private fun HelperSessionInfo.toDto() = HelperSessionDto(
        meetingId = meetingId,
        deviceId = deviceId,
        sourceLang = sourceLang,
        targetLang = targetLang,
        active = active,
    )
}
