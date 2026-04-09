package com.jervis.dto.connection

import kotlinx.serialization.Serializable

/**
 * Connection state for DTOs.
 *
 * State lifecycle:
 * - NEW: Connection created but not yet assigned to any client/project
 * - DISCOVERING: Browser pool is detecting available services (O365-specific)
 * - VALID: Connection assigned and successfully validated (can connect)
 * - INVALID: Connection assigned but validation failed (couldn't connect)
 * - AUTH_EXPIRED: OAuth2 token expired or provider returned 401 — user must re-authorize
 * - PAUSED: Connection temporarily disabled by user — CentralPoller skips it,
 *   but data is preserved so it can be re-enabled without re-configuration
 */
@Serializable
enum class ConnectionStateEnum {
    NEW,
    DISCOVERING,
    INVALID,
    VALID,
    AUTH_EXPIRED,
    PAUSED,
}
