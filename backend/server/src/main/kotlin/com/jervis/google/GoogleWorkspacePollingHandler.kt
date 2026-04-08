package com.jervis.google

import com.jervis.calendar.CalendarEventIndexDocument
import com.jervis.calendar.CalendarEventIndexRepository
import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.common.types.ProjectId
import com.jervis.connection.ConnectionDocument
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ProviderEnum
import com.jervis.dto.integration.CalendarProvider
import com.jervis.email.EmailAttachment
import com.jervis.email.EmailDirection
import com.jervis.email.EmailMessageIndexDocument
import com.jervis.email.EmailMessageIndexRepository
import com.jervis.infrastructure.oauth2.OAuth2Service
import com.jervis.infrastructure.polling.PollingResult
import com.jervis.infrastructure.polling.PollingStateService
import com.jervis.infrastructure.polling.handler.PollingContext
import com.jervis.infrastructure.polling.handler.PollingHandler
import com.jervis.infrastructure.polling.handler.ResourceFilter
import com.jervis.client.ClientDocument
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

private val logger = KotlinLogging.logger {}

/**
 * Polling handler for Google Workspace (Gmail API + Google Calendar API).
 *
 * Handles two capabilities:
 * - EMAIL_READ: Polls Gmail API for new messages (deduplicates against IMAP-indexed emails)
 * - CALENDAR_READ: Polls Google Calendar API for upcoming events
 *
 * Authentication: OAuth2 with automatic token refresh via OAuth2Service.
 */
@Component
class GoogleWorkspacePollingHandler(
    private val emailRepository: EmailMessageIndexRepository,
    private val calendarRepository: CalendarEventIndexRepository,
    private val pollingStateService: PollingStateService,
    private val oauth2Service: OAuth2Service,
    private val httpClient: HttpClient,
) : PollingHandler {
    override val provider: ProviderEnum = ProviderEnum.GOOGLE_WORKSPACE

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    override fun canHandle(connectionDocument: ConnectionDocument): Boolean {
        return connectionDocument.availableCapabilities.any {
            it == ConnectionCapability.EMAIL_READ || it == ConnectionCapability.CALENDAR_READ
        }
    }

    override suspend fun poll(
        connectionDocument: ConnectionDocument,
        context: PollingContext,
    ): PollingResult {
        // Ensure fresh OAuth2 token
        oauth2Service.refreshAccessToken(connectionDocument)
        val token = connectionDocument.bearerToken
        if (token.isNullOrBlank()) {
            logger.warn { "GOOGLE: No bearer token for '${connectionDocument.name}'" }
            return PollingResult(errors = 1, authenticationError = true)
        }

        var totalResult = PollingResult()

        for (client in context.clients) {
            // Gmail polling
            if (connectionDocument.availableCapabilities.contains(ConnectionCapability.EMAIL_READ)) {
                val emailFilter = context.getResourceFilter(client.id, ConnectionCapability.EMAIL_READ)
                if (emailFilter != null) {
                    val result = pollGmail(connectionDocument, client, null, token, emailFilter)
                    totalResult = totalResult.merge(result)
                }
            }

            // Calendar polling
            if (connectionDocument.availableCapabilities.contains(ConnectionCapability.CALENDAR_READ)) {
                val calFilter = context.getResourceFilter(client.id, ConnectionCapability.CALENDAR_READ)
                if (calFilter != null) {
                    val result = pollCalendar(connectionDocument, client, null, token, calFilter)
                    totalResult = totalResult.merge(result)
                }
            }

            // Project-level polling
            for (project in context.projects.filter { it.clientId == client.id }) {
                if (connectionDocument.availableCapabilities.contains(ConnectionCapability.EMAIL_READ)) {
                    val filter = context.getProjectResourceFilter(project.id, client.id, ConnectionCapability.EMAIL_READ)
                    if (filter != null) {
                        val result = pollGmail(connectionDocument, client, project.id, token, filter)
                        totalResult = totalResult.merge(result)
                    }
                }
                if (connectionDocument.availableCapabilities.contains(ConnectionCapability.CALENDAR_READ)) {
                    val filter = context.getProjectResourceFilter(project.id, client.id, ConnectionCapability.CALENDAR_READ)
                    if (filter != null) {
                        val result = pollCalendar(connectionDocument, client, project.id, token, filter)
                        totalResult = totalResult.merge(result)
                    }
                }
            }
        }

        return totalResult
    }

    // ── Gmail Polling ────────────────────────────────────────────────────

    private suspend fun pollGmail(
        connection: ConnectionDocument,
        client: ClientDocument,
        projectId: ProjectId?,
        token: String,
        resourceFilter: ResourceFilter,
    ): PollingResult {
        val labelIds = when (resourceFilter) {
            is ResourceFilter.IndexAll -> listOf("INBOX")
            is ResourceFilter.IndexSelected -> resourceFilter.resources
        }

        var created = 0
        var skipped = 0
        var errors = 0

        for (labelId in labelIds) {
            try {
                val result = pollGmailLabel(connection, client, projectId, token, labelId)
                created += result.first
                skipped += result.second
            } catch (e: Exception) {
                logger.error(e) { "GMAIL: Failed to poll label $labelId for '${connection.name}'" }
                errors++
            }
        }

        // Also poll SENT folder for thread context
        try {
            val sentResult = pollGmailLabel(connection, client, projectId, token, "SENT")
            created += sentResult.first
            skipped += sentResult.second
        } catch (e: Exception) {
            logger.debug { "GMAIL: SENT folder poll failed (non-critical): ${e.message}" }
        }

        return PollingResult(
            itemsDiscovered = created + skipped,
            itemsCreated = created,
            itemsSkipped = skipped,
            errors = errors,
        )
    }

    private suspend fun pollGmailLabel(
        connection: ConnectionDocument,
        client: ClientDocument,
        projectId: ProjectId?,
        token: String,
        labelId: String,
    ): Pair<Int, Int> {
        val tool = "GMAIL:$labelId"
        val state = pollingStateService.getState(connection.id, ProviderEnum.GOOGLE_WORKSPACE, tool)
        val afterEpoch = state?.lastSeenUpdatedAt?.epochSecond ?: (Instant.now().minus(7, ChronoUnit.DAYS).epochSecond)

        // List message IDs
        val listResponse = httpClient.get("https://gmail.googleapis.com/gmail/v1/users/me/messages") {
            header("Authorization", "Bearer $token")
            parameter("labelIds", labelId)
            parameter("q", "after:$afterEpoch")
            parameter("maxResults", "50")
        }
        if (!listResponse.status.isSuccess()) {
            logger.warn { "GMAIL: List messages failed: ${listResponse.status}" }
            return 0 to 0
        }

        val listBody = json.decodeFromString<GmailMessageListResponse>(listResponse.body())
        val messageIds = listBody.messages?.map { it.id } ?: emptyList()

        if (messageIds.isEmpty()) return 0 to 0

        var created = 0
        var skipped = 0
        var latestTimestamp = state?.lastSeenUpdatedAt ?: Instant.EPOCH

        for (msgId in messageIds) {
            // Check dedup by Gmail message ID (per-connection)
            if (emailRepository.existsByConnectionIdAndMessageUid(connection.id, msgId)) {
                skipped++
                continue
            }

            // Fetch full message
            val msgResponse = httpClient.get("https://gmail.googleapis.com/gmail/v1/users/me/messages/$msgId") {
                header("Authorization", "Bearer $token")
                parameter("format", "full")
            }
            if (!msgResponse.status.isSuccess()) {
                logger.warn { "GMAIL: Fetch message $msgId failed: ${msgResponse.status}" }
                continue
            }

            val msg = json.decodeFromString<GmailMessage>(msgResponse.body())

            // Parse headers
            val headers = msg.payload?.headers?.associate { it.name.lowercase() to it.value } ?: emptyMap()
            val messageIdHeader = headers["message-id"]
            val subject = headers["subject"]
            val from = headers["from"]
            val to = headers["to"]?.split(",")?.map { it.trim() } ?: emptyList()
            val cc = headers["cc"]?.split(",")?.map { it.trim() } ?: emptyList()
            val date = headers["date"]
            val inReplyTo = headers["in-reply-to"]
            val references = headers["references"]?.split("\\s+".toRegex())?.filter { it.isNotBlank() } ?: emptyList()

            // Cross-connection dedup: check if same email already indexed from IMAP
            if (messageIdHeader != null && emailRepository.existsByMessageId(messageIdHeader)) {
                skipped++
                continue
            }

            // Parse body
            val textBody = extractTextBody(msg.payload)
            val htmlBody = extractHtmlBody(msg.payload)

            // Parse date
            val receivedDate = try {
                Instant.ofEpochMilli(msg.internalDate?.toLongOrNull() ?: System.currentTimeMillis())
            } catch (_: Exception) {
                Instant.now()
            }

            // Detect direction from label
            val direction = if (labelId == "SENT" || msg.labelIds?.contains("SENT") == true) {
                EmailDirection.SENT
            } else {
                EmailDirection.RECEIVED
            }

            // Compute thread ID (RFC 2822 based, not Gmail threadId)
            val threadId = EmailMessageIndexDocument.computeThreadId(messageIdHeader, inReplyTo, references)

            val doc = EmailMessageIndexDocument(
                connectionId = connection.id,
                clientId = client.id,
                projectId = projectId,
                messageUid = msgId,
                messageId = messageIdHeader,
                subject = subject,
                from = from,
                to = to,
                cc = cc,
                receivedDate = receivedDate,
                sentDate = receivedDate,
                textBody = textBody,
                htmlBody = htmlBody,
                folder = labelId,
                direction = direction,
                inReplyTo = inReplyTo,
                references = references,
                threadId = threadId,
            )

            emailRepository.save(doc)
            created++

            if (receivedDate.isAfter(latestTimestamp)) {
                latestTimestamp = receivedDate
            }
        }

        // Update polling state
        if (latestTimestamp.isAfter(Instant.EPOCH)) {
            pollingStateService.updateWithTimestamp(connection.id, ProviderEnum.GOOGLE_WORKSPACE, latestTimestamp, tool)
        }

        logger.info { "GMAIL: label=$labelId created=$created skipped=$skipped" }
        return created to skipped
    }

    private fun extractTextBody(payload: GmailMessagePart?): String? {
        if (payload == null) return null
        if (payload.mimeType == "text/plain" && payload.body?.data != null) {
            return decodeBase64Url(payload.body.data)
        }
        for (part in payload.parts ?: emptyList()) {
            val result = extractTextBody(part)
            if (result != null) return result
        }
        return null
    }

    private fun extractHtmlBody(payload: GmailMessagePart?): String? {
        if (payload == null) return null
        if (payload.mimeType == "text/html" && payload.body?.data != null) {
            return decodeBase64Url(payload.body.data)
        }
        for (part in payload.parts ?: emptyList()) {
            val result = extractHtmlBody(part)
            if (result != null) return result
        }
        return null
    }

    private fun decodeBase64Url(encoded: String): String {
        val bytes = java.util.Base64.getUrlDecoder().decode(encoded)
        return String(bytes, Charsets.UTF_8)
    }

    // ── Calendar Polling ─────────────────────────────────────────────────

    private suspend fun pollCalendar(
        connection: ConnectionDocument,
        client: ClientDocument,
        projectId: ProjectId?,
        token: String,
        resourceFilter: ResourceFilter,
    ): PollingResult {
        val calendarIds = when (resourceFilter) {
            is ResourceFilter.IndexAll -> listOf("primary")
            is ResourceFilter.IndexSelected -> resourceFilter.resources
        }

        var created = 0
        var skipped = 0
        var errors = 0

        for (calendarId in calendarIds) {
            try {
                val result = pollCalendarEvents(connection, client, projectId, token, calendarId)
                created += result.first
                skipped += result.second
            } catch (e: Exception) {
                logger.error(e) { "CALENDAR: Failed to poll calendar $calendarId for '${connection.name}'" }
                errors++
            }
        }

        return PollingResult(
            itemsDiscovered = created + skipped,
            itemsCreated = created,
            itemsSkipped = skipped,
            errors = errors,
        )
    }

    private suspend fun pollCalendarEvents(
        connection: ConnectionDocument,
        client: ClientDocument,
        projectId: ProjectId?,
        token: String,
        calendarId: String,
    ): Pair<Int, Int> {
        val tool = "CALENDAR:$calendarId"
        val state = pollingStateService.getState(connection.id, ProviderEnum.GOOGLE_WORKSPACE, tool)

        // Fetch events from last poll time (or 1 day ago) to 7 days ahead
        val timeMin = state?.lastSeenUpdatedAt ?: Instant.now().minus(1, ChronoUnit.DAYS)
        val timeMax = Instant.now().plus(7, ChronoUnit.DAYS)

        val response = httpClient.get("https://www.googleapis.com/calendar/v3/calendars/$calendarId/events") {
            header("Authorization", "Bearer $token")
            parameter("timeMin", timeMin.toString())
            parameter("timeMax", timeMax.toString())
            parameter("singleEvents", "true")
            parameter("orderBy", "startTime")
            parameter("maxResults", "100")
        }
        if (!response.status.isSuccess()) {
            logger.warn { "CALENDAR: List events failed: ${response.status}" }
            return 0 to 0
        }

        val body = json.decodeFromString<GoogleCalendarEventsResponse>(response.body())
        val events = body.items ?: emptyList()

        if (events.isEmpty()) return 0 to 0

        var created = 0
        var updated = 0
        var skipped = 0

        for (event in events) {
            val eventId = event.id ?: continue

            val startTime = parseGoogleDateTime(event.start)
            val endTime = parseGoogleDateTime(event.end)
            if (startTime == null || endTime == null) continue

            val attendees = event.attendees?.mapNotNull { it.email } ?: emptyList()

            val videoEntryPoint = event.conferenceData?.entryPoints
                ?.firstOrNull { it.entryPointType.equals("video", ignoreCase = true) }
                ?.uri
            val joinUrl = event.hangoutLink ?: videoEntryPoint
            val isOnline = joinUrl != null
            val isCancelled = event.status.equals("cancelled", ignoreCase = true)

            val existing = calendarRepository.findByConnectionIdAndEventId(connection.id, eventId)
            if (existing != null) {
                // Skip if upstream etag matches what we already indexed.
                if (existing.etag != null && existing.etag == event.etag) {
                    skipped++
                    continue
                }
                // Update path: overwrite mutable fields, flip state back to NEW
                // so the indexer reprocesses and propagates changes to the task.
                val refreshed = existing.copy(
                    state = PollingStatusEnum.NEW,
                    title = event.summary ?: existing.title,
                    startTime = startTime,
                    endTime = endTime,
                    location = event.location,
                    description = event.description,
                    attendees = attendees,
                    isAllDay = event.start?.date != null,
                    isRecurring = event.recurringEventId != null,
                    organizer = event.organizer?.email,
                    isOnlineMeeting = isOnline,
                    onlineMeetingJoinUrl = joinUrl,
                    etag = event.etag,
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
                calendarId = calendarId,
                title = event.summary ?: "Untitled Event",
                startTime = startTime,
                endTime = endTime,
                location = event.location,
                description = event.description,
                attendees = attendees,
                isAllDay = event.start?.date != null,
                isRecurring = event.recurringEventId != null,
                organizer = event.organizer?.email,
                isOnlineMeeting = isOnline,
                onlineMeetingJoinUrl = joinUrl,
                etag = event.etag,
                isCancelled = isCancelled,
            )

            calendarRepository.save(doc)
            created++
        }

        // Update polling state
        pollingStateService.updateWithTimestamp(connection.id, ProviderEnum.GOOGLE_WORKSPACE, Instant.now(), tool)

        logger.info { "CALENDAR: calendar=$calendarId created=$created updated=$updated skipped=$skipped" }
        return (created + updated) to skipped
    }

    private fun parseGoogleDateTime(dt: GoogleDateTime?): Instant? {
        if (dt == null) return null
        // dateTime format: "2026-04-01T10:00:00+02:00"
        if (dt.dateTime != null) {
            return try {
                Instant.parse(dt.dateTime)
            } catch (_: Exception) {
                try {
                    java.time.OffsetDateTime.parse(dt.dateTime).toInstant()
                } catch (_: Exception) {
                    null
                }
            }
        }
        // All-day event: date format "2026-04-01"
        if (dt.date != null) {
            return try {
                java.time.LocalDate.parse(dt.date).atStartOfDay().toInstant(java.time.ZoneOffset.UTC)
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    private fun PollingResult.merge(other: PollingResult) = PollingResult(
        itemsDiscovered = this.itemsDiscovered + other.itemsDiscovered,
        itemsCreated = this.itemsCreated + other.itemsCreated,
        itemsSkipped = this.itemsSkipped + other.itemsSkipped,
        errors = this.errors + other.errors,
        authenticationError = this.authenticationError || other.authenticationError,
    )
}

// ── Gmail API DTOs ───────────────────────────────────────────────────────

@Serializable
private data class GmailMessageListResponse(
    val messages: List<GmailMessageRef>? = null,
    val nextPageToken: String? = null,
    val resultSizeEstimate: Int? = null,
)

@Serializable
private data class GmailMessageRef(
    val id: String,
    val threadId: String? = null,
)

@Serializable
private data class GmailMessage(
    val id: String,
    val threadId: String? = null,
    val labelIds: List<String>? = null,
    val payload: GmailMessagePart? = null,
    val internalDate: String? = null,
)

@Serializable
private data class GmailMessagePart(
    val mimeType: String? = null,
    val headers: List<GmailHeader> = emptyList(),
    val body: GmailBody? = null,
    val parts: List<GmailMessagePart>? = null,
)

@Serializable
private data class GmailHeader(
    val name: String,
    val value: String,
)

@Serializable
private data class GmailBody(
    val size: Int? = null,
    val data: String? = null,
)

// ── Google Calendar API DTOs ─────────────────────────────────────────────

@Serializable
private data class GoogleCalendarEventsResponse(
    val items: List<GoogleCalendarEventDto>? = null,
    val nextPageToken: String? = null,
)

@Serializable
private data class GoogleCalendarEventDto(
    val id: String? = null,
    val etag: String? = null,
    /** "confirmed", "tentative" or "cancelled". */
    val status: String? = null,
    val summary: String? = null,
    val description: String? = null,
    val location: String? = null,
    val start: GoogleDateTime? = null,
    val end: GoogleDateTime? = null,
    val attendees: List<GoogleAttendee>? = null,
    val organizer: GoogleOrganizer? = null,
    val recurringEventId: String? = null,
    /** Direct Google Meet link (legacy field, still populated for Meet meetings). */
    val hangoutLink: String? = null,
    /** Conference data for any provider (Meet, Zoom add-on, etc.). */
    val conferenceData: GoogleConferenceData? = null,
)

@Serializable
private data class GoogleConferenceData(
    val entryPoints: List<GoogleConferenceEntryPoint>? = null,
    val conferenceSolution: GoogleConferenceSolution? = null,
)

@Serializable
private data class GoogleConferenceEntryPoint(
    val entryPointType: String? = null,
    val uri: String? = null,
)

@Serializable
private data class GoogleConferenceSolution(
    val name: String? = null,
)

@Serializable
private data class GoogleDateTime(
    val dateTime: String? = null,
    val date: String? = null,
    val timeZone: String? = null,
)

@Serializable
private data class GoogleAttendee(
    val email: String? = null,
    val displayName: String? = null,
    val responseStatus: String? = null,
)

@Serializable
private data class GoogleOrganizer(
    val email: String? = null,
    val displayName: String? = null,
)
