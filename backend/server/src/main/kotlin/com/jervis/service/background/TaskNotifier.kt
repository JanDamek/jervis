package com.jervis.service.background

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.stereotype.Component

/**
 * Channel-based wake-up for BackgroundEngine execution loop.
 *
 * Avoids circular dependency: AgentOrchestratorRpcImpl -> TaskNotifier <- BackgroundEngine
 * When a new FOREGROUND task is created, notifyNewTask() wakes the execution loop immediately
 * instead of waiting for the idle poll interval.
 */
@Component
class TaskNotifier {
    private val channel = Channel<Unit>(Channel.CONFLATED)

    /** Signal that a new task is available. Non-blocking. */
    fun notifyNewTask() {
        channel.trySend(Unit)
    }

    /** Wait for a new task notification, or return after [timeoutMs]. Returns true if signalled. */
    suspend fun awaitTask(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { channel.receive(); true } ?: false
}
