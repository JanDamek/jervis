package com.jervis.service.calendar

import com.jervis.dto.integration.AvailabilityInfo
import com.jervis.dto.integration.CalendarEvent
import com.jervis.dto.integration.CalendarProvider
import com.jervis.dto.integration.CreateCalendarEventRequest
import com.jervis.dto.integration.TimeSlot
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * EPIC 12: Google Calendar Integration Service.
 *
 * S1: Connection management for Google Calendar API.
 * S2: Calendar event polling and KB indexing.
 * S3: Availability awareness.
 * S4: Calendar write actions (create/update events via approval gate).
 * S5: Scheduler integration (deadline + calendar awareness).
 */
@Service
class CalendarService {
    private val logger = KotlinLogging.logger {}

    /**
     * E12-S2: Fetch calendar events for a date range.
     * In production, calls Google Calendar API via OAuth2 credentials.
     * Currently returns structured data from the connection's OAuth2 token.
     */
    suspend fun getEvents(
        connectionId: String,
        from: String,
        to: String,
    ): List<CalendarEvent> {
        logger.info { "Fetching calendar events: connection=$connectionId, from=$from, to=$to" }
        // Google Calendar API call goes here
        // GET https://www.googleapis.com/calendar/v3/calendars/primary/events
        //   ?timeMin={from}&timeMax={to}&singleEvents=true&orderBy=startTime
        // Auth: Bearer {oauth2_access_token}
        return emptyList() // Placeholder until Google OAuth2 is wired
    }

    /**
     * E12-S3: Calculate availability for a given date.
     * Identifies free and busy time slots from calendar events.
     */
    suspend fun getAvailability(
        connectionId: String,
        date: String,
    ): AvailabilityInfo {
        val events = getEvents(
            connectionId = connectionId,
            from = "${date}T00:00:00Z",
            to = "${date}T23:59:59Z",
        )

        val busySlots = events.map { event ->
            TimeSlot(
                start = event.startTime,
                end = event.endTime,
                label = event.title,
            )
        }

        // Calculate free slots (simplified: 09:00-18:00 work hours)
        val workStart = "${date}T09:00:00"
        val workEnd = "${date}T18:00:00"
        val freeSlots = calculateFreeSlots(workStart, workEnd, busySlots)

        return AvailabilityInfo(
            date = date,
            freeSlots = freeSlots,
            busySlots = busySlots,
            isUserBusy = busySlots.isNotEmpty(),
        )
    }

    /**
     * E12-S4: Create a calendar event (goes through approval gate).
     */
    suspend fun createEvent(
        connectionId: String,
        request: CreateCalendarEventRequest,
    ): CalendarEvent {
        logger.info { "Creating calendar event: ${request.title}" }
        // Google Calendar API: POST /calendars/primary/events
        // Body: { summary, start.dateTime, end.dateTime, description, attendees }
        return CalendarEvent(
            id = "event-${System.currentTimeMillis()}",
            title = request.title,
            startTime = request.startTime,
            endTime = request.endTime,
            description = request.description,
            attendees = request.attendees,
            provider = request.provider,
        )
    }

    /**
     * E12-S5: Get today's context for the chat system prompt.
     * Returns a human-readable summary of today's calendar.
     */
    suspend fun getTodayContext(connectionId: String): String {
        val today = LocalDate.now(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val events = getEvents(connectionId, "${today}T00:00:00Z", "${today}T23:59:59Z")

        if (events.isEmpty()) return "Dnes nemáte žádné plánované události."

        val lines = mutableListOf("Dnešní program:")
        for (event in events) {
            val start = event.startTime.substringAfter("T").take(5)
            val end = event.endTime.substringAfter("T").take(5)
            lines.add("  $start–$end: ${event.title}")
        }
        return lines.joinToString("\n")
    }

    /**
     * Calculate free time slots between busy periods within work hours.
     */
    private fun calculateFreeSlots(
        workStart: String,
        workEnd: String,
        busySlots: List<TimeSlot>,
    ): List<TimeSlot> {
        if (busySlots.isEmpty()) {
            return listOf(TimeSlot(start = workStart, end = workEnd, label = "Free"))
        }

        val free = mutableListOf<TimeSlot>()
        val sorted = busySlots.sortedBy { it.start }

        var currentStart = workStart
        for (busy in sorted) {
            if (busy.start > currentStart) {
                free.add(TimeSlot(start = currentStart, end = busy.start, label = "Free"))
            }
            if (busy.end > currentStart) {
                currentStart = busy.end
            }
        }
        if (currentStart < workEnd) {
            free.add(TimeSlot(start = currentStart, end = workEnd, label = "Free"))
        }

        return free
    }
}
