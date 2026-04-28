package com.jervis.teams

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Maps to the `scraped_calendar` collection written by the Python O365
 * browser-pool pod (`scrape_storage.store_calendar_event`).
 *
 * The browser-pool agent scrapes Outlook / Teams calendar UI and upserts
 * events here. The server side reads NEW rows, converts them into
 * `CalendarEventIndexDocument` rows for the existing indexing flow, and
 * marks them as PROCESSED. There is no Microsoft Graph call anywhere on
 * the server — the browser pod owns the session.
 */
@Document(collection = "scraped_calendar")
@CompoundIndexes(
    CompoundIndex(name = "connection_event_unique", def = "{'connectionId': 1, 'externalId': 1}", unique = true),
    CompoundIndex(name = "connection_state_idx", def = "{'connectionId': 1, 'state': 1}"),
)
data class O365ScrapedCalendarDocument(
    @Id val id: ObjectId = ObjectId.get(),
    val clientId: ObjectId,
    val connectionId: ObjectId,
    val externalId: String,
    val title: String? = null,
    val startAt: String? = null,
    val endAt: String? = null,
    val organizer: String? = null,
    val joinUrl: String? = null,
    val state: String = "NEW",
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
)
