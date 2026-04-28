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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitFirstOrNull
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Component
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * O365 (Microsoft Outlook) calendar polling component.
 *
 * Reads `scraped_calendar` rows produced by the O365 browser-pool pod
 * agent (which scrapes Outlook / Teams calendar UI in the user's browser
 * session and upserts events into Mongo) and converts NEW rows into
 * `CalendarEventIndexDocument` for the existing indexing flow. Marks
 * processed rows as PROCESSED so the next poll cycle skips them.
 *
 * No Microsoft Graph or Azure AD calls anywhere — see commits 6c768af9e
 * and 04883c82c. The browser pod owns the session; the server owns
 * Mongo-only reads.
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
    private val scrapedCalendarRepository: O365ScrapedCalendarRepository,
    private val mongoTemplate: ReactiveMongoTemplate,
) {
    /**
     * Run a calendar polling cycle for an O365 connection. Reads NEW rows
     * from `scraped_calendar` (written by the browser-pool pod agent) and
     * folds them into `CalendarEventIndexDocument`.
     */
    suspend fun poll(
        connection: ConnectionDocument,
        context: PollingContext,
        @Suppress("UNUSED_PARAMETER") o365ClientId: String?,
    ): PollingResult {
        val connectionOid: ObjectId = connection.id.value
        val rows = scrapedCalendarRepository
            .findByConnectionIdAndState(connectionOid, "NEW")
            .toList()
        if (rows.isEmpty()) {
            pollingStateService.updateWithTimestamp(
                connection.id, ProviderEnum.MICROSOFT_TEAMS, Instant.now(), "O365_CALENDAR:primary",
            )
            return PollingResult()
        }

        var totalResult = PollingResult()
        for (client in context.clients) {
            val clientLevelProjectId = client.defaultProjectId
            val clientFilter = context.getResourceFilter(client.id, ConnectionCapability.CALENDAR_READ)
            if (clientFilter != null) {
                totalResult = totalResult.merge(
                    foldRows(connection, client, clientLevelProjectId, rows, clientFilter),
                )
            }
            for (project in context.projects.filter { it.clientId == client.id }) {
                val projectFilter = context.getProjectResourceFilter(
                    projectId = project.id,
                    clientId = client.id,
                    capability = ConnectionCapability.CALENDAR_READ,
                )
                if (projectFilter != null) {
                    totalResult = totalResult.merge(
                        foldRows(connection, client, project.id, rows, projectFilter),
                    )
                }
            }
        }

        // Mark all consumed rows as PROCESSED so the next poll skips them.
        // We mark unconditionally even when no client/project filter matched —
        // there is no value in re-reading an event that nobody routes.
        markProcessed(rows)
        pollingStateService.updateWithTimestamp(
            connection.id, ProviderEnum.MICROSOFT_TEAMS, Instant.now(), "O365_CALENDAR:primary",
        )
        return totalResult
    }

    private fun PollingResult.merge(other: PollingResult) = PollingResult(
        itemsDiscovered = itemsDiscovered + other.itemsDiscovered,
        itemsCreated = itemsCreated + other.itemsCreated,
        itemsSkipped = itemsSkipped + other.itemsSkipped,
        errors = errors + other.errors,
        authenticationError = authenticationError || other.authenticationError,
    )

    private suspend fun foldRows(
        connection: ConnectionDocument,
        client: ClientDocument,
        projectId: ProjectId?,
        rows: List<O365ScrapedCalendarDocument>,
        @Suppress("UNUSED_PARAMETER") resourceFilter: ResourceFilter,
    ): PollingResult {
        var created = 0
        var updated = 0
        var skipped = 0

        for (row in rows) {
            val startTime = parseScrapeInstant(row.startAt) ?: continue
            val endTime = parseScrapeInstant(row.endAt) ?: continue
            val joinUrl = row.joinUrl?.takeIf { it.isNotBlank() }
            val isOnline = !joinUrl.isNullOrBlank()

            val existing = calendarRepository.findByConnectionIdAndEventId(connection.id, row.externalId)
            if (existing != null) {
                // Etag-style dedup: scrape timestamp is the freshness signal
                val rowEtag = row.updatedAt?.toString()
                if (existing.etag != null && existing.etag == rowEtag) {
                    skipped++
                    continue
                }
                val refreshed = existing.copy(
                    state = PollingStatusEnum.NEW,
                    title = row.title ?: existing.title,
                    startTime = startTime,
                    endTime = endTime,
                    organizer = row.organizer,
                    isOnlineMeeting = isOnline,
                    onlineMeetingJoinUrl = joinUrl,
                    etag = rowEtag,
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
                eventId = row.externalId,
                calendarId = "primary",
                provider = CalendarProvider.MICROSOFT_OUTLOOK,
                title = row.title ?: "Untitled Event",
                startTime = startTime,
                endTime = endTime,
                organizer = row.organizer,
                isOnlineMeeting = isOnline,
                onlineMeetingJoinUrl = joinUrl,
                etag = row.updatedAt?.toString(),
            )
            calendarRepository.save(doc)
            created++
        }

        if (created > 0 || updated > 0) {
            logger.info {
                "O365_CALENDAR: connection=${connection.name} created=$created updated=$updated skipped=$skipped (from scraped_calendar)"
            }
        }
        return PollingResult(
            itemsDiscovered = created + updated + skipped,
            itemsCreated = created + updated,
            itemsSkipped = skipped,
        )
    }

    private suspend fun markProcessed(rows: List<O365ScrapedCalendarDocument>) {
        if (rows.isEmpty()) return
        val ids = rows.map { it.id }
        val query = Query(Criteria.where("_id").`in`(ids))
        val update = Update().set("state", "PROCESSED").set("processedAt", Instant.now())
        try {
            mongoTemplate.updateMulti(query, update, O365ScrapedCalendarDocument::class.java).awaitFirstOrNull()
        } catch (e: Exception) {
            logger.warn(e) { "O365_CALENDAR: failed to mark rows as PROCESSED" }
        }
    }

    /**
     * Parse the loose ISO timestamp the scraper agent stores. Browser-pool
     * normalizes Outlook's "MM/dd/yyyy h:mm a" / "dd.MM.yyyy HH:mm" into
     * ISO-8601 already, so a simple `Instant.parse` is enough; otherwise
     * the row is dropped (no calendar entry created).
     */
    private fun parseScrapeInstant(raw: String?): Instant? {
        val s = raw?.takeIf { it.isNotBlank() } ?: return null
        val normalized = if (s.endsWith("Z") || s.contains("+")) s else "${s}Z"
        return try {
            Instant.parse(normalized)
        } catch (_: Exception) {
            null
        }
    }
}

