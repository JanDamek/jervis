package com.jervis.repository

import com.jervis.dto.events.JervisEvent
import com.jervis.service.INotificationService
import kotlinx.coroutines.flow.Flow

/**
 * Notification Repository
 * Provides business logic layer over INotificationService
 */
class NotificationRepository(
    private val service: INotificationService,
) : BaseRepository() {
    /**
     * Subscribe to real-time events for a client
     */
    fun subscribeToEvents(clientId: String): Flow<JervisEvent> = 
        service.subscribeToEvents(clientId)
}
