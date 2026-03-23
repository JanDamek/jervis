package com.jervis.dto.maintenance

import kotlinx.serialization.Serializable

/**
 * EPIC 7: Proactive KB Maintenance & Learning DTOs.
 *
 * Supports idle task registry, vulnerability scanning, KB consistency checks,
 * learning engine, and documentation freshness monitoring.
 */

/**
 * Types of idle maintenance that JERVIS performs when GPU is idle.
 *
 * Focused on KB health — personal assistant needs clean, consistent knowledge.
 * Code quality, vulnerability scans etc. are handled by coding agents on demand.
 */
@Serializable
enum class IdleTaskType {
    /** Deduplicate similar KB nodes and chunks (cosine similarity > threshold). */
    KB_DEDUP,
    /** Remove orphaned nodes with no edges (disconnected from graph). */
    KB_ORPHAN_CLEANUP,
    /** Check KB for contradictions, stale refs, broken links. */
    KB_CONSISTENCY_CHECK,
    /** Thought Map decay — reduce activation scores over time. */
    THOUGHT_DECAY,
    /** Thought Map merge — merge highly similar ThoughtNodes. */
    THOUGHT_MERGE,
    /** Re-embed chunks with outdated embeddings (model version change). */
    EMBEDDING_QUALITY,
}

/**
 * Priority-ordered idle task configuration.
 */
@Serializable
data class IdleTaskConfig(
    val type: IdleTaskType,
    val enabled: Boolean = true,
    val priority: Int = 50,
    val intervalHours: Int = 24,
    val lastRunAt: String? = null,
)

/**
 * KB maintenance finding — issue found during idle maintenance.
 */
@Serializable
data class KbMaintenanceFinding(
    val type: MaintenanceFindingType,
    val nodeKey1: String,
    val nodeKey2: String? = null,
    val description: String,
    val autoFixed: Boolean = false,
)

@Serializable
enum class MaintenanceFindingType {
    DUPLICATE_NODE,
    DUPLICATE_CHUNK,
    ORPHANED_NODE,
    CONTRADICTORY_INFO,
    STALE_REFERENCE,
    LOW_QUALITY_EMBEDDING,
}

/**
 * Response from Python orchestrator's /maintenance/run endpoint.
 * Covers both Phase 1 (CPU-only cleanup) and Phase 2 (GPU-light KB maintenance).
 */
@Serializable
data class MaintenanceResultDto(
    val phase: Int = 1,
    @kotlinx.serialization.SerialName("mem_removed") val memRemoved: Int = 0,
    @kotlinx.serialization.SerialName("thinking_evicted") val thinkingEvicted: Int = 0,
    @kotlinx.serialization.SerialName("lqm_drained") val lqmDrained: Int = 0,
    @kotlinx.serialization.SerialName("affairs_archived") val affairsArchived: Int = 0,
    @kotlinx.serialization.SerialName("next_client_for_phase2") val nextClientForPhase2: String? = null,
    @kotlinx.serialization.SerialName("client_id") val clientId: String? = null,
    val findings: List<String> = emptyList(),
)
