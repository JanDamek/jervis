package com.jervis.di

/**
 * Thrown when an RPC operation is attempted while the app is offline.
 * Callers should catch this to gracefully degrade to cached/offline data,
 * or show a "Retry" banner and keep the message in the pending queue.
 */
class OfflineException(message: String = "App is offline") : Exception(message)

/**
 * Thrown when [RpcConnectionManager.awaitConnected] exceeds its timeout
 * waiting for a Connected state. Distinct from OfflineException so callers
 * can differentiate "never connected in bounded time" from "connection was
 * lost mid-call".
 */
class ConnectionTimeoutException(message: String = "Timed out waiting for connection") : Exception(message)

/**
 * True if the throwable indicates the underlying RPC transport is dead
 * and a reconnect is required.
 *
 * Walks the cause chain (up to 8 levels) and matches by:
 *  1. Exception class simpleName (primary)
 *  2. Class qualifiedName prefixes for known Ktor / kRPC packages
 *  3. IllegalStateException message heuristics (kRPC wraps cancellation as ISE)
 *  4. Message-based fallback for "Connection reset" / "Broken pipe"
 *
 * Conservative by design:
 *  - False positives trigger an unnecessary reconnect (acceptable — fast to recover).
 *  - False negatives cause the UI to hang (unacceptable — these are the real bugs).
 *
 * Explicitly EXCLUDED (callers must handle these as normal errors, not reconnect triggers):
 *  - HTTP 401 / 403 (auth errors) — should propagate to caller
 *  - HTTP 4xx server errors — real business errors
 *  - Validation / serialization errors from server responses
 */
fun isConnectionLost(t: Throwable): Boolean {
    var cur: Throwable? = t
    var depth = 0
    while (cur != null && depth < 8) {
        val name = cur::class.simpleName ?: ""
        val fqn = cur::class.qualifiedName ?: ""
        val msg = cur.message ?: ""

        // --- Type-based detection (primary, robust) ---
        when (name) {
            "ClosedReceiveChannelException",
            "ClosedSendChannelException",
            "ClosedChannelException",
            "WebSocketException",
            "EOFException",
            "ConnectException",
            "UnresolvedAddressException",
            "SocketException",
            "SocketTimeoutException",
            "HttpRequestTimeoutException",
            "ConnectTimeoutException",
            "NoTransformationFoundException",
            -> return true
        }

        // --- Package-based detection (catches Ktor / kRPC subtypes) ---
        if (fqn.startsWith("io.ktor.client.network.sockets.")) return true
        if (fqn.startsWith("io.ktor.websocket.")) return true
        if (fqn.startsWith("io.ktor.client.plugins.HttpRequestTimeoutException")) return true
        if (fqn.startsWith("kotlinx.rpc.") && name.contains("Cancel")) return true
        if (fqn.startsWith("kotlinx.coroutines.channels.Closed")) return true

        // --- Fallback: IllegalStateException message heuristics
        //     (kRPC wraps WebSocket close as IllegalStateException) ---
        if (cur is IllegalStateException) {
            if (msg.contains("cancelled", ignoreCase = true)) return true
            if (msg.contains("closed", ignoreCase = true)) return true
            if (msg.contains("RpcClient", ignoreCase = true)) return true
        }

        // --- Fallback: common OS-level messages ---
        if (msg.contains("Connection reset", ignoreCase = true)) return true
        if (msg.contains("Broken pipe", ignoreCase = true)) return true
        if (msg.contains("Connection refused", ignoreCase = true)) return true
        if (msg.contains("Network is unreachable", ignoreCase = true)) return true

        cur = cur.cause
        depth++
    }
    return false
}
