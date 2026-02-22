package com.jervis.entity

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * ChatSummaryDocument — compressed summary of a block of chat messages.
 *
 * Rolling summaries for long conversations. Every 20 messages beyond the
 * recent window are compressed by LLM into a summary block.
 *
 * @property conversationId The conversation thread this summary belongs to
 * @property sequenceStart First message sequence number in this summary
 * @property sequenceEnd Last message sequence number in this summary
 * @property summary LLM-generated summary text (200-500 chars)
 * @property keyDecisions Important decisions extracted from the conversation
 * @property topics Conversation topics identified
 * @property isCheckpoint True if conversation direction changed significantly
 * @property checkpointReason Why this is a checkpoint (if applicable)
 * @property messageCount Number of messages compressed in this block
 */
@Document(collection = "chat_summaries")
@CompoundIndex(name = "conversation_seq_idx", def = "{'conversationId': 1, 'sequenceEnd': -1}")
data class ChatSummaryDocument(
    @Id val id: ObjectId = ObjectId(),
    @Indexed val conversationId: ObjectId,
    val sequenceStart: Long,
    val sequenceEnd: Long,
    val summary: String,
    val keyDecisions: List<String> = emptyList(),
    val topics: List<String> = emptyList(),
    val isCheckpoint: Boolean = false,
    val checkpointReason: String? = null,
    val messageCount: Int,
    val createdAt: Instant = Instant.now(),
)
