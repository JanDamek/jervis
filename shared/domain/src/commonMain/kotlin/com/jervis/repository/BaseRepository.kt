package com.jervis.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import kotlin.math.pow

/**
 * Base repository providing safe RPC call execution.
 * Catches all exceptions except CancellationException to prevent app crashes.
 */
abstract class BaseRepository {
    /**
     * Execute RPC call with error handling and automatic retry.
     * Catches all exceptions except CancellationException.
     * Logs error and returns null or throws based on returnNull parameter.
     */
    protected suspend fun <T> safeRpcCall(
        operation: String,
        returnNull: Boolean = false,
        maxRetries: Int = 3,
        block: suspend () -> T,
    ): T? {
        var lastException: Exception? = null
        for (attempt in 0..maxRetries) {
            try {
                return block()
            } catch (e: CancellationException) {
                // Always propagate cancellation
                throw e
            } catch (e: Exception) {
                lastException = e
                println("RPC Error in $operation (attempt ${attempt + 1}/$maxRetries): ${e.message}")
                if (attempt < maxRetries) {
                    val delayMs = (2.0.pow(attempt).toLong() * 1000L).coerceAtMost(5000L)
                    delay(delayMs)
                }
            }
        }

        lastException?.printStackTrace()
        if (returnNull) {
            return null
        } else {
            throw lastException ?: RuntimeException("Unknown error in $operation")
        }
    }

    /**
     * Execute RPC call that returns a list, returning empty list on error.
     */
    protected suspend fun <T> safeRpcListCall(
        operation: String,
        maxRetries: Int = 3,
        block: suspend () -> List<T>,
    ): List<T> =
        try {
            safeRpcCall(operation, returnNull = false, maxRetries = maxRetries, block = block) ?: emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("RPC List Error in $operation: ${e.message}")
            emptyList()
        }

    /**
     * Wrap Flow with error handling and automatic retry.
     * Catches all exceptions except CancellationException.
     * This prevents app crashes from RPC deserialization errors.
     */
    protected fun <T> Flow<T>.safeFlow(
        operation: String,
        maxRetries: Int = 10,
    ): Flow<T> =
        this.retryWhen { cause, attempt ->
            if (cause is CancellationException) return@retryWhen false
            if (attempt < maxRetries) {
                println("Flow Error in $operation (attempt ${attempt + 1}/$maxRetries): ${cause.message}. Retrying...")
                val delayMs = (2.0.pow(attempt.toInt()).toLong() * 1000L).coerceAtMost(30000L)
                delay(delayMs)
                true
            } else {
                println("Flow Error in $operation: Max retries reached: ${cause.message}")
                false
            }
        }.catch { e ->
            if (e is CancellationException) {
                throw e
            }
            println("Flow Error in $operation finally caught: ${e.message}")
            e.printStackTrace()
            // Complete the flow gracefully without emitting
        }
}
