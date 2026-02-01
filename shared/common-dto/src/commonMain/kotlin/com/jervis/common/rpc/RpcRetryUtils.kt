package com.jervis.common.rpc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.filter

/**
 * Executes an RPC call with retry logic for connection-loss errors.
 * Non-network errors propagate immediately.
 */
suspend fun <T> withRpcRetry(
    name: String = "RPC",
    maxRetries: Int = Int.MAX_VALUE,
    initialDelayMs: Long = 1000,
    maxDelayMs: Long = 30_000,
    reconnect: (suspend () -> Unit)? = null,
    block: suspend () -> T
): T {
    require(maxRetries > 0) { "maxRetries must be > 0" }

    var lastException: Exception? = null
    val cappedMaxDelay = maxDelayMs.coerceAtLeast(0)
    var currentDelay = initialDelayMs.coerceAtLeast(0)
    if (cappedMaxDelay > 0 && currentDelay > cappedMaxDelay) {
        currentDelay = cappedMaxDelay
    }
    var attempt = 0

    while (attempt < maxRetries) {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastException = e
            val message = e.message ?: ""
            
            if (!isRetryableRpcError(message)) {
                throw e
            } else {
                attempt += 1

                // Special handling for RPC cancellation - needs full client refresh
                val isRpcCancelled = message.contains("RpcClient was cancelled", ignoreCase = true)
                if (isRpcCancelled && attempt >= 3) {
                    println("$name: RPC client cancelled after $attempt attempts, needs full refresh")
                    throw e // Throw to trigger full reconnect at UI level
                }

                if (attempt < maxRetries) {
                    println("$name attempt $attempt failed with '$message', retrying in ${currentDelay}ms...")

                    if (reconnect != null && (attempt % 5 == 0)) {
                        try {
                            println("Attempting to reconnect $name...")
                            reconnect()
                        } catch (reconnectException: Exception) {
                            println("Failed to reconnect $name: ${reconnectException.message}")
                        }
                    }

                    if (currentDelay > 0) {
                        delay(currentDelay)
                    }
                    currentDelay = nextDelay(currentDelay, cappedMaxDelay)
                } else {
                    println("$name failed after $maxRetries attempts: $message")
                }
            }
        }
    }
    
    throw lastException ?: IllegalStateException("$name failed without exception")
}

    private fun isRetryableRpcError(message: String): Boolean {
        if (message.isBlank()) return true // Empty message is suspicious, better retry
        val lowered = message.lowercase()
        return lowered.contains("rpcclient was cancelled") ||
            lowered.contains("client cancelled") ||
            lowered.contains("channel was closed") ||
            lowered.contains("connection refused") ||
            lowered.contains("connection reset") ||
            lowered.contains("broken pipe") ||
            lowered.contains("failed to connect") ||
            lowered.contains("timed out") ||
            lowered.contains("timeout") ||
            lowered.contains("closed")
    }

private fun nextDelay(currentDelay: Long, maxDelay: Long): Long {
    if (maxDelay <= 0 || currentDelay <= 0) return 0
    return if (currentDelay >= maxDelay / 2) maxDelay else currentDelay * 2
}

/**
 * Robust handling for kRPC Flows using flatMapLatest pattern.
 * Automatically switches to new flow when RpcClient changes.
 *
 * This is the CORRECT way to handle kRPC reconnection:
 * 1. Subscribe to rpcClientState from NetworkModule
 * 2. When client changes (reconnect), automatically cancel old flow and start new one
 * 3. Use retryWhen for transient network errors within single client lifetime
 *
 * Based on kotlinx-rpc GitHub issue #100 community solution.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun <T> withRpcFlowRetry(
    name: String = "Flow",
    rpcClientState: kotlinx.coroutines.flow.StateFlow<Any?>,
    reconnect: (suspend () -> Unit)? = null,
    flowProvider: () -> Flow<T>
): Flow<T> {
    return rpcClientState
        .filter { it != null } // Wait for client to be available
        .flatMapLatest { client ->
            println("$name: Starting flow with RpcClient instance ${client.hashCode()}")

            flowProvider()
                .retryWhen { cause, attempt ->
                    val message = cause.message ?: ""
                    if (cause is CancellationException) {
                        println("$name: Flow cancelled (likely switching to new client)")
                        return@retryWhen false
                    }

                    if (isRetryableRpcError(message)) {
                        // Check if RPC client was cancelled - this means we need new client
                        val isRpcCancelled = message.contains("RpcClient was cancelled", ignoreCase = true) ||
                                            message.contains("Client cancelled", ignoreCase = true)

                        if (isRpcCancelled) {
                            println("$name: RPC client cancelled, triggering reconnect to get new client")
                            // Trigger reconnect - this will update rpcClientState
                            // flatMapLatest will automatically cancel this flow and start new one
                            if (reconnect != null) {
                                try {
                                    reconnect()
                                } catch (e: Exception) {
                                    println("$name: Reconnect failed: ${e.message}")
                                }
                            }
                            // Stop retrying this flow - flatMapLatest will start new one
                            return@retryWhen false
                        }

                        // Transient error - retry with backoff
                        val delayMs = when {
                            attempt < 3 -> 1000L
                            attempt < 10 -> 2000L
                            else -> 5000L
                        }
                        println("$name: Transient error (attempt ${attempt + 1}), retrying in ${delayMs}ms...")
                        delay(delayMs)
                        true // Retry the flow
                    } else {
                        println("$name: Non-retryable error: $message")
                        false // Other errors propagate
                    }
                }
                .catch { cause ->
                    if (cause is CancellationException) {
                        println("$name: Flow cancelled in catch block")
                        throw cause
                    }

                    val message = cause.message ?: ""
                    val isRpcCancelled = message.contains("RpcClient was cancelled", ignoreCase = true)
                    if (isRpcCancelled) {
                        println("$name: RPC client cancelled in catch, triggering reconnect")
                        // Trigger reconnect to update rpcClientState
                        reconnect?.let {
                            try {
                                it()
                            } catch (e: Exception) {
                                println("$name: Reconnect in catch failed: ${e.message}")
                            }
                        }
                        // Complete this flow - flatMapLatest will start new one
                        return@catch
                    }

                    // For other errors, propagate normally
                    println("$name: Flow failed with error: ${cause.message}")
                    throw cause
                }
        }
}
