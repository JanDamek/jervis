package com.jervis.entity.connection

/**
 * Connection state lifecycle.
 *
 * State transitions:
 * - NEW: Connection created but not yet assigned to any client/project
 * - VALID: Connection assigned and successfully validated (can connect)
 * - INVALID: Connection assigned but validation failed (couldn't connect)
 *
 * Only VALID connections are used by CentralPoller for indexing.
 * INVALID connections trigger UserTask creation for manual fix.
 */
enum class ConnectionStateEnum {
    /**
     * Connection created but not yet assigned to any client/project.
     * Not eligible for polling.
     */
    NEW,

    /**
     * Connection assigned but validation failed (couldn't connect).
     * CentralPoller creates UserTask for manual fix.
     */
    INVALID,

    /**
     * Connection assigned and successfully validated.
     * Eligible for polling and indexing.
     */
    VALID,
}
