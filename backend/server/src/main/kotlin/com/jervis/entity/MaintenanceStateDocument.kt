package com.jervis.entity

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Tracks progress of long-running KB maintenance tasks.
 *
 * Each maintenance type (dedup, orphan, consistency) has one document per client.
 * Supports resume after preemption — cursor marks where to continue.
 * Cooldown prevents re-running a completed cycle within 30 minutes.
 *
 * Flow:
 * 1. GPU idle → check maintenance_state for next work
 * 2. completedAt != null && (now - completedAt) < cooldownMinutes → skip
 * 3. cursor != null → resume from cursor (was preempted)
 * 4. cursor == null → start new cycle (next client in rotation)
 * 5. Process batch of N items, save cursor
 * 6. Preempted → cursor saved, resumes next idle
 * 7. Completed → completedAt = now, cursor = null
 */
@Document(collection = "maintenance_state")
data class MaintenanceStateDocument(
    /** Composite key: "{maintenanceType}:{clientId}" e.g. "dedup:68a330231b04695a243e5adb" */
    @Id
    val id: String,

    /** Maintenance type: DEDUP, ORPHAN_CLEANUP, CONSISTENCY_CHECK, THOUGHT_DECAY, EMBEDDING_QUALITY */
    val maintenanceType: MaintenanceType,

    /** Client being processed */
    @Indexed
    val clientId: String,

    /** Current phase within the maintenance type (e.g., "nodes", "edges", "chunks") */
    val phase: String? = null,

    /** Cursor for resume — last processed document ID or ArangoDB key */
    val cursor: String? = null,

    /** How many items processed in current cycle */
    val processedCount: Int = 0,

    /** Estimated total items to process (for progress reporting) */
    val totalCount: Int = 0,

    /** Number of issues found in current cycle */
    val findingsCount: Int = 0,

    /** Number of issues auto-fixed in current cycle */
    val fixedCount: Int = 0,

    /** When current cycle started */
    val startedAt: Instant? = null,

    /** When current cycle completed (null = in progress or not started) */
    val completedAt: Instant? = null,

    /** When the last full cycle completed (for cooldown check) */
    val lastFullCycleAt: Instant? = null,

    /** Cooldown in minutes — don't restart if completed within this window */
    val cooldownMinutes: Int = 30,

    /** Last error message (if maintenance failed) */
    val lastError: String? = null,
)

enum class MaintenanceType {
    /** Deduplicate similar KB nodes and chunks */
    DEDUP,
    /** Remove orphaned nodes with no edges */
    ORPHAN_CLEANUP,
    /** Check KB consistency — contradictions, stale refs */
    CONSISTENCY_CHECK,
    /** Thought Map decay — reduce activation scores over time */
    THOUGHT_DECAY,
    /** Thought Map merge — merge highly similar ThoughtNodes */
    THOUGHT_MERGE,
    /** Re-embed chunks with outdated or low-quality embeddings */
    EMBEDDING_QUALITY,
}
