package com.jervis.dto.connection

import kotlinx.serialization.Serializable

/**
 * Connection state for DTOs.
 *
 * State lifecycle:
 * - NEW: Connection created but not yet assigned to any client/project
 * - VALID: Connection assigned and successfully validated (can connect)
 * - INVALID: Connection assigned but validation failed (couldn't connect)
 * - AUTH_EXPIRED: OAuth2 token expired or provider returned 401 — user must re-authorize
 */
@Serializable
enum class ConnectionStateEnum {
    NEW,
    INVALID,
    VALID,
    AUTH_EXPIRED,
}
