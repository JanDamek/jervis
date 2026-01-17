package com.jervis.rpc

import com.jervis.dto.ErrorNotificationDto
import com.jervis.service.INotificationService
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
class NotificationRpcImpl : INotificationService {
    private val logger = KotlinLogging.logger {}

    // Store notification streams per client
    private val errorStreams = ConcurrentHashMap<String, MutableSharedFlow<ErrorNotificationDto>>()
    private val allStreams = ConcurrentHashMap<String, MutableSharedFlow<ErrorNotificationDto>>()

    override fun subscribeToErrors(clientId: String): Flow<ErrorNotificationDto> {
        logger.info { "Client subscribing to error stream: $clientId" }

        val sharedFlow = errorStreams.getOrPut(clientId) {
            MutableSharedFlow(
                replay = 10, // Keep last 10 errors for late subscribers
                extraBufferCapacity = 50,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
        }

        return sharedFlow.asSharedFlow()
    }

    override fun subscribeToAll(clientId: String): Flow<ErrorNotificationDto> {
        logger.info { "Client subscribing to all notifications: $clientId" }

        val sharedFlow = allStreams.getOrPut(clientId) {
            MutableSharedFlow(
                replay = 10, // Keep last 10 notifications
                extraBufferCapacity = 100,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
        }

        return sharedFlow.asSharedFlow()
    }

    /**
     * Internal method to emit errors to client streams.
     * Called by ErrorLogService when errors are recorded.
     */
    suspend fun emitError(clientId: String, error: ErrorNotificationDto) {
        logger.debug { "Emitting error to client $clientId: ${error.message}" }

        // Emit to error-only stream
        errorStreams[clientId]?.emit(error)

        // Emit to all notifications stream
        allStreams[clientId]?.emit(error)
    }
}
