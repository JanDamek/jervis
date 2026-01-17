package com.jervis.service

import com.jervis.dto.ErrorNotificationDto
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc

/**
 * Service for streaming notifications to UI clients.
 * All notifications are delivered via Flow for real-time updates.
 */
@Rpc
interface INotificationService {
    /**
     * Subscribe to error notifications for a client.
     * Returns Flow of error events as they occur.
     */
    fun subscribeToErrors(clientId: String): Flow<ErrorNotificationDto>

    /**
     * Subscribe to all notifications for a client.
     * Includes errors, warnings, and info messages.
     */
    fun subscribeToAll(clientId: String): Flow<ErrorNotificationDto>
}
