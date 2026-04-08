package com.jervis.calendar

import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.common.types.ProjectId
import com.jervis.dto.integration.CalendarProvider
import com.jervis.infrastructure.polling.PollingStatusEnum
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Index document for calendar events discovered by polling.
 *
 * State machine: NEW → INDEXED (or FAILED)
 * - NEW: Event fetched from calendar API, not yet processed (initial insert OR
 *   update — when the upstream `etag` changes the poller resets state to NEW so
 *   the indexer reprocesses the event and propagates the update to the
 *   underlying TaskDocument).
 * - INDEXED: Task created or updated, event content sent to KB pipeline.
 * - FAILED: Processing error (stored in indexingError)
 *
 * Update flow:
 * The poller compares the current `etag` against the persisted one. On mismatch
 * it overwrites the document fields and flips state back to NEW so the
 * CalendarContinuousIndexer picks it up again. The indexer then upserts the
 * task by `correlationId = "calendar:<eventId>"`.
 */
@Document(collection = "calendar_event_index")
@CompoundIndexes(
    CompoundIndex(name = "connection_eventid_idx", def = "{'connectionId': 1, 'eventId': 1}", unique = true),
    CompoundIndex(name = "connection_state_idx", def = "{'connectionId': 1, 'state': 1}"),
    CompoundIndex(name = "client_state_idx", def = "{'clientId': 1, 'state': 1}"),
)
data class CalendarEventIndexDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    val connectionId: ConnectionId,
    val clientId: ClientId,
    val projectId: ProjectId? = null,
    /** Google Calendar event ID (unique per calendar). */
    val eventId: String,
    /** Calendar ID (e.g., "primary" or user email). */
    val calendarId: String = "primary",
    val state: PollingStatusEnum = PollingStatusEnum.NEW,
    val provider: CalendarProvider = CalendarProvider.GOOGLE_CALENDAR,
    val title: String,
    val startTime: Instant,
    val endTime: Instant,
    val location: String? = null,
    val description: String? = null,
    val attendees: List<String> = emptyList(),
    val isAllDay: Boolean = false,
    val isRecurring: Boolean = false,
    val organizer: String? = null,
    /**
     * True if the event is an online meeting (Teams/Meet/Zoom/...).
     * Detected from calendar conference data or join URL on the event.
     */
    val isOnlineMeeting: Boolean = false,
    /** Join URL for the online meeting (Teams `joinWebUrl`, Google Meet `hangoutLink`, ...). */
    val onlineMeetingJoinUrl: String? = null,
    /**
     * Upstream entity tag (Google Calendar `etag`, Outlook `@odata.etag`).
     * Compared on every poll cycle to detect updates and trigger reindex.
     */
    val etag: String? = null,
    /** True when the upstream event has been cancelled. Propagated to the task. */
    val isCancelled: Boolean = false,
    val createdAt: Instant = Instant.now(),
    /** Last time the document was reindexed because of an upstream update. */
    val updatedAt: Instant? = null,
    val indexingError: String? = null,
)
