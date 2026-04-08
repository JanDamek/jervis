package com.jervis.task

import java.time.Instant

/**
 * Embedded meeting metadata on TaskDocument.
 *
 * Populated by calendar polling indexers (Google, Outlook) for events that
 * represent online meetings (Teams, Meet, Zoom, ...). Used by the meeting attend
 * approval flow to know when, where and how Jervis can join a meeting after the
 * user explicitly approves it.
 *
 * The presence of this object on a task implies the task represents an upcoming
 * (or in-progress) online meeting that may be subject to a MEETING_ATTEND
 * approval request. NEVER triggers automatic join — approval is mandatory.
 */
data class MeetingMetadata(
    /** Meeting start in UTC. Mirrors TaskDocument.scheduledAt for convenience. */
    val startTime: Instant,
    /** Meeting end in UTC. Used to detect "still running" vs "already past". */
    val endTime: Instant,
    /** Provider derived from join URL or calendar source. */
    val provider: MeetingProvider,
    /** Join URL (Teams `joinWebUrl`, Google Meet `hangoutLink`, Zoom URL, ...). */
    val joinUrl: String? = null,
    /** Organizer email if known. */
    val organizer: String? = null,
    /** Attendee emails (best-effort, may be partial for large meetings). */
    val attendees: List<String> = emptyList(),
    /** Event location string (room, address, "Microsoft Teams Meeting", ...). */
    val location: String? = null,
    /**
     * Teams chat thread ID (`onlineMeeting.chatInfo.threadId`).
     * Resolved later via Graph lookup; null at calendar-index time.
     */
    val chatThreadId: String? = null,
    /** Whether the underlying calendar event is part of a recurring series. */
    val isRecurring: Boolean = false,
)

/**
 * Online meeting provider classification.
 *
 * Derived from join URL host (teams.microsoft.com, meet.google.com, zoom.us, ...)
 * or from explicit calendar conference data. UNKNOWN means we know it's an online
 * meeting but couldn't classify the provider.
 */
enum class MeetingProvider {
    TEAMS,
    GOOGLE_MEET,
    ZOOM,
    WEBEX,
    UNKNOWN,
    ;

    companion object {
        /** Best-effort classification from a join URL. */
        fun fromJoinUrl(url: String?): MeetingProvider {
            if (url.isNullOrBlank()) return UNKNOWN
            val lower = url.lowercase()
            return when {
                "teams.microsoft.com" in lower || "teams.live.com" in lower -> TEAMS
                "meet.google.com" in lower -> GOOGLE_MEET
                "zoom.us" in lower || "zoom.com" in lower -> ZOOM
                "webex.com" in lower -> WEBEX
                else -> UNKNOWN
            }
        }
    }
}
