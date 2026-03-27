package com.jervis.deadline

import com.jervis.dto.deadline.DeadlineItem
import com.jervis.dto.deadline.DeadlinePreparationPlan
import com.jervis.dto.deadline.DeadlineSubtask
import com.jervis.dto.deadline.DeadlineUrgency
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * EPIC 8-S3: Proactive Preparation Service.
 *
 * When a deadline enters YELLOW (3-7 days) or ORANGE (1-3 days) zone,
 * this service generates a preparation plan: breaks down remaining work
 * into subtasks, estimates effort, and optionally creates background tasks.
 *
 * Strategy:
 * 1. Analyze the deadline item (source type, status, remaining time)
 * 2. Query KB for related context (requirements, previous work, blockers)
 * 3. Generate a breakdown of remaining work
 * 4. Estimate effort and recommend prioritization
 * 5. Optionally auto-create background tasks for each subtask
 */
@Service
class ProactivePreparationService(
    private val deadlineTrackerService: DeadlineTrackerService,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Generate a preparation plan for an approaching deadline.
     *
     * Called when a deadline transitions to YELLOW or ORANGE urgency.
     *
     * @param item The deadline item approaching its due date
     * @param relatedContext KB context about the deadline (requirements, blockers)
     * @return Preparation plan with subtasks and effort estimates
     */
    fun generatePreparationPlan(
        item: DeadlineItem,
        relatedContext: List<String> = emptyList(),
    ): DeadlinePreparationPlan {
        val subtasks = mutableListOf<DeadlineSubtask>()

        // Phase 1: Standard preparation subtasks based on source type
        subtasks.addAll(generateStandardSubtasks(item))

        // Phase 2: Context-driven subtasks from KB
        subtasks.addAll(generateContextSubtasks(item, relatedContext))

        // Phase 3: Urgency-specific tasks
        subtasks.addAll(generateUrgencySubtasks(item))

        val totalEffort = subtasks.sumOf { it.estimatedHours ?: 0.0 }
        val prioritization = generatePrioritizationRecommendation(item, totalEffort)

        val plan = DeadlinePreparationPlan(
            deadlineId = item.id,
            title = "Preparation plan: ${item.title}",
            subtasks = subtasks,
            estimatedEffortHours = totalEffort,
            recommendedPrioritization = prioritization,
        )

        logger.info {
            "PREPARATION_PLAN | deadline=${item.id} | title=${item.title} | " +
                "subtasks=${subtasks.size} | effort=${totalEffort}h | urgency=${item.urgency}"
        }
        return plan
    }

    /**
     * Determine if a deadline needs proactive preparation.
     */
    fun needsPreparation(item: DeadlineItem): Boolean {
        return item.urgency in listOf(
            DeadlineUrgency.YELLOW,
            DeadlineUrgency.ORANGE,
            DeadlineUrgency.RED,
        )
    }

    /**
     * Generate standard subtasks based on the deadline source type.
     */
    private fun generateStandardSubtasks(item: DeadlineItem): List<DeadlineSubtask> {
        val subtasks = mutableListOf<DeadlineSubtask>()

        when {
            item.source.contains("jira", ignoreCase = true) -> {
                subtasks.add(DeadlineSubtask(
                    description = "Review JIRA issue status and blockers for: ${item.title}",
                    estimatedHours = 0.5,
                ))
                subtasks.add(DeadlineSubtask(
                    description = "Check linked PRs and code review status",
                    estimatedHours = 0.5,
                ))
                subtasks.add(DeadlineSubtask(
                    description = "Verify acceptance criteria and test coverage",
                    estimatedHours = 1.0,
                ))
            }
            item.source.contains("git", ignoreCase = true) -> {
                subtasks.add(DeadlineSubtask(
                    description = "Review pending PRs and merge conflicts",
                    estimatedHours = 1.0,
                ))
                subtasks.add(DeadlineSubtask(
                    description = "Run CI/CD pipeline validation",
                    estimatedHours = 0.5,
                ))
            }
            item.source.contains("meeting", ignoreCase = true) -> {
                subtasks.add(DeadlineSubtask(
                    description = "Prepare meeting agenda and materials",
                    estimatedHours = 1.0,
                ))
                subtasks.add(DeadlineSubtask(
                    description = "Review action items from previous meetings",
                    estimatedHours = 0.5,
                ))
            }
            else -> {
                subtasks.add(DeadlineSubtask(
                    description = "Review current progress on: ${item.title}",
                    estimatedHours = 0.5,
                ))
                subtasks.add(DeadlineSubtask(
                    description = "Identify remaining work and blockers",
                    estimatedHours = 0.5,
                ))
            }
        }

        return subtasks
    }

    /**
     * Generate context-driven subtasks from KB information.
     */
    private fun generateContextSubtasks(
        item: DeadlineItem,
        relatedContext: List<String>,
    ): List<DeadlineSubtask> {
        val subtasks = mutableListOf<DeadlineSubtask>()

        // Analyze context for blockers, dependencies, and requirements
        for (context in relatedContext.take(5)) {
            val lower = context.lowercase()
            when {
                lower.contains("blocked") || lower.contains("blocker") -> {
                    subtasks.add(DeadlineSubtask(
                        description = "Resolve blocker: ${context.take(100)}",
                        estimatedHours = 2.0,
                    ))
                }
                lower.contains("dependency") || lower.contains("depends on") -> {
                    subtasks.add(DeadlineSubtask(
                        description = "Verify dependency: ${context.take(100)}",
                        estimatedHours = 1.0,
                    ))
                }
                lower.contains("test") || lower.contains("qa") -> {
                    subtasks.add(DeadlineSubtask(
                        description = "Complete testing: ${context.take(100)}",
                        estimatedHours = 2.0,
                    ))
                }
            }
        }

        return subtasks
    }

    /**
     * Generate urgency-specific tasks based on how close the deadline is.
     */
    private fun generateUrgencySubtasks(item: DeadlineItem): List<DeadlineSubtask> {
        val subtasks = mutableListOf<DeadlineSubtask>()

        when (item.urgency) {
            DeadlineUrgency.ORANGE, DeadlineUrgency.RED -> {
                subtasks.add(DeadlineSubtask(
                    description = "Send status update to stakeholders",
                    estimatedHours = 0.25,
                ))
                if (item.urgency == DeadlineUrgency.RED) {
                    subtasks.add(DeadlineSubtask(
                        description = "Assess if deadline extension is needed — prepare risk summary",
                        estimatedHours = 0.5,
                    ))
                }
            }
            DeadlineUrgency.YELLOW -> {
                subtasks.add(DeadlineSubtask(
                    description = "Verify milestone progress and adjust plan if needed",
                    estimatedHours = 0.5,
                ))
            }
            else -> { /* GREEN or OVERDUE — no extra tasks */ }
        }

        return subtasks
    }

    /**
     * Generate prioritization recommendation based on urgency and effort.
     */
    private fun generatePrioritizationRecommendation(
        item: DeadlineItem,
        totalEffortHours: Double,
    ): String {
        val availableHours = item.remainingDays * 6.0 // Assume 6 productive hours/day
        val ratio = if (availableHours > 0) totalEffortHours / availableHours else 999.0

        return when {
            ratio > 1.5 -> "CRITICAL: Estimated effort (${totalEffortHours}h) exceeds available time. Scope reduction or deadline extension recommended."
            ratio > 1.0 -> "WARNING: Tight schedule. Focus exclusively on this deadline. Consider deprioritizing other tasks."
            ratio > 0.5 -> "MODERATE: Manageable timeline but requires focused effort. Avoid taking on new tasks."
            else -> "OK: Sufficient time remaining. Maintain steady progress."
        }
    }
}
