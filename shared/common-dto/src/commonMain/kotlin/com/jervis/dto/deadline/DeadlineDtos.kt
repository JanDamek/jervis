package com.jervis.dto.deadline

import kotlinx.serialization.Serializable

/**
 * EPIC 8: Deadline Monitoring & Proactive Preparation DTOs.
 *
 * Supports deadline tracking, threshold alerts, proactive preparation,
 * and deadline dashboard widgets.
 */

/**
 * Urgency level for deadline proximity alerts.
 */
@Serializable
enum class DeadlineUrgency {
    /** More than 7 days away. */
    GREEN,
    /** 3-7 days away. */
    YELLOW,
    /** 1-3 days away. */
    ORANGE,
    /** Less than 1 day away. */
    RED,
    /** Past the deadline. */
    OVERDUE,
}

/**
 * A tracked deadline item.
 */
@Serializable
data class DeadlineItem(
    val id: String,
    val title: String,
    val deadline: String,
    val source: String,
    val sourceUrn: String,
    val projectId: String? = null,
    val clientId: String,
    val urgency: DeadlineUrgency,
    val remainingDays: Int,
    val status: String = "open",
    val lastNotifiedAt: String? = null,
    val notificationLevel: String? = null,
)

/**
 * Proactive preparation plan generated when deadline approaches.
 */
@Serializable
data class DeadlinePreparationPlan(
    val deadlineId: String,
    val title: String,
    val subtasks: List<DeadlineSubtask> = emptyList(),
    val estimatedEffortHours: Double? = null,
    val recommendedPrioritization: String? = null,
)

@Serializable
data class DeadlineSubtask(
    val description: String,
    val estimatedHours: Double? = null,
    val assignedTaskId: String? = null,
    val completed: Boolean = false,
)

/**
 * Dashboard widget data for deadline overview.
 */
@Serializable
data class DeadlineDashboard(
    val items: List<DeadlineItem> = emptyList(),
    val overdueCount: Int = 0,
    val urgentCount: Int = 0,
    val upcomingCount: Int = 0,
)
