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
 * - NEW: Event fetched from Google Calendar API, not yet processed
 * - INDEXED: Task created, event content sent to KB pipeline
 * - FAILED: Processing error (stored in indexingError)
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
    val createdAt: Instant = Instant.now(),
    val indexingError: String? = null,
)
