package com.jervis.dto.task

import kotlinx.serialization.Serializable

/**
 * A single entry in the calendar view — can be a scheduled task,
 * calendar event (meeting), or a task with a deadline.
 */
@Serializable
data class CalendarEntryDto(
    val id: String,
    val title: String,
    val startEpochMs: Long,
    val endEpochMs: Long? = null,
    val entryType: CalendarEntryType,
    val state: TaskStateEnum = TaskStateEnum.NEW,
    val clientId: String? = null,
    val projectId: String? = null,
    val clientName: String? = null,
    val projectName: String? = null,
    /** For meetings: join URL. */
    val joinUrl: String? = null,
    /** For meetings: provider (TEAMS, GOOGLE_MEET, ZOOM). */
    val meetingProvider: String? = null,
    /** For scheduled tasks: cron expression. */
    val cronExpression: String? = null,
    /** True if overdue (scheduledAt < now, state not DONE). */
    val isOverdue: Boolean = false,
    /** Short preview of task content. */
    val contentPreview: String? = null,
)

@Serializable
enum class CalendarEntryType {
    /** User-scheduled task (via chat or scheduler). */
    SCHEDULED_TASK,
    /** Calendar event from O365/Google polling. */
    CALENDAR_EVENT,
    /** Regular task with a deadline (scheduledAt set). */
    DEADLINE_TASK,
}
