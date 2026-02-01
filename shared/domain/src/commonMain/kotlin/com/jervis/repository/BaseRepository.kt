package com.jervis.repository

import com.jervis.common.rpc.withRpcRetry
import com.jervis.common.rpc.withRpcFlowRetry
import com.jervis.di.NetworkModule
import kotlinx.coroutines.flow.Flow

/**
 * Base repository for RPC calls with reconnect-aware retries.
 * Uses StateFlow-based connection management for proper reconnection handling.
 */
abstract class BaseRepository {
    /**
     * Reconnect handler from NetworkModule
     */
    protected val reconnectHandler = NetworkModule.reconnectHandler

    /**
     * Execute RPC call with automatic reconnect-aware retry for network failures.
     * Non-network errors propagate immediately.
     */
    protected suspend fun <T> safeRpcCall(
        operation: String,
        maxRetries: Int = Int.MAX_VALUE,
        block: suspend () -> T,
    ): T =
        withRpcRetry(
            name = operation,
            maxRetries = maxRetries,
            reconnect = { reconnectHandler.reconnect() },
            block = block,
        )

    /**
     * Execute RPC call that returns a list.
     */
    protected suspend fun <T> safeRpcListCall(
        operation: String,
        maxRetries: Int = Int.MAX_VALUE,
        block: suspend () -> List<T>,
    ): List<T> = safeRpcCall(operation, maxRetries = maxRetries, block = block)
}
