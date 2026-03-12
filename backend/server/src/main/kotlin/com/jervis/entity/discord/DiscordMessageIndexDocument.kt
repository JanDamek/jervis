package com.jervis.entity.discord

import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.common.types.ProjectId
import com.jervis.domain.PollingStatusEnum
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Discord message index - tracking which Discord messages have been processed.
 *
 * STATE MACHINE: NEW -> INDEXED (or FAILED)
 *
 * FLOW:
 * 1. DiscordPollingHandler fetches via Discord REST API → saves as NEW
 * 2. DiscordContinuousIndexer creates task → converts to INDEXED
 * 3. Qualifier stores to RAG/Graph with sourceUrn
 */
@Document(collection = "discord_message_index")
@CompoundIndexes(
    CompoundIndex(name = "connection_state_idx", def = "{'connectionId': 1, 'state': 1}"),
    CompoundIndex(name = "connection_msgid_idx", def = "{'connectionId': 1, 'messageId': 1}", unique = true),
    CompoundIndex(name = "client_state_idx", def = "{'clientId': 1, 'state': 1}"),
    CompoundIndex(name = "channel_idx", def = "{'guildId': 1, 'channelId': 1, 'createdDateTime': 1}"),
)
data class DiscordMessageIndexDocument(
    val id: ObjectId = ObjectId.get(),
    val connectionId: ConnectionId,
    val clientId: ClientId,
    val projectId: ProjectId? = null,
    val state: PollingStatusEnum = PollingStatusEnum.NEW,

    /** Unique message ID from Discord */
    val messageId: String,
    /** Guild (server) and channel context */
    val guildId: String,
    val channelId: String,
    val guildName: String? = null,
    val channelName: String? = null,

    /** Message content */
    val from: String? = null,
    val body: String? = null,
    val createdDateTime: Instant,

    /** Indexing metadata */
    val indexingError: String? = null,
)
