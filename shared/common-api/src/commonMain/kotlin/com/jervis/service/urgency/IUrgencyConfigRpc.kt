package com.jervis.service.urgency

import com.jervis.dto.urgency.UrgencyConfigDto
import com.jervis.dto.urgency.UserPresenceDto
import kotlinx.rpc.annotations.Rpc

/**
 * RPC for per-client urgency configuration and presence lookup.
 *
 * Used by:
 *  - UI Settings "Urgency & Deadlines" tab (read + write config)
 *  - Orchestrator tools (update_urgency_config / get_urgency_config / get_user_presence)
 *
 * Config is per-client; if no document exists the backend returns an in-memory default
 * (does NOT auto-create — caller should call `updateUrgencyConfig` to persist).
 */
@Rpc
interface IUrgencyConfigRpc {
    /** Returns the stored config, or a default-valued DTO if none persisted yet. */
    suspend fun getUrgencyConfig(clientId: String): UrgencyConfigDto

    /** Upsert — full replace of the client's config document. */
    suspend fun updateUrgencyConfig(config: UrgencyConfigDto): UrgencyConfigDto

    /**
     * Look up the cached presence for a user on a given platform.
     * Returns Presence.UNKNOWN when no cache entry exists or it is stale
     * (older than `presenceTtlSeconds`).
     */
    suspend fun getUserPresence(userId: String, platform: String): UserPresenceDto
}
