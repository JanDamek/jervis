package com.jervis.service.meeting

import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory tracker for correction heartbeats.
 * Updated every time a correction progress callback arrives from the Python orchestrator.
 * Used by stuck detection (Pipeline 5) to determine if a CORRECTING meeting is still alive.
 */
@Component
class CorrectionHeartbeatTracker {
    private val lastProgressAt = ConcurrentHashMap<String, Instant>()

    fun updateHeartbeat(meetingId: String) {
        lastProgressAt[meetingId] = Instant.now()
    }

    fun getLastHeartbeat(meetingId: String): Instant? = lastProgressAt[meetingId]

    fun clearHeartbeat(meetingId: String) {
        lastProgressAt.remove(meetingId)
    }
}
