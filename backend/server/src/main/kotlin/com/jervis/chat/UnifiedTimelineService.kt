package com.jervis.chat

import com.jervis.dto.chat.ChatMessageDto
import com.jervis.dto.chat.ChatRole
import kotlinx.coroutines.reactive.awaitSingle
import mu.KotlinLogging
import org.bson.Document
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Unified timeline: builds MongoDB aggregation pipeline based on filter flags.
 * Merges chat_messages + tasks (USER_TASK) when K reakci is active.
 *
 * Filter flags are independent toggles (can combine):
 * - includeChat: user/assistant/system messages (non-BACKGROUND)
 * - includeTasks: ALL background task results
 * - includeNeedReaction: actionable items only (failed background + USER_TASK with question)
 *
 * DB does all filtering, projection, merge, and sort — no application-level sort/filter.
 */
@Service
class UnifiedTimelineService(
    private val mongoTemplate: ReactiveMongoTemplate,
) {
    /**
     * Load timeline with flexible filter combination, sorted by timestamp DESC.
     * Uses MongoDB aggregation pipeline with optional $unionWith for single-roundtrip merge.
     */
    suspend fun loadTimeline(
        conversationId: ObjectId,
        limit: Int,
        beforeTimestamp: String? = null,
        includeChat: Boolean = true,
        includeTasks: Boolean = false,
        includeNeedReaction: Boolean = true,
    ): Pair<List<ChatMessageDto>, Boolean> {
        // Build dynamic $or conditions for chat_messages based on active filters
        val orConditions = mutableListOf<Document>()

        if (includeChat) {
            // Non-background messages (USER, ASSISTANT, SYSTEM, ALERT)
            orConditions.add(Document("role", Document("\$ne", "BACKGROUND")))
        }

        if (includeTasks) {
            // ALL background messages
            orConditions.add(Document("role", "BACKGROUND"))
        } else if (includeNeedReaction) {
            // Only actionable background results (failed or explicitly marked, NOT dismissed)
            orConditions.add(
                Document("\$and", listOf(
                    Document("role", "BACKGROUND"),
                    Document("\$or", listOf(
                        Document("metadata.needsReaction", "true"),
                        Document("metadata.success", "false"),
                    )),
                    Document("metadata.dismissed", Document("\$ne", "true")),
                )),
            )
        }

        // If no conditions, return empty (shouldn't happen in practice)
        if (orConditions.isEmpty()) {
            return emptyList<ChatMessageDto>() to false
        }

        val chatMatch = if (orConditions.size == 1) {
            // Single condition — merge into conversationId match
            val condition = Document("conversationId", conversationId)
            orConditions[0].forEach { (k, v) -> condition.append(k, v) }
            Document("\$match", condition)
        } else {
            Document("\$match", Document("conversationId", conversationId).append("\$or", orConditions))
        }

        val chatProject = Document(
            "\$project", Document()
                .append("_sort_ts", "\$timestamp")
                .append("_source", Document("\$literal", "chat"))
                .append("role", "\$role")
                .append("content", "\$content")
                .append("timestamp", Document("\$toString", "\$timestamp"))
                .append("correlationId", "\$correlationId")
                .append("metadata", "\$metadata")
                .append("sequence", "\$sequence")
                .append("messageId", Document("\$toString", "\$_id")),
        )

        val pipeline = mutableListOf(chatMatch, chatProject)

        // $unionWith tasks only when K reakci is active
        if (includeNeedReaction) {
            val tasksPipeline = listOf(
                Document(
                    "\$match", Document("type", "USER_TASK")
                        .append("state", "USER_TASK"),
                ),
                Document(
                    "\$project", Document()
                        .append("_sort_ts", "\$createdAt")
                        .append("_source", Document("\$literal", "task"))
                        .append("role", Document("\$literal", "BACKGROUND"))
                        .append(
                            "content", Document(
                                "\$concat", listOf(
                                    Document("\$ifNull", listOf("\$pendingUserQuestion", Document("\$ifNull", listOf("\$taskName", "")))),
                                ),
                            ),
                        )
                        .append("timestamp", Document("\$toString", "\$createdAt"))
                        .append("correlationId", Document("\$literal", ""))
                        .append(
                            "metadata", Document(
                                "\$arrayToObject", listOf(
                                    listOf(
                                        Document("k", "taskId").append("v", Document("\$toString", "\$_id")),
                                        Document("k", "needsReaction").append("v", "true"),
                                        Document("k", "clientId").append("v", Document("\$toString", "\$clientId")),
                                        Document("k", "title").append("v", "\$taskName"),
                                        Document("k", "state").append("v", "\$state"),
                                    ),
                                ),
                            ),
                        )
                        .append("sequence", Document("\$literal", 0L))
                        .append("messageId", Document("\$toString", "\$_id")),
                ),
            )
            pipeline.add(Document("\$unionWith", Document("coll", "tasks").append("pipeline", tasksPipeline)))
        }

        // Pagination cursor
        if (beforeTimestamp != null) {
            val ts = java.time.Instant.parse(beforeTimestamp)
            pipeline.add(Document("\$match", Document("_sort_ts", Document("\$lt", ts))))
        }

        pipeline.add(Document("\$sort", Document("_sort_ts", -1))) // DESC — newest first
        pipeline.add(Document("\$limit", limit + 1)) // +1 to detect hasMore

        return try {
            val docs = mongoTemplate
                .getCollection("chat_messages")
                .flatMapMany { collection ->
                    collection.aggregate(pipeline)
                }
                .collectList()
                .awaitSingle()

            val hasMore = docs.size > limit
            val items = docs.take(limit)

            // Reverse to chronological (oldest first) for UI display
            val messages = items.reversed().map { doc -> docToDto(doc) }
            messages to hasMore
        } catch (e: Exception) {
            logger.error(e) { "Failed to load timeline (chat=$includeChat, tasks=$includeTasks, reaction=$includeNeedReaction)" }
            emptyList<ChatMessageDto>() to false
        }
    }

    private fun docToDto(doc: Document): ChatMessageDto {
        val role = when (doc.getString("role")) {
            "USER" -> ChatRole.USER
            "ASSISTANT" -> ChatRole.ASSISTANT
            "SYSTEM" -> ChatRole.SYSTEM
            "BACKGROUND" -> ChatRole.BACKGROUND
            "ALERT" -> ChatRole.ALERT
            else -> ChatRole.SYSTEM
        }

        @Suppress("UNCHECKED_CAST")
        val metadata = (doc.get("metadata") as? Document)
            ?.entries?.associate { it.key to (it.value?.toString() ?: "") }
            ?: emptyMap()

        return ChatMessageDto(
            role = role,
            content = doc.getString("content") ?: "",
            timestamp = doc.getString("timestamp") ?: "",
            correlationId = doc.getString("correlationId") ?: "",
            metadata = metadata,
            sequence = (doc.get("sequence") as? Number)?.toLong() ?: 0L,
            messageId = doc.getString("messageId") ?: "",
        )
    }
}
