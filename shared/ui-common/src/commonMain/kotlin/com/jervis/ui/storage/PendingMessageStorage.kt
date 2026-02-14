package com.jervis.ui.storage

import kotlin.time.Clock
import kotlinx.serialization.Serializable

/**
 * Persisted state for pending (unsent) chat messages.
 * Saved when send fails, cleared when server confirms delivery.
 */
@Serializable
data class PendingMessageState(
    val text: String,
    val clientMessageId: String,
    val timestampMs: Long,
    val attemptCount: Int = 0,
    val contextClientId: String? = null,
    val contextProjectId: String? = null,
    val lastErrorType: String? = null,
    val lastErrorMessage: String? = null,
)

private const val EXPIRY_MS = 24 * 60 * 60 * 1000L // 24 hours

fun PendingMessageState.isExpired(): Boolean =
    (Clock.System.now().toEpochMilliseconds() - timestampMs) > EXPIRY_MS

/**
 * Platform-specific storage for pending message state.
 * Survives app restarts — message is retried after reconnect.
 */
expect object PendingMessageStorage {
    fun save(state: PendingMessageState?)
    fun load(): PendingMessageState?
}
