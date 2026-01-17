package com.jervis.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

/**
 * Base repository providing safe RPC call execution.
 * Catches all exceptions except CancellationException to prevent app crashes.
 */
abstract class BaseRepository {
    /**
     * Execute RPC call with error handling.
     * Catches all exceptions except CancellationException.
     * Logs error and returns null or throws based on returnNull parameter.
     */
    protected suspend fun <T> safeRpcCall(
        operation: String,
        returnNull: Boolean = false,
        block: suspend () -> T,
    ): T? =
        try {
            block()
        } catch (e: CancellationException) {
            // Always propagate cancellation
            throw e
        } catch (e: Exception) {
            println("RPC Error in $operation: ${e.message}")
            e.printStackTrace()
            if (returnNull) {
                null
            } else {
                throw e
            }
        }

    /**
     * Execute RPC call that returns a list, returning empty list on error.
     */
    protected suspend fun <T> safeRpcListCall(
        operation: String,
        block: suspend () -> List<T>,
    ): List<T> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("RPC Error in $operation: ${e.message}")
            e.printStackTrace()
            emptyList()
        }

    /**
     * Wrap Flow with error handling.
     * Catches all exceptions except CancellationException.
     * This prevents app crashes from RPC deserialization errors.
     */
    protected fun <T> Flow<T>.safeFlow(operation: String): Flow<T> =
        this.catch { e ->
            if (e is CancellationException) {
                throw e
            }
            println("Flow Error in $operation: ${e.message}")
            e.printStackTrace()
            // Complete the flow gracefully without emitting
        }
}
