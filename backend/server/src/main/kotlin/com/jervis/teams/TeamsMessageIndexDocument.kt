package com.jervis.teams

import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.common.types.ProjectId
import com.jervis.infrastructure.polling.PollingStatusEnum
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Teams message index - tracking which Teams messages have been processed.
 *
 * STATE MACHINE: NEW -> INDEXED (or FAILED)
 *
 * FLOW:
 * 1. O365PollingHandler fetches via O365 Gateway -> saves as NEW
 * 2. TeamsContinuousIndexer creates task -> converts to INDEXED
 * 3. Qualifier stores to RAG/Graph with sourceUrn
 */
@Document(collection = "teams_message_index")
@CompoundIndexes(
    CompoundIndex(name = "connection_state_idx", def = "{'connectionId': 1, 'state': 1}"),
    CompoundIndex(name = "connection_msgid_idx", def = "{'connectionId': 1, 'messageId': 1}", unique = true),
    CompoundIndex(name = "client_state_idx", def = "{'clientId': 1, 'state': 1}"),
    CompoundIndex(name = "chat_idx", def = "{'chatId': 1, 'createdDateTime': 1}"),
    CompoundIndex(name = "channel_idx", def = "{'teamId': 1, 'channelId': 1, 'createdDateTime': 1}"),
)
data class TeamsMessageIndexDocument(
    val id: ObjectId = ObjectId.get(),
    val connectionId: ConnectionId,
    val clientId: ClientId,
    val projectId: ProjectId? = null,
    val state: PollingStatusEnum = PollingStatusEnum.NEW,

    /** Unique message ID from Graph API */
    val messageId: String,
    /** Chat or channel context */
    val chatId: String? = null,
    val teamId: String? = null,
    val channelId: String? = null,
    /** Display names for context */
    val chatDisplayName: String? = null,
    val teamDisplayName: String? = null,
    val channelDisplayName: String? = null,

    /** Message content */
    val from: String? = null,
    val body: String? = null,
    val bodyContentType: String? = null,
    val createdDateTime: Instant,
    val subject: String? = null,

    /** Indexing metadata */
    val indexingError: String? = null,
)
