package com.jervis.dto.brain

import kotlinx.serialization.Serializable

/**
 * EPIC 16: Brain Workflow Structure DTOs.
 *
 * Defines issue types, workflows, Confluence space structure,
 * and daily report format for the internal Brain system.
 */

/**
 * Brain JIRA issue types.
 */
@Serializable
enum class BrainIssueType {
    /** Standard work item. */
    TASK,
    /** Discovered bug or problem. */
    BUG,
    /** Finding from monitoring/scanning. */
    FINDING,
    /** Code review finding. */
    REVIEW,
    /** Learned best practice or pattern. */
    LEARNING,
}

/**
 * Brain JIRA workflow states.
 */
@Serializable
enum class BrainWorkflowState {
    OPEN,
    IN_PROGRESS,
    REVIEW,
    DONE,
    BLOCKED,
}

/**
 * Confluence space pages for auto-created structure.
 */
@Serializable
enum class ConfluenceSpaceSection {
    ARCHITECTURE,
    DAILY_REPORTS,
    CLIENT_PAGES,
    KNOWLEDGE,
    DRAFTS,
}

/**
 * Daily report data structure.
 */
@Serializable
data class DailyReport(
    val date: String,
    val clientId: String,
    val completedTasks: List<DailyReportItem> = emptyList(),
    val newKbEntries: Int = 0,
    val pendingApprovals: Int = 0,
    val upcomingDeadlines: List<DailyReportItem> = emptyList(),
    val errors: List<DailyReportItem> = emptyList(),
    val stuckTasks: List<DailyReportItem> = emptyList(),
)

@Serializable
data class DailyReportItem(
    val title: String,
    val description: String = "",
    val taskId: String? = null,
    val severity: String? = null,
)
