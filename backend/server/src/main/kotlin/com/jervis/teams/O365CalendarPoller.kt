package com.jervis.teams

import com.jervis.calendar.CalendarEventIndexDocument
import com.jervis.calendar.CalendarEventIndexRepository
import com.jervis.client.ClientDocument
import com.jervis.common.types.ProjectId
import com.jervis.connection.ConnectionDocument
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ProviderEnum
import com.jervis.dto.integration.CalendarProvider
import com.jervis.infrastructure.polling.PollingResult
import com.jervis.infrastructure.polling.PollingStateService
import com.jervis.infrastructure.polling.PollingStatusEnum
import com.jervis.infrastructure.polling.handler.PollingContext
import com.jervis.infrastructure.polling.handler.ResourceFilter
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

private val logger = KotlinLogging.logger {}

/**
 * O365 (Microsoft Outlook) calendar polling component.
 *
 * Not a `PollingHandler` itself — `O365PollingHandler` owns the provider slot
 * for `MICROSOFT_TEAMS` and dispatches into this poller after chats / channels.
 * That mirrors `GoogleWorkspacePollingHandler`, which keeps Gmail and Calendar
 * inside one `PollingHandler` because the central poller registry is keyed by
 * provider.
 *
 * Dual-mode:
 * - **OAuth2 / Graph API**: when the connection has a fresh bearer token,
 *   call `https://graph.microsoft.com/v1.0/me/calendarView` directly with
 *   `Prefer: outlook.timezone="UTC"` so we get UTC `dateTime` strings.
 * - **Browser session via O365 Gateway**: when the connection has an
 *   `o365ClientId` and no bearer token, call `<gateway>/calendar/{clientId}`
 *   which proxies to Graph using browser-pool tokens (Conditional Access path).
 *
 * Persistence is shared with Google calendar via `CalendarEventIndexDocument`.
 * `provider = CalendarProvider.MICROSOFT_OUTLOOK`. Etag-based upsert mirrors
 * the Google flow exactly: on `@odata.etag` mismatch we overwrite mutable
 * fields and reset `state = NEW` so `CalendarContinuousIndexer` picks the doc
 * up again and propagates the update into the underlying meeting `TaskDocument`.
 *
 * Project routing:
 * - Project-level `CALENDAR_READ` resources take priority.
 * - Otherwise client-level fallback uses `client.defaultProjectId`, so a
 *   Teams web meeting polled on the client connection still lands inside the
 *   correct project context (e.g. mazlusek → "příprava").
 */
@Component
class O365CalendarPoller(
    private val calendarRepository: CalendarEventIndexRepository,
    private val pollingStateService: PollingStateService,
    private val httpClient: HttpClient,
    @Value("\${jervis.o365-gateway.url:http://jervis-o365-gateway:8080}")
    private val gatewayUrl: String,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    /**
     * Run a calendar polling cycle for an O365 connection. At least one of
     * [accessToken] (OAuth2) or [o365ClientId] (browser pool) must be non-null.
     */
    suspend fun poll(
        connection: ConnectionDocument,
        context: PollingContext,
        accessToken: String?,
        o365ClientId: String?,
    ): PollingResult {
        if (accessToken == null && o365ClientId.isNullOrBlank()) {
            return PollingResult(errors = 1, authenticationError = true)
        }

        var totalResult = PollingResult()

        for (client in context.clients) {
            // Client-level events with no project filter fall back to the
            // client's defaultProjectId so every indexed item carries a
            // (clientId, projectId) pair. Required for the meeting attend
            // approval bubble (which is rendered inside the project chat scope).
            val clientLevelProjectId = client.defaultProjectId

            val clientFilter = context.getResourceFilter(client.id, ConnectionCapability.CALENDAR_READ)
            if (clientFilter != null) {
                val result = pollCalendar(
                    connection = connection,
                    client = client,
                    projectId = clientLevelProjectId,
                    accessToken = accessToken,
                    o365ClientId = o365ClientId,
                    calendarKey = "primary",
                    resourceFilter = clientFilter,
                )
                totalResult = totalResult.merge(result)
            }

            // Project-level overrides — projects can claim the calendar resource
            // and route events directly under their projectId.
            for (project in context.projects.filter { it.clientId == client.id }) {
                val projectFilter = context.getProjectResourceFilter(
                    projectId = project.id,
                    clientId = client.id,
                    capability = ConnectionCapability.CALENDAR_READ,
                )
                if (projectFilter != null) {
                    val result = pollCalendar(
                        connection = connection,
                        client = client,
                        projectId = project.id,
                        accessToken = accessToken,
                        o365ClientId = o365ClientId,
                        calendarKey = "primary",
                        resourceFilter = projectFilter,
                    )
                    totalResult = totalResult.merge(result)
                }
            }
        }

        return totalResult
    }

    private fun PollingResult.merge(other: PollingResult) = PollingResult(
        itemsDiscovered = itemsDiscovered + other.itemsDiscovered,
        itemsCreated = itemsCreated + other.itemsCreated,
        itemsSkipped = itemsSkipped + other.itemsSkipped,
        errors = errors + other.errors,
        authenticationError = authenticationError || other.authenticationError,
    )

    private suspend fun pollCalendar(
        connection: ConnectionDocument,
        client: ClientDocument,
        projectId: ProjectId?,
        accessToken: String?,
        o365ClientId: String?,
        calendarKey: String,
        @Suppress("UNUSED_PARAMETER") resourceFilter: ResourceFilter,
    ): PollingResult {
        val tool = "O365_CALENDAR:$calendarKey"
        val state = pollingStateService.getState(connection.id, ProviderEnum.MICROSOFT_TEAMS, tool)

        // Same window as Google: from last poll (or 1 day ago) to 7 days ahead.
        val timeMin = state?.lastSeenUpdatedAt ?: Instant.now().minus(1, ChronoUnit.DAYS)
        val timeMax = Instant.now().plus(7, ChronoUnit.DAYS)

        val events = if (accessToken != null) {
            fetchEventsGraphApi(accessToken, timeMin, timeMax)
        } else {
            fetchEventsGateway(o365ClientId!!, timeMin, timeMax)
        } ?: return PollingResult(errors = 1)

        if (events.isEmpty()) {
            pollingStateService.updateWithTimestamp(connection.id, ProviderEnum.MICROSOFT_TEAMS, Instant.now(), tool)
            return PollingResult()
        }

        var created = 0
        var updated = 0
        var skipped = 0

        for (event in events) {
            val eventId = event.id ?: continue
            val startTime = parseGraphDateTime(event.start) ?: continue
            val endTime = parseGraphDateTime(event.end) ?: continue

            val attendees = event.attendees
                ?.mapNotNull { it.emailAddress?.address }
                ?: emptyList()
            val isOnline = event.isOnlineMeeting == true || !event.onlineMeetingUrl.isNullOrBlank()
            val joinUrl = event.onlineMeetingUrl
            val isCancelled = event.isCancelled == true
            val isAllDay = event.isAllDay == true
            val isRecurring = event.recurrence != null

            val existing = calendarRepository.findByConnectionIdAndEventId(connection.id, eventId)
            if (existing != null) {
                if (existing.etag != null && existing.etag == event.odataEtag) {
                    skipped++
                    continue
                }
                val refreshed = existing.copy(
                    state = PollingStatusEnum.NEW,
                    title = event.subject ?: existing.title,
                    startTime = startTime,
                    endTime = endTime,
                    location = event.location?.displayName,
                    description = event.body?.content,
                    attendees = attendees,
                    isAllDay = isAllDay,
                    isRecurring = isRecurring,
                    organizer = event.organizer?.emailAddress?.address,
                    isOnlineMeeting = isOnline,
                    onlineMeetingJoinUrl = joinUrl,
                    etag = event.odataEtag,
                    isCancelled = isCancelled,
                    updatedAt = Instant.now(),
                    indexingError = null,
                )
                calendarRepository.save(refreshed)
                updated++
                continue
            }

            val doc = CalendarEventIndexDocument(
                connectionId = connection.id,
                clientId = client.id,
                projectId = projectId,
                eventId = eventId,
                calendarId = calendarKey,
                provider = CalendarProvider.MICROSOFT_OUTLOOK,
                title = event.subject ?: "Untitled Event",
                startTime = startTime,
                endTime = endTime,
                location = event.location?.displayName,
                description = event.body?.content,
                attendees = attendees,
                isAllDay = isAllDay,
                isRecurring = isRecurring,
                organizer = event.organizer?.emailAddress?.address,
                isOnlineMeeting = isOnline,
                onlineMeetingJoinUrl = joinUrl,
                etag = event.odataEtag,
                isCancelled = isCancelled,
            )
            calendarRepository.save(doc)
            created++
        }

        pollingStateService.updateWithTimestamp(connection.id, ProviderEnum.MICROSOFT_TEAMS, Instant.now(), tool)
        logger.info {
            "O365_CALENDAR: connection=${connection.name} created=$created updated=$updated skipped=$skipped"
        }
        return PollingResult(
            itemsDiscovered = created + updated + skipped,
            itemsCreated = created + updated,
            itemsSkipped = skipped,
        )
    }

    // -- Graph API direct (OAuth2) --------------------------------------------

    private val graphBaseUrl = "https://graph.microsoft.com/v1.0"

    private suspend fun fetchEventsGraphApi(
        token: String,
        timeMin: Instant,
        timeMax: Instant,
    ): List<O365GraphEvent>? {
        return try {
            val url = "$graphBaseUrl/me/calendarView" +
                "?startDateTime=$timeMin" +
                "&endDateTime=$timeMax" +
                "&\$top=100" +
                "&\$orderby=start/dateTime"
            val response = httpClient.get(url) {
                header("Authorization", "Bearer $token")
                // Force UTC dateTime strings instead of the user's preferred timezone.
                header("Prefer", "outlook.timezone=\"UTC\"")
            }
            if (!response.status.isSuccess()) {
                logger.warn { "O365_CALENDAR: Graph /me/calendarView returned ${response.status}" }
                return null
            }
            response.body<GraphListResponse<O365GraphEvent>>().value
        } catch (e: Exception) {
            logger.error(e) { "O365_CALENDAR: Error fetching events from Graph API" }
            null
        }
    }

    // -- Gateway proxy (browser session pool) ---------------------------------

    private suspend fun fetchEventsGateway(
        o365ClientId: String,
        timeMin: Instant,
        timeMax: Instant,
    ): List<O365GraphEvent>? {
        return try {
            val url = "$gatewayUrl/calendar/$o365ClientId" +
                "?startDateTime=$timeMin" +
                "&endDateTime=$timeMax" +
                "&top=100"
            val response = httpClient.get(url)
            if (!response.status.isSuccess()) {
                logger.warn { "O365_CALENDAR: Gateway /calendar/$o365ClientId returned ${response.status}" }
                return null
            }
            response.body<List<O365GraphEvent>>()
        } catch (e: Exception) {
            logger.error(e) { "O365_CALENDAR: Error fetching events from gateway" }
            null
        }
    }

    private fun parseGraphDateTime(dt: O365GraphDateTime?): Instant? {
        val raw = dt?.dateTime ?: return null
        // Microsoft Graph returns "yyyy-MM-ddTHH:mm:ss.fffffff" without timezone
        // suffix when Prefer: outlook.timezone="UTC" is set. The Gateway proxies
        // the raw value through. Append a Z if missing so Instant.parse() works.
        val normalized = if (raw.endsWith("Z") || raw.contains("+")) raw else "${raw}Z"
        return try {
            Instant.parse(normalized)
        } catch (_: Exception) {
            null
        }
    }

    // -- Local serialization models -------------------------------------------
    //
    // Mirrors the gateway's GraphEvent shape but lives here so the server module
    // doesn't depend on the gateway artefact. Only the fields we actually use.

    @Serializable
    private data class GraphListResponse<T>(
        val value: List<T> = emptyList(),
        @SerialName("@odata.nextLink")
        val nextLink: String? = null,
    )

    @Serializable
    data class O365GraphEvent(
        val id: String? = null,
        val subject: String? = null,
        val body: O365GraphBody? = null,
        val start: O365GraphDateTime? = null,
        val end: O365GraphDateTime? = null,
        val location: O365GraphLocation? = null,
        val organizer: O365GraphEmailAddressWrapper? = null,
        val attendees: List<O365GraphAttendee>? = null,
        val isAllDay: Boolean? = null,
        val isCancelled: Boolean? = null,
        val isOnlineMeeting: Boolean? = null,
        val onlineMeetingUrl: String? = null,
        val recurrence: kotlinx.serialization.json.JsonElement? = null,
        @SerialName("@odata.etag")
        val odataEtag: String? = null,
    )

    @Serializable
    data class O365GraphBody(
        val contentType: String? = null,
        val content: String? = null,
    )

    @Serializable
    data class O365GraphDateTime(
        val dateTime: String? = null,
        val timeZone: String? = null,
    )

    @Serializable
    data class O365GraphLocation(
        val displayName: String? = null,
    )

    @Serializable
    data class O365GraphEmailAddressWrapper(
        val emailAddress: O365GraphEmailAddress? = null,
    )

    @Serializable
    data class O365GraphEmailAddress(
        val name: String? = null,
        val address: String? = null,
    )

    @Serializable
    data class O365GraphAttendee(
        val emailAddress: O365GraphEmailAddress? = null,
        val type: String? = null,
    )
}
