package com.jervis.dto.user

import kotlinx.serialization.Serializable

/**
 * Defines how a user task should be routed when sent to processing
 */
@Serializable
enum class TaskRoutingMode {
    /**
     * Send directly to agent orchestrator for immediate processing
     * Agent starts working immediately, results appear in main window
     */
    DIRECT_TO_AGENT,

    /**
     * Return to pending queue with user's additional context
     * Task waits for GPU/scheduler to pick it up later
     */
    BACK_TO_PENDING
}
