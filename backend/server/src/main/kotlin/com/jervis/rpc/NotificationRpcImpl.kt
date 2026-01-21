package com.jervis.rpc

import com.jervis.dto.ErrorNotificationDto
import com.jervis.dto.events.JervisEvent
import com.jervis.dto.events.UserDialogResponseEventDto
import com.jervis.service.INotificationService
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Component
class NotificationRpcImpl : INotificationService {
    private val logger = KotlinLogging.logger {}

    // Store notification streams per client
    private val eventStreams = ConcurrentHashMap<String, MutableSharedFlow<JervisEvent>>()

    override fun subscribeToEvents(clientId: String): Flow<JervisEvent> {
        logger.info { "Client subscribing to event stream: $clientId" }

        val sharedFlow = eventStreams.getOrPut(clientId) {
            MutableSharedFlow(
                replay = 10,
                extraBufferCapacity = 100,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
        }

        return sharedFlow.asSharedFlow()
    }

    override suspend fun sendDialogResponse(response: UserDialogResponseEventDto) {
        logger.info { "Received dialog response: ${response.dialogId}" }
        // TODO: Propojit s orchestrátorem nebo službou, která na dialog čeká
    }

    override suspend fun closeDialog(clientId: String, dialogId: String, correlationId: String, reason: String) {
        logger.info { "Closing dialog: $dialogId for client $clientId" }
        emitEvent(clientId, JervisEvent.UserDialogClose(
            dialogId = dialogId,
            correlationId = correlationId,
            reason = reason,
            timestamp = Instant.now().toString()
        ))
    }

    /**
     * Internal method to emit errors to client streams.
     * Called by ErrorLogService when errors are recorded.
     */
    suspend fun emitError(clientId: String, error: ErrorNotificationDto) {
        logger.debug { "Emitting error to client $clientId: ${error.message}" }

        // Emit to event stream
        emitEvent(clientId, JervisEvent.ErrorNotification(
            id = error.id,
            severity = error.severity,
            message = error.message,
            clientId = error.clientId,
            projectId = error.projectId,
            timestamp = error.timestamp
        ))
    }

    /**
     * Emit a generic event to a client.
     */
    suspend fun emitEvent(clientId: String, event: JervisEvent) {
        logger.debug { "Emitting event to client $clientId: ${event::class.simpleName}" }
        eventStreams[clientId]?.emit(event)
    }
}
