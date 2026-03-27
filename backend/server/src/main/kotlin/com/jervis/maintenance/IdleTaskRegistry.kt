package com.jervis.maintenance

import com.jervis.dto.maintenance.IdleTaskConfig
import com.jervis.dto.maintenance.IdleTaskType
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Idle Maintenance Registry — KB-focused maintenance tasks.
 *
 * Priority-ordered list of maintenance activities JERVIS performs when GPU is idle.
 * All tasks are KB health focused — personal assistant needs clean, consistent knowledge.
 *
 * Each task processes in batches with checkpoint/cursor support:
 * - Preemptible: saves cursor when interrupted by FG/BG work
 * - Resumable: continues from cursor on next idle period
 * - Cooldown: doesn't restart if completed within last 30 minutes
 */
@Service
class IdleTaskRegistry {
    private val logger = KotlinLogging.logger {}

    /**
     * KB maintenance pipeline, ordered by priority (highest first).
     *
     * Pipeline runs top-to-bottom: finish dedup before starting orphan cleanup etc.
     * Each task has its own cooldown — after completing a full cycle, waits before re-running.
     */
    private val maintenancePipeline = listOf(
        IdleTaskConfig(
            type = IdleTaskType.THOUGHT_DECAY,
            enabled = true,
            priority = 100,         // Highest — fast CPU-only operation
            intervalHours = 6,      // Every 6 hours
        ),
        IdleTaskConfig(
            type = IdleTaskType.KB_DEDUP,
            enabled = true,
            priority = 90,
            intervalHours = 24,
        ),
        IdleTaskConfig(
            type = IdleTaskType.KB_ORPHAN_CLEANUP,
            enabled = true,
            priority = 80,
            intervalHours = 24,
        ),
        IdleTaskConfig(
            type = IdleTaskType.KB_CONSISTENCY_CHECK,
            enabled = true,
            priority = 70,
            intervalHours = 48,     // Every 2 days — heaviest task
        ),
        IdleTaskConfig(
            type = IdleTaskType.THOUGHT_MERGE,
            enabled = true,
            priority = 60,
            intervalHours = 24,
        ),
        IdleTaskConfig(
            type = IdleTaskType.EMBEDDING_QUALITY,
            enabled = true,
            priority = 50,          // Lowest — only when everything else is done
            intervalHours = 168,    // Weekly
        ),
    )

    /**
     * Get the next maintenance task to execute, respecting priority and cooldown.
     *
     * @param lastRunTimes Map of IdleTaskType → last completion timestamp (ISO)
     * @return Next task config to execute, or null if all tasks completed recently
     */
    fun getNextIdleTask(
        lastRunTimes: Map<IdleTaskType, String>,
    ): IdleTaskConfig? {
        val now = java.time.Instant.now()

        return maintenancePipeline
            .filter { it.enabled }
            .sortedByDescending { it.priority }
            .firstOrNull { config ->
                val lastRun = lastRunTimes[config.type]
                if (lastRun == null) {
                    true // Never run → eligible
                } else {
                    val lastRunInstant = java.time.Instant.parse(lastRun)
                    val nextRunAt = lastRunInstant.plusSeconds(config.intervalHours * 3600L)
                    now.isAfter(nextRunAt)
                }
            }
    }

    /**
     * Get all configured maintenance tasks (for UI display in settings).
     */
    fun getAllTasks(): List<IdleTaskConfig> = maintenancePipeline.toList()

    /**
     * Get task description for logging/reporting.
     */
    fun getTaskDescription(type: IdleTaskType): String = when (type) {
        IdleTaskType.KB_DEDUP -> "KB Dedup — merge similar nodes and chunks"
        IdleTaskType.KB_ORPHAN_CLEANUP -> "KB Orphan Cleanup — remove disconnected nodes"
        IdleTaskType.KB_CONSISTENCY_CHECK -> "KB Consistency — find contradictions and stale refs"
        IdleTaskType.THOUGHT_DECAY -> "Thought Map Decay — reduce old activation scores"
        IdleTaskType.THOUGHT_MERGE -> "Thought Map Merge — consolidate similar thoughts"
        IdleTaskType.EMBEDDING_QUALITY -> "Embedding Quality — re-embed outdated chunks"
    }
}
