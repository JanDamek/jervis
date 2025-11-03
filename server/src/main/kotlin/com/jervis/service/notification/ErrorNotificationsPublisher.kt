package com.jervis.service.notification

import com.jervis.dto.events.ErrorNotificationEventDto
import com.jervis.service.websocket.WebSocketChannelType
import com.jervis.service.websocket.WebSocketSessionManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class ErrorNotificationsPublisher(
    private val sessionManager: WebSocketSessionManager,
) {
    private val json = Json { encodeDefaults = true }

    fun publishError(
        message: String,
        stackTrace: String? = null,
        correlationId: String? = null,
    ) {
        val dto =
            ErrorNotificationEventDto(
                message = message,
                stackTrace = stackTrace,
                correlationId = correlationId,
                timestamp = Instant.now().toString(),
            )
        val payload = json.encodeToString(dto)
        sessionManager.broadcastToChannel(payload, WebSocketChannelType.NOTIFICATIONS)
    }
}
