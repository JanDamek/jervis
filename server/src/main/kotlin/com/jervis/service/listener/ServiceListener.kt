package com.jervis.service.listener

import com.jervis.domain.authentication.ServiceTypeEnum
import com.jervis.service.listener.domain.ListenerPollResult
import java.time.Instant

/**
 * Base interface for all service listeners
 */
interface ServiceListener {
    val serviceTypeEnum: ServiceTypeEnum

    /**
     * Poll the service for new messages since the last check
     */
    suspend fun poll(
        credentials: ServiceCredentials,
        lastCheckTime: Instant?,
    ): ListenerPollResult

    /**
     * Verify that credentials are valid and working
     */
    suspend fun verifyCredentials(credentials: ServiceCredentials): Boolean

    /**
     * Check if this listener supports webhook-based notifications
     */
    fun supportsWebhooks(): Boolean = false

    /**
     * Register a webhook if supported
     */
    suspend fun registerWebhook(
        credentials: ServiceCredentials,
        webhookUrl: String,
    ): String? = null

    /**
     * Handle a webhook event if supported
     */
    suspend fun handleWebhookEvent(
        credentials: ServiceCredentials,
        payload: String,
    ): ListenerPollResult? = null
}
