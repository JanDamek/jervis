package com.jervis.service.listener

import com.jervis.dto.ServiceType
import com.jervis.entity.mongo.ServiceCredentialsDocument
import org.bson.types.ObjectId
import java.time.Instant

/**
 * Represents a message or item retrieved from an external service
 */
data class ServiceMessage(
    val id: String,
    val serviceType: ServiceType,
    val clientId: ObjectId,
    val projectId: ObjectId?,
    val content: String,
    val author: String?,
    val timestamp: Instant,
    val metadata: Map<String, String> = emptyMap(),
    val threadId: String? = null,
    val channelId: String? = null,
    val attachments: List<ServiceAttachment> = emptyList(),
)

/**
 * Represents an attachment from an external service
 */
data class ServiceAttachment(
    val id: String,
    val name: String,
    val contentType: String?,
    val size: Long?,
    val url: String?,
)

/**
 * Result of a listener poll operation
 */
data class ListenerPollResult(
    val serviceType: ServiceType,
    val clientId: ObjectId,
    val projectId: ObjectId?,
    val newMessages: List<ServiceMessage>,
    val deletedMessageIds: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * Base interface for all service listeners
 */
interface ServiceListener {
    val serviceType: ServiceType

    /**
     * Poll the service for new messages since the last check
     */
    suspend fun poll(
        credentials: ServiceCredentialsDocument,
        lastCheckTime: Instant?,
    ): ListenerPollResult

    /**
     * Verify that credentials are valid and working
     */
    suspend fun verifyCredentials(credentials: ServiceCredentialsDocument): Boolean

    /**
     * Check if this listener supports webhook-based notifications
     */
    fun supportsWebhooks(): Boolean = false

    /**
     * Register a webhook if supported
     */
    suspend fun registerWebhook(
        credentials: ServiceCredentialsDocument,
        webhookUrl: String,
    ): String? = null

    /**
     * Handle a webhook event if supported
     */
    suspend fun handleWebhookEvent(
        credentials: ServiceCredentialsDocument,
        payload: String,
    ): ListenerPollResult? = null
}
