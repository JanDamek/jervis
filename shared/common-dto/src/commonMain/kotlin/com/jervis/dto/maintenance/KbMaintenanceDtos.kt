package com.jervis.dto.maintenance

import kotlinx.serialization.Serializable

/**
 * EPIC 7: Proactive KB Maintenance & Learning DTOs.
 *
 * Supports idle task registry, vulnerability scanning, KB consistency checks,
 * learning engine, and documentation freshness monitoring.
 */

/**
 * Types of idle tasks that JERVIS can perform autonomously.
 */
@Serializable
enum class IdleTaskType {
    /** Check KB for duplicate/contradictory chunks. */
    KB_CONSISTENCY_CHECK,
    /** Scan project dependencies for known CVEs. */
    VULNERABILITY_SCAN,
    /** Basic static code quality analysis from KB data. */
    CODE_QUALITY_SCAN,
    /** Check if docs are stale relative to code changes. */
    DOCUMENTATION_FRESHNESS,
    /** Search for best practices relevant to project technologies. */
    LEARNING_BEST_PRACTICES,
    /** Generate daily activity report and post to Confluence. */
    DAILY_REPORT,
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
 * Result of a vulnerability scan for a single dependency.
 */
@Serializable
data class VulnerabilityFinding(
    val dependency: String,
    val version: String,
    val cveId: String? = null,
    val severity: VulnerabilitySeverity,
    val description: String,
    val fixVersion: String? = null,
    val projectId: String,
)

@Serializable
enum class VulnerabilitySeverity {
    LOW, MEDIUM, HIGH, CRITICAL,
}

/**
 * KB consistency check result.
 */
@Serializable
data class KbConsistencyFinding(
    val type: ConsistencyIssueType,
    val sourceUrn1: String,
    val sourceUrn2: String? = null,
    val description: String,
    val suggestedAction: String,
)

@Serializable
enum class ConsistencyIssueType {
    DUPLICATE_CHUNK,
    CONTRADICTORY_INFO,
    STALE_INFORMATION,
}

/**
 * Documentation freshness check result.
 */
@Serializable
data class DocFreshnessResult(
    val docPath: String,
    val lastDocUpdate: String,
    val lastCodeUpdate: String,
    val staleDays: Int,
    val affectedCodePaths: List<String> = emptyList(),
)

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
