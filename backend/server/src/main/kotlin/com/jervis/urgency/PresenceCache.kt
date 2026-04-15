package com.jervis.urgency

import com.jervis.dto.urgency.Presence
import com.jervis.dto.urgency.UserPresenceDto
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory presence cache across platforms (Slack/Teams/Discord).
 *
 * This is a **stub implementation**: real platform presence subscriptions (Slack Events API,
 * Graph `/me/presence`, Discord gateway) are a separate workstream. For now the cache
 * accepts writes from whoever has data and returns `UNKNOWN` on miss or when stale.
 *
 * TTL is read from `UrgencyConfigDocument.presenceTtlSeconds` by callers; this class just
 * stores a timestamp and exposes a raw snapshot.
 */
@Component
class PresenceCache {
    private val snapshots = ConcurrentHashMap<Key, PresenceSnapshot>()

    fun put(
        userId: String,
        platform: String,
        presence: Presence,
    ) {
        snapshots[Key(userId, platform)] =
            PresenceSnapshot(
                userId = userId,
                platform = platform,
                presence = presence,
                observedAtMillis = System.currentTimeMillis(),
            )
    }

    /**
     * Get a snapshot, or null if none recorded. Caller applies TTL based on urgency_config.
     */
    fun get(userId: String, platform: String): PresenceSnapshot? = snapshots[Key(userId, platform)]

    /** Converts a snapshot to DTO, applying TTL — returns UNKNOWN presence if stale. */
    fun getDto(
        userId: String,
        platform: String,
        ttlSeconds: Int,
    ): UserPresenceDto {
        val snap = get(userId, platform)
        val now = System.currentTimeMillis()
        val stale = snap == null || (now - snap.observedAtMillis) > ttlSeconds * 1000L
        val presence = if (stale) Presence.UNKNOWN else snap!!.presence
        val lastActive =
            snap?.observedAtMillis?.let { Instant.ofEpochMilli(it).toString() }
        return UserPresenceDto(
            userId = userId,
            platform = platform,
            presence = presence,
            lastActiveAtIso = lastActive,
        )
    }

    private data class Key(val userId: String, val platform: String)
}
