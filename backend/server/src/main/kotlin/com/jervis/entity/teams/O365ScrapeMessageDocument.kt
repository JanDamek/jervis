package com.jervis.entity.teams

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Maps to the `o365_scrape_messages` collection written by the Python browser pool.
 *
 * The browser pool VLM scraper extracts individual messages from screenshots
 * and stores them with state=NEW. The Kotlin polling handler reads NEW messages,
 * converts them to TeamsMessageIndexDocument, and marks them as PROCESSED.
 */
@Document(collection = "o365_scrape_messages")
@CompoundIndexes(
    CompoundIndex(name = "connection_msg_unique", def = "{'connectionId': 1, 'messageHash': 1}", unique = true),
    CompoundIndex(name = "connection_state_idx", def = "{'connectionId': 1, 'state': 1}"),
)
data class O365ScrapeMessageDocument(
    @Id val id: ObjectId = ObjectId.get(),
    val clientId: String,
    val connectionId: String,
    val messageHash: String,
    val sender: String? = null,
    val content: String? = null,
    val timestamp: String? = null,
    val chatName: String? = null,
    val messageType: String? = null, // "chat", "email", "calendar"
    val state: String = "NEW",
    val createdAt: Instant? = null,
)
