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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import mu.KotlinLogging
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
 * Single-mode: **Browser session via O365 Gateway**. The server never talks to
 * Microsoft Graph directly — every Microsoft Graph call goes through the
 * O365 gateway pod (which holds the browser session and the Conditional
 * Access path). The poller hands `o365ClientId` to the gateway gRPC client
 * and the gateway returns calendar events via its `listCalendarEvents` RPC.
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
    private val o365GatewayGrpc: com.jervis.infrastructure.grpc.O365GatewayGrpcClient,
) {
    /**
     * Run a calendar polling cycle for an O365 connection. The O365 gateway
     * pod owns the browser session and proxies Graph requests, so the only
     * thing the poller needs is the [o365ClientId] handle.
     */
    suspend fun poll(
        connection: ConnectionDocument,
        context: PollingContext,
        o365ClientId: String?,
    ): PollingResult {
        if (o365ClientId.isNullOrBlank()) {
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
        o365ClientId: String?,
        calendarKey: String,
        @Suppress("UNUSED_PARAMETER") resourceFilter: ResourceFilter,
    ): PollingResult {
        val tool = "O365_CALENDAR:$calendarKey"
        val state = pollingStateService.getState(connection.id, ProviderEnum.MICROSOFT_TEAMS, tool)

        // Same window as Google: from last poll (or 1 day ago) to 7 days ahead.
        val timeMin = state?.lastSeenUpdatedAt ?: Instant.now().minus(1, ChronoUnit.DAYS)
        val timeMax = Instant.now().plus(7, ChronoUnit.DAYS)

        val events = fetchEventsGateway(o365ClientId!!, timeMin, timeMax)
            ?: return PollingResult(errors = 1)

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

    // -- Gateway proxy (browser session pool) ---------------------------------

    private suspend fun fetchEventsGateway(
        o365ClientId: String,
        timeMin: Instant,
        timeMax: Instant,
    ): List<O365GraphEvent>? = try {
        o365GatewayGrpc.listCalendarEvents(
            o365ClientId,
            top = 100,
            startDateTime = timeMin.toString(),
            endDateTime = timeMax.toString(),
        ).map { it.toGraphEvent() }
    } catch (e: Exception) {
        logger.error(e) { "O365_CALENDAR: Error fetching events from gateway" }
        null
    }

    private fun com.jervis.contracts.o365_gateway.CalendarEvent.toGraphEvent(): O365GraphEvent =
        O365GraphEvent(
            id = id.takeIf { it.isNotBlank() },
            subject = subject.takeIf { it.isNotBlank() },
            body = if (hasBody()) O365GraphBody(
                contentType = body.contentType.takeIf { it.isNotBlank() },
                content = body.content.takeIf { it.isNotBlank() },
            ) else null,
            start = if (hasStart()) O365GraphDateTime(
                dateTime = start.dateTime.takeIf { it.isNotBlank() },
                timeZone = start.timeZone.takeIf { it.isNotBlank() },
            ) else null,
            end = if (hasEnd()) O365GraphDateTime(
                dateTime = end.dateTime.takeIf { it.isNotBlank() },
                timeZone = end.timeZone.takeIf { it.isNotBlank() },
            ) else null,
            location = if (hasLocation()) O365GraphLocation(
                displayName = location.displayName.takeIf { it.isNotBlank() },
            ) else null,
            organizer = if (hasOrganizer()) O365GraphEmailAddressWrapper(
                emailAddress = O365GraphEmailAddress(
                    name = organizer.name.takeIf { it.isNotBlank() },
                    address = organizer.address.takeIf { it.isNotBlank() },
                ),
            ) else null,
            attendees = attendeesList.takeIf { it.isNotEmpty() }?.map { a ->
                O365GraphAttendee(
                    emailAddress = if (a.hasEmailAddress()) O365GraphEmailAddress(
                        name = a.emailAddress.name.takeIf { it.isNotBlank() },
                        address = a.emailAddress.address.takeIf { it.isNotBlank() },
                    ) else null,
                    type = a.type.takeIf { it.isNotBlank() },
                )
            },
            isAllDay = isAllDay,
            isCancelled = isCancelled,
            isOnlineMeeting = isOnlineMeeting,
            onlineMeetingUrl = onlineMeetingUrl.takeIf { it.isNotBlank() },
            recurrence = null,
            odataEtag = odataEtag.takeIf { it.isNotBlank() },
        )

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
