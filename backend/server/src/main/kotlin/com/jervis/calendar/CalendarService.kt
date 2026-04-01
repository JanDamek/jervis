package com.jervis.calendar

import com.jervis.connection.ConnectionService
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ProviderEnum
import com.jervis.dto.integration.AvailabilityInfo
import com.jervis.dto.integration.CalendarEvent
import com.jervis.dto.integration.CalendarProvider
import com.jervis.dto.integration.CreateCalendarEventRequest
import com.jervis.dto.integration.TimeSlot
import com.jervis.infrastructure.oauth2.OAuth2Service
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Google Calendar Integration Service.
 *
 * Provides calendar event read/write via Google Calendar API.
 * OAuth2 tokens managed by OAuth2Service (automatic refresh).
 */
@Service
class CalendarService(
    private val preferenceService: com.jervis.preferences.PreferenceService,
    private val connectionService: ConnectionService,
    private val oauth2Service: OAuth2Service,
    private val httpClient: HttpClient,
) {
    private val logger = KotlinLogging.logger {}
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    /**
     * Fetch calendar events for a date range from Google Calendar API.
     */
    suspend fun getEvents(
        connectionId: String,
        from: String,
        to: String,
    ): List<CalendarEvent> {
        logger.info { "Fetching calendar events: connection=$connectionId, from=$from, to=$to" }

        val connection = connectionService.findById(com.jervis.common.types.ConnectionId(ObjectId(connectionId))) ?: run {
            logger.warn { "Calendar connection not found: $connectionId" }
            return emptyList()
        }

        if (connection.provider != ProviderEnum.GOOGLE_WORKSPACE) {
            logger.warn { "Calendar connection $connectionId is not Google Workspace" }
            return emptyList()
        }

        oauth2Service.refreshAccessToken(connection)
        val token = connection.bearerToken
        if (token.isNullOrBlank()) {
            logger.warn { "No bearer token for calendar connection $connectionId" }
            return emptyList()
        }

        return try {
            val response = httpClient.get("https://www.googleapis.com/calendar/v3/calendars/primary/events") {
                header("Authorization", "Bearer $token")
                parameter("timeMin", from)
                parameter("timeMax", to)
                parameter("singleEvents", "true")
                parameter("orderBy", "startTime")
                parameter("maxResults", "100")
            }

            if (!response.status.isSuccess()) {
                logger.warn { "Google Calendar API returned ${response.status}" }
                return emptyList()
            }

            val body = json.decodeFromString<GoogleEventsResponse>(response.body())
            body.items?.map { event ->
                CalendarEvent(
                    id = event.id ?: "",
                    title = event.summary ?: "Untitled",
                    startTime = event.start?.dateTime ?: event.start?.date ?: "",
                    endTime = event.end?.dateTime ?: event.end?.date ?: "",
                    location = event.location,
                    description = event.description,
                    attendees = event.attendees?.mapNotNull { it.email } ?: emptyList(),
                    isAllDay = event.start?.date != null,
                    isRecurring = event.recurringEventId != null,
                    provider = CalendarProvider.GOOGLE_CALENDAR,
                )
            } ?: emptyList()
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch calendar events" }
            emptyList()
        }
    }

    /**
     * Calculate availability for a given date.
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
     * Create a calendar event via Google Calendar API.
     */
    suspend fun createEvent(
        connectionId: String,
        request: CreateCalendarEventRequest,
    ): CalendarEvent {
        logger.info { "Creating calendar event: ${request.title}" }

        val connection = connectionService.findById(com.jervis.common.types.ConnectionId(ObjectId(connectionId))) ?: run {
            throw IllegalStateException("Calendar connection not found: $connectionId")
        }

        oauth2Service.refreshAccessToken(connection)
        val token = connection.bearerToken
            ?: throw IllegalStateException("No bearer token for connection $connectionId")

        val body = buildString {
            append("{")
            append("\"summary\":\"${request.title.replace("\"", "\\\"")}\",")
            append("\"start\":{\"dateTime\":\"${request.startTime}\"},")
            append("\"end\":{\"dateTime\":\"${request.endTime}\"}")
            if (!request.description.isNullOrBlank()) {
                append(",\"description\":\"${request.description.replace("\"", "\\\"")}\"")
            }
            if (request.attendees.isNotEmpty()) {
                val attendeesJson = request.attendees.joinToString(",") { "{ \"email\": \"$it\" }" }
                append(",\"attendees\":[$attendeesJson]")
            }
            append("}")
        }

        val response = httpClient.post("https://www.googleapis.com/calendar/v3/calendars/primary/events") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        if (!response.status.isSuccess()) {
            throw IllegalStateException("Failed to create calendar event: ${response.status}")
        }

        val created = json.decodeFromString<GoogleEventDto>(response.body<String>())
        return CalendarEvent(
            id = created.id ?: "",
            title = created.summary ?: request.title,
            startTime = created.start?.dateTime ?: request.startTime,
            endTime = created.end?.dateTime ?: request.endTime,
            description = request.description,
            attendees = request.attendees,
            provider = CalendarProvider.GOOGLE_CALENDAR,
        )
    }

    /**
     * Get today's context for the chat system prompt.
     */
    suspend fun getTodayContext(connectionId: String): String {
        val userZone = preferenceService.getUserTimezone()
        val today = LocalDate.now(userZone).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val startOfDay = LocalDate.parse(today).atStartOfDay(userZone).toInstant().toString()
        val endOfDay = LocalDate.parse(today).atTime(23, 59, 59).atZone(userZone).toInstant().toString()
        val events = getEvents(connectionId, startOfDay, endOfDay)

        if (events.isEmpty()) return "Dnes nemáte žádné plánované události."

        val lines = mutableListOf("Dnešní program:")
        for (event in events) {
            val start = event.startTime.substringAfter("T").take(5)
            val end = event.endTime.substringAfter("T").take(5)
            lines.add("  $start–$end: ${event.title}")
        }
        return lines.joinToString("\n")
    }

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

// ── Google Calendar API DTOs (internal) ──────────────────────────────────

@Serializable
private data class GoogleEventsResponse(
    val items: List<GoogleEventDto>? = null,
)

@Serializable
private data class GoogleEventDto(
    val id: String? = null,
    val summary: String? = null,
    val description: String? = null,
    val location: String? = null,
    val start: GoogleDateTimeDto? = null,
    val end: GoogleDateTimeDto? = null,
    val attendees: List<GoogleAttendeeDto>? = null,
    val organizer: GoogleOrganizerDto? = null,
    val recurringEventId: String? = null,
)

@Serializable
private data class GoogleDateTimeDto(
    val dateTime: String? = null,
    val date: String? = null,
    val timeZone: String? = null,
)

@Serializable
private data class GoogleAttendeeDto(
    val email: String? = null,
    val displayName: String? = null,
)

@Serializable
private data class GoogleOrganizerDto(
    val email: String? = null,
)
