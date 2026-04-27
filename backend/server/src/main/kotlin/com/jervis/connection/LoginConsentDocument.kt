package com.jervis.connection

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Global Login Consent Semaphore state — singleton document with `_id="GLOBAL"`.
 *
 * The user (one human) is the bottleneck for MFA approvals. To prevent
 * two consent push notifications from arriving simultaneously (one
 * could dismiss the other in the notification stack), we serialize the
 * entire login flow across ALL connections of ALL clients.
 *
 * Lifecycle:
 *   - Pod calls AcquireLoginConsent → server enqueues entry.
 *   - If `currentHolder` is null and queue head is available, server
 *     promotes head to AWAITING_CONSENT and pushes a notification with
 *     action buttons [Now / Defer 15min / Defer 1h / Cancel].
 *   - User taps action → `respond` endpoint updates state.
 *   - On "Now" → entry transitions to ACTIVE_LOGIN, pod proceeds.
 *   - On "Defer X" → entry returns to queue with `availableAt = now + X`,
 *     server promotes next available entry.
 *   - On "Cancel" → entry removed, pod gets `declined`.
 *   - Pod calls ReleaseLoginConsent after login completes (success/fail/expired).
 *   - Hold timeout: if pod doesn't release within 5 min from grant,
 *     server force-releases (assumes pod crashed).
 */
@Document(collection = "login_consent_state")
data class LoginConsentDocument(
    @Id
    val id: String = GLOBAL_ID,
    val currentHolder: Holder? = null,
    val queue: List<QueuedEntry> = emptyList(),
    val updatedAt: Instant = Instant.now(),
) {
    companion object {
        const val GLOBAL_ID = "GLOBAL"
    }

    /**
     * The single entry currently holding the semaphore.
     *
     * Two phases:
     *   - AWAITING_CONSENT: notification sent, waiting for user reply.
     *   - ACTIVE_LOGIN: user clicked "Now", pod is performing login flow.
     *
     * `expiresAt` is computed as `acquiredAt + 5 min` for ACTIVE_LOGIN,
     * `acquiredAt + 10 min` for AWAITING_CONSENT (gives user time to
     * react before re-promoting next).
     */
    data class Holder(
        val requestId: String,
        val connectionId: String,
        val label: String,
        val reason: String,
        val phase: Phase,
        val token: String,
        val acquiredAt: Instant,
        val expiresAt: Instant,
        val lastHeartbeatAt: Instant = acquiredAt,
        val notificationTaskId: String? = null,
    )

    enum class Phase {
        AWAITING_CONSENT, // push out, waiting for user action
        ACTIVE_LOGIN, // user said "Now", login flow running
    }

    data class QueuedEntry(
        val requestId: String,
        val connectionId: String,
        val label: String,
        val reason: String,
        val queuedAt: Instant,
        val availableAt: Instant = queuedAt, // bumped on Defer
        val deferCount: Int = 0,
    )
}
