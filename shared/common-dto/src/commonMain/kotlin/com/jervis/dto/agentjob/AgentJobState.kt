package com.jervis.dto.agentjob

/**
 * Lifecycle state of an AgentJobRecord.
 *
 *   QUEUED → RUNNING → DONE          (happy path)
 *                   ↘  WAITING_USER → RUNNING → DONE
 *                   ↘  ERROR
 *                   ↘  CANCELLED
 *
 *  - QUEUED        — record created, K8s Job not yet dispatched (or
 *                    waiting for a free slot / locked workspace).
 *  - RUNNING       — K8s Job is alive, work in progress.
 *  - WAITING_USER  — Job is paused waiting for user input (approval,
 *                    clarification). The "K reakci" UI list pulls
 *                    records in this state.
 *  - DONE          — Terminal success, `resultSummary` populated.
 *  - ERROR         — Terminal failure, `errorMessage` populated.
 *  - CANCELLED     — User or orchestrator called abort before terminal.
 */
enum class AgentJobState {
    QUEUED,
    RUNNING,
    WAITING_USER,
    DONE,
    ERROR,
    CANCELLED;

    /**
     * Terminal states are the ones from which no further transition is
     * allowed. The watcher never re-opens a terminal record; abort on
     * a terminal record is a no-op.
     */
    fun isTerminal(): Boolean = this == DONE || this == ERROR || this == CANCELLED
}
