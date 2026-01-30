package com.jervis.common.rpc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen

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
 * Robust handling for kRPC Flows.
 * Automatically reconnects and retries the flow if the connection is lost.
 * 
 * IMPORTANT: When RpcClient is cancelled, we MUST NOT propagate the exception
 * after stopping retry, as this creates an infinite retry loop at higher levels.
 * Instead, the flow should complete gracefully and let the UI reconnect handler
 * create a NEW RpcClient instance.
 */
fun <T> withRpcFlowRetry(
    name: String = "Flow",
    reconnect: (suspend () -> Unit)? = null,
    flowProvider: () -> Flow<T>
): Flow<T> {
    return flowProvider()
        .retryWhen { cause, attempt ->
            val message = cause.message ?: ""
            if (cause is CancellationException) return@retryWhen false

            if (isRetryableRpcError(message)) {
                // Special handling for RPC cancellation - trigger reconnect and stop retry
                val isRpcCancelled = message.contains("RpcClient was cancelled", ignoreCase = true) ||
                                    message.contains("Client cancelled", ignoreCase = true)
                if (isRpcCancelled && attempt >= 2) {
                    println("$name flow: RPC client cancelled after ${attempt + 1} attempts, stopping retry")
                    // Trigger full reconnect to create NEW RpcClient (this will update all service stubs)
                    if (reconnect != null) {
                        try {
                            println("$name: Triggering NetworkModule.reconnect() to create new RpcClient")
                            reconnect()
                            // Wait a moment for reconnection to complete
                            delay(500)
                            // After successful reconnect, retry the flow ONE more time with fresh client
                            println("$name: Reconnection completed, retrying flow with new RpcClient")
                            return@retryWhen true
                        } catch (e: Exception) {
                            println("$name: Reconnect failed: ${e.message}, stopping flow")
                            return@retryWhen false
                        }
                    }
                    return@retryWhen false // Stop retry if no reconnect handler
                }

                println("$name flow lost (attempt ${attempt + 1}), attempting reconnect and retry...")

                if (reconnect != null && (attempt % 5 == 0L)) {
                    try {
                        reconnect()
                    } catch (e: Exception) {
                        println("Failed to reconnect $name flow: ${e.message}")
                    }
                }

                val delayMs = when {
                    attempt < 3 -> 1000L
                    attempt < 10 -> 2000L
                    else -> 5000L
                }
                delay(delayMs)
                true // Retry the flow
            } else {
                false // Other errors propagate
            }
        }
        .catch { cause ->
            if (cause is CancellationException) throw cause
            
            // CRITICAL FIX: Do NOT propagate RpcClient cancellation after retry stopped
            // This would create infinite retry loop at higher levels
            val message = cause.message ?: ""
            val isRpcCancelled = message.contains("RpcClient was cancelled", ignoreCase = true)
            if (isRpcCancelled) {
                println("Flow $name completed after RPC client cancellation (not propagating exception)")
                // Complete normally - reconnect handler will create new client
                return@catch
            }
            
            // For other errors, propagate normally
            println("Flow $name finally failed: ${cause.message}")
            throw cause
        }
}
