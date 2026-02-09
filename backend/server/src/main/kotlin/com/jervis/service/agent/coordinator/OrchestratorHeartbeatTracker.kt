package com.jervis.service.agent.coordinator

import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory tracker for orchestrator task heartbeats.
 * Updated every time a progress callback arrives from the Python orchestrator.
 * Used by BackgroundEngine to determine if a PYTHON_ORCHESTRATING task is still alive.
 *
 * Heartbeat-based liveness detection (same pattern as CorrectionHeartbeatTracker):
 * - Progress callbacks arrive on each LangGraph node transition
 * - If no heartbeat for HEARTBEAT_DEAD_THRESHOLD → task considered dead → reset for retry
 */
@Component
class OrchestratorHeartbeatTracker {
    private val lastProgressAt = ConcurrentHashMap<String, Instant>()

    fun updateHeartbeat(taskId: String) {
        lastProgressAt[taskId] = Instant.now()
    }

    fun getLastHeartbeat(taskId: String): Instant? = lastProgressAt[taskId]

    fun clearHeartbeat(taskId: String) {
        lastProgressAt.remove(taskId)
    }
}
