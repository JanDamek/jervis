package com.jervis.dto.dashboard

import kotlinx.serialization.Serializable

/**
 * Snapshot of the orchestrator's SessionBroker state — read-only view
 * for the desktop Dashboard screen.
 *
 *  - [activeCount] / [cap] — current count of live Claude sessions and
 *    the LRU cap (eviction kicks in once `activeCount >= cap`).
 *  - [paused] — true when broker is back-pressured (Claude API rate
 *    limit). UI shows a warning banner.
 *  - [sessions] — per-session detail (scope, idle time, cumulative
 *    tokens, compact-in-progress flag, last compact age).
 *  - [agentJobHolds] — agent_job_id → holder_scope. Sessions that are
 *    pinned alive while a coding-agent K8s Job is in flight.
 *  - [recentEvictions] — last 24 h of LRU evictions, newest-first.
 *
 *  Pushed via `IDashboardService.subscribeSessionSnapshot` (replay=1).
 */
@Serializable
data class DashboardSnapshotDto(
    val activeCount: Int,
    val cap: Int,
    val paused: Boolean,
    val sessions: List<ActiveSessionDto>,
    val agentJobHolds: Map<String, String>,
    val recentEvictions: List<EvictionRecordDto>,
)

@Serializable
data class ActiveSessionDto(
    val scope: String,
    val sessionId: String,
    val clientId: String,
    val projectId: String,
    val cumulativeTokens: Int,
    val idleSeconds: Int,
    val compactInProgress: Boolean,
    val lastCompactAgeSeconds: Int,
)

@Serializable
data class EvictionRecordDto(
    val scope: String,
    val reason: String,
    /** ISO 8601 timestamp from the broker audit doc (`data.ts`). */
    val ts: String,
)
