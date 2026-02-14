package com.jervis.ui.model

/**
 * Classified send error for pending message retry logic.
 * Network errors are retryable (auto-retry with backoff), server errors are not.
 */
sealed class SendError {
    data class Network(val message: String) : SendError()
    data class Server(val statusCode: Int?, val message: String) : SendError()

    val isRetryable: Boolean get() = this is Network

    val displayMessage: String
        get() = when (this) {
            is Network -> "Chyba sítě: $message"
            is Server -> "Chyba serveru${statusCode?.let { " ($it)" } ?: ""}: $message"
        }
}

/**
 * UI model for pending message banner display.
 */
data class PendingMessageInfo(
    val text: String,
    val attemptCount: Int,
    val isAutoRetrying: Boolean,
    val nextRetryInSeconds: Int?,
    val errorMessage: String?,
    val isRetryable: Boolean,
)

/**
 * Classify exception into retryable (network) vs non-retryable (server) error.
 * Uses message-based heuristics since kRPC wraps exceptions.
 */
fun classifySendError(e: Exception): SendError {
    val msg = e.message ?: "Neznámá chyba"
    val msgLower = msg.lowercase()

    return when {
        msgLower.contains("connection refused") -> SendError.Network("Připojení odmítnuto")
        msgLower.contains("connection reset") -> SendError.Network("Připojení přerušeno")
        msgLower.contains("timeout") -> SendError.Network("Časový limit vypršel")
        msgLower.contains("unreachable") -> SendError.Network("Server nedostupný")
        msgLower.contains("no route to host") -> SendError.Network("Server nedostupný")
        msgLower.contains("socket") -> SendError.Network("Chyba sítě")
        msgLower.contains("broken pipe") -> SendError.Network("Připojení přerušeno")
        msgLower.contains("eof") -> SendError.Network("Připojení přerušeno")
        msgLower.contains("closed") -> SendError.Network("Připojení uzavřeno")
        else -> SendError.Server(null, msg)
    }
}
