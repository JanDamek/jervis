package com.jervis.service

import com.jervis.dto.events.JervisEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc

/**
 * Service for streaming notifications to UI clients.
 * All notifications are delivered via Flow for real-time updates.
 */
@Rpc
interface INotificationService {
    /**
     * Main event stream for all real-time notifications (replacing WebSocket).
     */
    fun subscribeToEvents(clientId: String): Flow<JervisEvent>
}
