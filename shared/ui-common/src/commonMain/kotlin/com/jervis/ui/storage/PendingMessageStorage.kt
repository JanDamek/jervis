package com.jervis.ui.storage

/**
 * Platform-specific storage for pending (unsent) chat messages.
 * Survives app restarts — message is retried after reconnect.
 */
expect object PendingMessageStorage {
    fun save(message: String?)
    fun load(): String?
}
