package com.jervis.service.notification

import com.jervis.dto.monitoring.IndexingProgressEventDto
import com.jervis.service.indexing.monitoring.IndexingProgressUpdate
import com.jervis.service.websocket.WebSocketChannelType
import com.jervis.service.websocket.WebSocketSessionManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Publishes indexing progress notifications to WebSocket clients.
 * Listens for internal IndexingProgressUpdate events and broadcasts
 * IndexingProgressEventDto messages over the NOTIFICATIONS channel.
 */
@Component
class IndexingNotificationsPublisher(
    private val sessionManager: WebSocketSessionManager,
) {
    private val json = Json { encodeDefaults = true }

    @EventListener
    fun onIndexingProgress(event: IndexingProgressUpdate) {
        val dto =
            IndexingProgressEventDto(
                projectId = event.projectId.toHexString(),
                projectName = event.projectName,
                stepType = event.stepType,
                status = event.status,
                progress = event.progress,
                message = event.message,
                errorMessage = event.errorMessage,
                timestamp = event.timestamp.toString(),
                logs = event.logs,
            )
        sessionManager.broadcastToChannel(json.encodeToString(dto), WebSocketChannelType.NOTIFICATIONS)
    }
}
