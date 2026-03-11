package com.jervis.service.deadline

import com.jervis.common.types.ClientId
import com.jervis.dto.deadline.DeadlineDashboard
import com.jervis.dto.deadline.DeadlineItem
import com.jervis.dto.deadline.DeadlineUrgency
import mu.KotlinLogging
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * EPIC 8: Deadline Tracker Service.
 *
 * Periodically scans KB and JIRA for items with deadlines.
 * Generates threshold alerts at 7d, 3d, 1d, and overdue.
 * Triggers proactive preparation when deadlines approach.
 *
 * Integration points:
 * - Called by BackgroundEngine's scheduler loop
 * - Reads deadlines from KB (qualifier-extracted) and bug tracker
 * - Creates notification events and auto-prioritizes tasks
 */
@Service
class DeadlineTrackerService(
    private val mongoTemplate: ReactiveMongoTemplate,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Calculate urgency level based on remaining days.
     */
    fun calculateUrgency(deadline: Instant): DeadlineUrgency {
        val now = Instant.now()
        if (deadline.isBefore(now)) return DeadlineUrgency.OVERDUE

        val days = Duration.between(now, deadline).toDays()
        return when {
            days > 7 -> DeadlineUrgency.GREEN
            days > 3 -> DeadlineUrgency.YELLOW
            days > 1 -> DeadlineUrgency.ORANGE
            else -> DeadlineUrgency.RED
        }
    }

    /**
     * Calculate remaining days (negative for overdue).
     */
    fun calculateRemainingDays(deadline: Instant): Int {
        val now = Instant.now()
        return ChronoUnit.DAYS.between(now, deadline).toInt()
    }

    /**
     * Check if a deadline needs notification at its current urgency level.
     *
     * @param item The deadline item
     * @return True if a notification should be sent
     */
    fun needsNotification(item: DeadlineItem): Boolean {
        val currentLevel = item.urgency.name
        val lastLevel = item.notificationLevel
        // Notify if urgency escalated since last notification
        return lastLevel == null || currentLevel != lastLevel
    }

    /**
     * Generate notification text for a deadline threshold.
     */
    fun generateNotificationText(item: DeadlineItem): String = when (item.urgency) {
        DeadlineUrgency.GREEN -> "Za ${item.remainingDays} dní: ${item.title}"
        DeadlineUrgency.YELLOW -> "Za ${item.remainingDays} dní: ${item.title}. Stav: ${item.status}"
        DeadlineUrgency.ORANGE -> "POZOR: Zítra/pozítří deadline: ${item.title}"
        DeadlineUrgency.RED -> "URGENTNÍ: Deadline dnes: ${item.title}"
        DeadlineUrgency.OVERDUE -> "PROŠLÝ TERMÍN: ${item.title} (${-item.remainingDays} dní po termínu)"
    }

    /**
     * Get dashboard data for a client.
     */
    fun buildDashboard(items: List<DeadlineItem>): DeadlineDashboard {
        return DeadlineDashboard(
            items = items.sortedBy { it.remainingDays },
            overdueCount = items.count { it.urgency == DeadlineUrgency.OVERDUE },
            urgentCount = items.count { it.urgency == DeadlineUrgency.RED || it.urgency == DeadlineUrgency.ORANGE },
            upcomingCount = items.count { it.urgency == DeadlineUrgency.YELLOW || it.urgency == DeadlineUrgency.GREEN },
        )
    }
}
