package com.jervis.dto.agentjob

import kotlinx.serialization.Serializable

/**
 * Composite snapshot of the global agent job list — pushed by the server
 * over `IAgentJobRpc.subscribeAgentJobs`. The sidebar Background section
 * groups visually as Running / Queued / Recent (terminal within window).
 *
 *  - `running`  — state=RUNNING, sorted oldest first (longest-active at top
 *    matches "started X min ago" intuition).
 *  - `queued`   — state=QUEUED, sorted by createdAt asc (FIFO).
 *  - `waitingUser` — state=WAITING_USER, sorted by createdAt asc.
 *  - `recent`   — terminal records (DONE / ERROR / CANCELLED) within the
 *    requested window, newest first.
 *
 * Replay 1: the first emission is the current snapshot; every subsequent
 * emission is a fresh full snapshot triggered by an
 * `AgentJobStateChanged` push event. Sending a fresh full snapshot is
 * cheaper than diff bookkeeping at this UI cardinality (low tens at peak).
 */
@Serializable
data class AgentJobListSnapshot(
    val running: List<AgentJobSnapshot> = emptyList(),
    val queued: List<AgentJobSnapshot> = emptyList(),
    val waitingUser: List<AgentJobSnapshot> = emptyList(),
    val recent: List<AgentJobSnapshot> = emptyList(),
    /** `Instant.now().toString()` of the moment the server built this snapshot. */
    val snapshotAt: String,
)
