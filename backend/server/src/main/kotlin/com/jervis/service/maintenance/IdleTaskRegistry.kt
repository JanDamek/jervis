package com.jervis.service.maintenance

import com.jervis.dto.maintenance.IdleTaskConfig
import com.jervis.dto.maintenance.IdleTaskType
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * EPIC 7-S1: Idle Tasks Registry.
 *
 * Manages the configurable list of idle activities JERVIS performs
 * when no foreground or background tasks are pending.
 *
 * Priority-ordered. The BackgroundEngine's idle review loop consults
 * this registry to pick the next idle task.
 */
@Service
class IdleTaskRegistry {
    private val logger = KotlinLogging.logger {}

    /**
     * Default idle task configuration, ordered by priority (highest first).
     * Can be overridden per-client via guidelines or DB config.
     */
    private val defaultTasks = listOf(
        IdleTaskConfig(
            type = IdleTaskType.REVIEW_BRAIN_ISSUES,
            enabled = true,
            priority = 100,
            intervalHours = 4,
        ),
        IdleTaskConfig(
            type = IdleTaskType.KB_CONSISTENCY_CHECK,
            enabled = true,
            priority = 80,
            intervalHours = 24,
        ),
        IdleTaskConfig(
            type = IdleTaskType.VULNERABILITY_SCAN,
            enabled = true,
            priority = 70,
            intervalHours = 24,
        ),
        IdleTaskConfig(
            type = IdleTaskType.CODE_QUALITY_SCAN,
            enabled = true,
            priority = 60,
            intervalHours = 48,
        ),
        IdleTaskConfig(
            type = IdleTaskType.DOCUMENTATION_FRESHNESS,
            enabled = true,
            priority = 50,
            intervalHours = 72,
        ),
        IdleTaskConfig(
            type = IdleTaskType.LEARNING_BEST_PRACTICES,
            enabled = true,
            priority = 40,
            intervalHours = 168, // Weekly
        ),
        IdleTaskConfig(
            type = IdleTaskType.DAILY_REPORT,
            enabled = true,
            priority = 90, // High — runs daily
            intervalHours = 24,
        ),
    )

    /**
     * Get the next idle task to execute, respecting priority and interval.
     *
     * @param lastRunTimes Map of IdleTaskType → last run timestamp (ISO)
     * @return Next task config to execute, or null if all tasks ran recently
     */
    fun getNextIdleTask(
        lastRunTimes: Map<IdleTaskType, String>,
    ): IdleTaskConfig? {
        val now = java.time.Instant.now()

        return defaultTasks
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
     * Get all configured idle tasks (for UI display).
     */
    fun getAllTasks(): List<IdleTaskConfig> = defaultTasks.toList()

    /**
     * Get task description for logging/reporting.
     */
    fun getTaskDescription(type: IdleTaskType): String = when (type) {
        IdleTaskType.REVIEW_BRAIN_ISSUES -> "Review open brain JIRA issues"
        IdleTaskType.KB_CONSISTENCY_CHECK -> "Check KB for duplicates and contradictions"
        IdleTaskType.VULNERABILITY_SCAN -> "Scan dependencies for known CVEs"
        IdleTaskType.CODE_QUALITY_SCAN -> "Run basic code quality analysis"
        IdleTaskType.DOCUMENTATION_FRESHNESS -> "Check documentation freshness"
        IdleTaskType.LEARNING_BEST_PRACTICES -> "Search for relevant best practices"
        IdleTaskType.DAILY_REPORT -> "Generate daily activity report"
    }
}
