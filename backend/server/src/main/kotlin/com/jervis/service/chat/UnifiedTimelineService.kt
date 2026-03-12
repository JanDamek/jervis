package com.jervis.service.chat

import com.jervis.dto.ChatMessageDto
import com.jervis.dto.ChatRole
import com.jervis.entity.MessageRole
import kotlinx.coroutines.reactive.awaitSingle
import mu.KotlinLogging
import org.bson.Document
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Unified timeline: merges chat_messages (needsReaction) + tasks (USER_TASK) into a single
 * chronologically sorted stream using MongoDB $unionWith aggregation.
 *
 * "K reakci" filter: only items that actually need user attention:
 * - USER_TASK tasks with pendingUserQuestion (orchestrator asking user)
 * - FAILED background results from chat (success=false)
 * - Background results explicitly marked needsReaction=true
 *
 * DB does all filtering, projection, merge, and sort — no application-level sort/filter.
 */
@Service
class UnifiedTimelineService(
    private val mongoTemplate: ReactiveMongoTemplate,
) {
    /**
     * Load "K reakci" timeline: actionable items only, sorted by createdAt DESC.
     * Uses MongoDB aggregation pipeline with $unionWith for single-roundtrip merge.
     *
     * @param conversationId Active chat session ID
     * @param limit Max items to return
     * @param beforeTimestamp Cursor for pagination (ISO-8601 timestamp string)
     * @return Pair of (messages sorted chronologically, hasMore flag)
     */
    suspend fun loadUnifiedTimeline(
        conversationId: ObjectId,
        limit: Int,
        beforeTimestamp: String? = null,
    ): Pair<List<ChatMessageDto>, Boolean> {
        // Build aggregation pipeline:
        // 1. Match chat_messages for this conversation that need reaction
        //    (BACKGROUND role with metadata.needsReaction=true OR metadata.success=false)
        // 2. Project to common shape {_sort_ts, _source, ...}
        // 3. $unionWith tasks collection (USER_TASK with pendingUserQuestion), projected to same shape
        // 4. Optional $match for pagination cursor
        // 5. $sort by _sort_ts DESC (newest first = by createdAt)
        // 6. $limit + 1 (to detect hasMore)

        // Chat messages that need reaction: BACKGROUND role + (needsReaction=true OR success=false)
        val chatMatch = Document(
            "\$match", Document("conversationId", conversationId)
                .append("role", "BACKGROUND")
                .append(
                    "\$or", listOf(
                        Document("metadata.needsReaction", "true"),
                        Document("metadata.success", "false"),
                    ),
                ),
        )
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

        // Tasks pipeline: USER_TASK with pendingUserQuestion (actual questions from orchestrator)
        val tasksPipeline = listOf(
            Document(
                "\$match", Document("type", "USER_TASK")
                    .append("state", "USER_TASK")
                    .append(
                        "pendingUserQuestion", Document("\$exists", true)
                            .append("\$ne", null),
                    ),
            ),
            Document(
                "\$project", Document()
                    .append("_sort_ts", "\$createdAt")
                    .append("_source", Document("\$literal", "task"))
                    .append("role", Document("\$literal", "BACKGROUND"))
                    .append(
                        "content", Document(
                            "\$concat", listOf(
                                Document("\$ifNull", listOf("\$pendingUserQuestion", "")),
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
        val unionWith = Document("\$unionWith", Document("coll", "tasks").append("pipeline", tasksPipeline))

        // Pagination cursor
        val paginationMatch = if (beforeTimestamp != null) {
            val ts = java.time.Instant.parse(beforeTimestamp)
            Document("\$match", Document("_sort_ts", Document("\$lt", ts)))
        } else null

        val sort = Document("\$sort", Document("_sort_ts", -1)) // DESC — newest first (createdAt)
        val limitStage = Document("\$limit", limit + 1) // +1 to detect hasMore

        val pipeline = mutableListOf(chatMatch, chatProject, unionWith)
        if (paginationMatch != null) pipeline.add(paginationMatch)
        pipeline.add(sort)
        pipeline.add(limitStage)

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
            logger.error(e) { "Failed to load unified timeline" }
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
