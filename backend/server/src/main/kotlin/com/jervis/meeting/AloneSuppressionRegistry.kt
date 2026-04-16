package com.jervis.meeting

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

// Tracks "keep Jervis alone in this meeting, suppress further alone-check
// pushes" decisions from the user. In-process; resets on server restart
// which is acceptable — the pod re-emits a fresh alone_check after its
// own internal timer.
//
// Two access paths on purpose:
//   - `AloneSuppressionRegistry` @Component — DI-friendly for the gRPC
//     impl and any future Spring-managed caller.
//   - `isAloneSuppressed(meetingId)` / `releaseAloneSuppression(meetingId)`
//     top-level helpers — used inside Ktor routing closures that run
//     outside Spring DI (push notify handler decides whether to swallow
//     a meeting_alone_check push).
//
// Both touch the same backing map so the registry's decisions are
// visible to the legacy helpers during the Phase 1 migration.
private val aloneSuppressionMap: MutableMap<String, Long> = ConcurrentHashMap()

fun isAloneSuppressed(meetingId: String): Boolean {
    val until = aloneSuppressionMap[meetingId] ?: return false
    if (System.currentTimeMillis() >= until) {
        aloneSuppressionMap.remove(meetingId)
        return false
    }
    return true
}

fun releaseAloneSuppression(meetingId: String) {
    aloneSuppressionMap.remove(meetingId)
}

fun suppressAlone(meetingId: String, minutes: Int) {
    val clamped = minutes.coerceIn(1, 180)
    aloneSuppressionMap[meetingId] = System.currentTimeMillis() + clamped * 60_000L
}

@Component
class AloneSuppressionRegistry {
    fun suppress(meetingId: String, minutes: Int) = suppressAlone(meetingId, minutes)
    fun release(meetingId: String) = releaseAloneSuppression(meetingId)
    fun isSuppressed(meetingId: String): Boolean = isAloneSuppressed(meetingId)
}
