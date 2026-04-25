package com.jervis.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitSingle
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * ChatMessageService - manages conversation messages.
 *
 * Responsibilities:
 * - Add new messages to conversation (with auto-increment sequence)
 * - Load messages for UI display (last N, pagination)
 * - Load full history for agent
 * - Search messages by content
 */
@Service
class ChatMessageService(
    private val chatMessageRepository: ChatMessageRepository,
    private val mongoTemplate: ReactiveMongoTemplate,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Add a new message to conversation.
     * Automatically assigns next sequence number.
     */
    suspend fun addMessage(
        conversationId: ObjectId,
        role: MessageRole,
        content: String,
        correlationId: String,
        metadata: Map<String, String> = emptyMap(),
        clientMessageId: String? = null,
        clientId: String? = null,
        projectId: String? = null,
        groupId: String? = null,
    ): ChatMessageDocument {
        require(content.isNotBlank()) { "Message content cannot be blank" }

        // Dedup check — return existing message instead of creating a duplicate
        if (clientMessageId != null && chatMessageRepository.existsByClientMessageId(clientMessageId)) {
            logger.info { "MESSAGE_DEDUP | conversationId=$conversationId | clientMessageId=$clientMessageId" }
            val existing = chatMessageRepository.findByClientMessageId(clientMessageId)
            if (existing != null) return existing
            // If existsBy returned true but findBy returned null (race), fall through to create
        }

        val nextSequence = getNextSequenceAtomic(conversationId)

        // Domain timestamps split: USER carries requestTime (when the turn
        // landed), every other role carries responseTime (when the response
        // was produced). Sort & display fall back via responseTime ?: requestTime.
        val now = Instant.now()
        val isUser = role == MessageRole.USER
        val message = ChatMessageDocument(
            id = ObjectId(),
            conversationId = conversationId,
            correlationId = correlationId,
            role = role,
            content = content,
            requestTime = if (isUser) now else null,
            responseTime = if (isUser) null else now,
            sequence = nextSequence,
            metadata = metadata,
            clientMessageId = clientMessageId,
            clientId = clientId,
            projectId = projectId,
            groupId = groupId,
        )

        val saved = chatMessageRepository.save(message)

        logger.info {
            "MESSAGE_ADDED | conversationId=$conversationId | role=$role | sequence=$nextSequence | " +
                "contentLength=${content.length} | correlationId=$correlationId"
        }

        return saved
    }

    /**
     * Load last N messages for a conversation (for UI initial display).
     * Returns messages in chronological order (oldest first).
     * Sorted by _id (ObjectId) which is monotonically increasing — immune to sequence counter desync.
     */
    suspend fun getLastMessages(
        conversationId: ObjectId,
        limit: Int = 10,
        excludeBackground: Boolean = false,
    ): List<ChatMessageDocument> {
        require(limit > 0) { "Limit must be positive" }

        val messages = if (excludeBackground) {
            // Chat mode: exclude ALL BACKGROUND messages (strict chat-only)
            chatMessageRepository
                .findByConversationIdAndRoleNotOrderByIdDesc(conversationId, MessageRole.BACKGROUND)
                .take(limit)
                .toList()
        } else {
            chatMessageRepository
                .findByConversationIdOrderByIdDesc(conversationId)
                .take(limit)
                .toList()
        }

        logger.debug { "MESSAGES_LOADED | conversationId=$conversationId | count=${messages.size} | limit=$limit | excludeBg=$excludeBackground" }

        return messages.reversed()
    }

    /**
     * Load all messages for a conversation (for agent to read full history).
     * Returns messages in chronological order.
     */
    suspend fun getAllMessages(conversationId: ObjectId): List<ChatMessageDocument> {
        val messages = chatMessageRepository.findByConversationIdOrderByIdAsc(conversationId).toList()

        logger.debug { "ALL_MESSAGES_LOADED | conversationId=$conversationId | count=${messages.size}" }

        return messages
    }

    /**
     * Load messages before a specific message ID (for pagination/"load more").
     * Uses ObjectId comparison — chronologically correct regardless of sequence values.
     */
    suspend fun getMessagesBefore(
        conversationId: ObjectId,
        beforeId: ObjectId,
        limit: Int = 10,
        excludeBackground: Boolean = false,
    ): List<ChatMessageDocument> {
        require(limit > 0) { "Limit must be positive" }

        val messages = if (excludeBackground) {
            // Chat mode: exclude ALL BACKGROUND messages (strict chat-only)
            chatMessageRepository
                .findByConversationIdAndRoleNotAndIdLessThanOrderByIdDesc(conversationId, MessageRole.BACKGROUND, beforeId)
                .toList()
                .take(limit)
        } else {
            chatMessageRepository
                .findByConversationIdAndIdLessThanOrderByIdDesc(conversationId, beforeId)
                .toList()
                .take(limit)
        }

        logger.debug {
            "MESSAGES_BEFORE_ID | conversationId=$conversationId | beforeId=$beforeId | " +
                "count=${messages.size} | limit=$limit | excludeBg=$excludeBackground"
        }

        return messages.reversed()
    }

    /**
     * Search messages by content (for agent search tool).
     */
    suspend fun searchMessages(
        conversationId: ObjectId,
        searchText: String,
    ): List<ChatMessageDocument> {
        require(searchText.isNotBlank()) { "Search text cannot be blank" }

        val messages =
            chatMessageRepository
                .findByConversationIdAndContentContainingIgnoreCase(conversationId, searchText)
                .toList()

        logger.info {
            "MESSAGES_SEARCHED | conversationId=$conversationId | searchText='$searchText' | found=${messages.size}"
        }

        return messages
    }

    /**
     * Delete all messages for a conversation.
     */
    suspend fun deleteAllMessages(conversationId: ObjectId): Long {
        val deletedCount = chatMessageRepository.deleteByConversationId(conversationId)

        logger.info { "MESSAGES_DELETED | conversationId=$conversationId | count=$deletedCount" }

        return deletedCount
    }

    /**
     * Get message count for a conversation.
     */
    suspend fun getMessageCount(conversationId: ObjectId, excludeBackground: Boolean = false): Long =
        if (excludeBackground) {
            // Chat mode: exclude ALL BACKGROUND messages (strict chat-only)
            chatMessageRepository.countByConversationIdAndRoleNot(conversationId, MessageRole.BACKGROUND)
        } else {
            chatMessageRepository.countByConversationId(conversationId)
        }

    /**
     * Count messages by role in a conversation (e.g. count BACKGROUND messages for badge).
     */
    suspend fun countByRole(conversationId: ObjectId, role: MessageRole): Long =
        chatMessageRepository.countByConversationIdAndRole(conversationId, role)

    /**
     * Count actionable BACKGROUND messages (needsReaction=true OR success=false).
     * Used for "K reakci" badge — combined with USER_TASK count.
     */
    suspend fun countActionableBackground(conversationId: ObjectId): Long =
        chatMessageRepository.countActionableBackground(conversationId)

    /**
     * Find actionable BACKGROUND messages (needsReaction=true OR success=false).
     * Used by dismissAll to mark them as dismissed.
     */
    fun findActionableBackground(conversationId: ObjectId): Flow<ChatMessageDocument> =
        chatMessageRepository.findActionableBackground(conversationId)

    /**
     * Save a chat message (update existing or insert new).
     */
    suspend fun save(message: ChatMessageDocument): ChatMessageDocument =
        chatMessageRepository.save(message)

    /**
     * Load messages filtered to a specific role only (newest first, chronological order).
     * Used for "Tasky" filter — BACKGROUND-only messages from DB.
     */
    suspend fun getMessagesByRole(
        conversationId: ObjectId,
        role: MessageRole,
        limit: Int = 10,
    ): List<ChatMessageDocument> {
        require(limit > 0) { "Limit must be positive" }
        val messages = chatMessageRepository
            .findByConversationIdAndRoleOrderByIdDesc(conversationId, role)
            .take(limit)
            .toList()
        return messages.reversed()
    }

    /**
     * Load messages by role before a cursor (newest first, chronological order).
     * Used for "Tasky" filter pagination.
     */
    suspend fun getMessagesByRoleBefore(
        conversationId: ObjectId,
        role: MessageRole,
        beforeId: ObjectId,
        limit: Int = 10,
    ): List<ChatMessageDocument> {
        require(limit > 0) { "Limit must be positive" }
        val messages = chatMessageRepository
            .findByConversationIdAndRoleAndIdLessThanOrderByIdDesc(conversationId, role, beforeId)
            .toList()
            .take(limit)
        return messages.reversed()
    }

    /**
     * Find a message by client-generated ID (for deduplication).
     */
    suspend fun findByClientMessageId(clientMessageId: String): ChatMessageDocument? =
        chatMessageRepository.findByClientMessageId(clientMessageId)

    /**
     * Scope-aware message query using ReactiveMongoTemplate with dynamic Criteria.
     *
     * Filtering logic:
     * - No scope (filterClientId=null) → return all messages (backward compat)
     * - With clientId → messages matching clientId OR null (legacy) OR affectedScopes containing clientId (cross-context masters)
     * - With projectId → further filter by projectId
     * - With groupProjectIds → filter by projectId IN groupProjectIds (group scope)
     *
     * Sets isOutOfScope on returned documents based on scope match.
     */
    suspend fun getMessagesWithScope(
        conversationId: ObjectId,
        limit: Int = 20,
        beforeId: ObjectId? = null,
        filterClientId: String? = null,
        filterProjectId: String? = null,
        groupProjectIds: List<String>? = null,
        showChat: Boolean = true,
        showTasks: Boolean = false,
        showNeedReaction: Boolean = true,
    ): List<ChatMessageDocument> {
        // Build all conditions as $and elements to avoid orOperator overwrite
        val andConditions = mutableListOf<Criteria>()

        // Base: conversationId
        andConditions.add(Criteria.where("conversationId").`is`(conversationId))

        // Pagination cursor
        if (beforeId != null) {
            andConditions.add(Criteria.where("_id").lt(beforeId))
        }

        // Build scope criteria for client-scoped messages
        val scopeOr = mutableListOf<Criteria>()
        if (filterClientId != null) {
            scopeOr.add(Criteria.where("clientId").`is`(filterClientId))
            scopeOr.add(Criteria.where("affectedScopes.clientId").`is`(filterClientId))
            if (filterProjectId != null) {
                scopeOr.clear()
                scopeOr.addAll(listOf(
                    Criteria().andOperator(
                        Criteria.where("clientId").`is`(filterClientId),
                        Criteria.where("projectId").`is`(filterProjectId),
                    ),
                    Criteria().andOperator(
                        Criteria.where("clientId").`is`(filterClientId),
                        Criteria.where("projectId").`is`(null),
                    ),
                    Criteria.where("affectedScopes.clientId").`is`(filterClientId),
                ))
            } else if (groupProjectIds != null && groupProjectIds.isNotEmpty()) {
                scopeOr.clear()
                scopeOr.addAll(listOf(
                    Criteria().andOperator(
                        Criteria.where("clientId").`is`(filterClientId),
                        Criteria.where("projectId").`in`(groupProjectIds),
                    ),
                    Criteria().andOperator(
                        Criteria.where("clientId").`is`(filterClientId),
                        Criteria.where("projectId").`is`(null),
                    ),
                    Criteria.where("affectedScopes.clientId").`is`(filterClientId),
                ))
            }
        }

        // Role filtering — scoped messages (Chat/Tasky) + global K reakci
        val roleFilters = mutableListOf<Criteria>()
        if (showChat) {
            val chatRole = Criteria.where("role").`in`(
                MessageRole.USER.name, MessageRole.ASSISTANT.name, MessageRole.SYSTEM.name,
            )
            // Chat messages are scoped to client
            if (scopeOr.isNotEmpty()) {
                roleFilters.add(Criteria().andOperator(chatRole, Criteria().orOperator(*scopeOr.toTypedArray())))
            } else {
                roleFilters.add(chatRole)
            }
        }
        if (showTasks) {
            val tasksRole = Criteria.where("role").`is`(MessageRole.BACKGROUND.name)
            // Background tasks are scoped to client
            if (scopeOr.isNotEmpty()) {
                roleFilters.add(Criteria().andOperator(tasksRole, Criteria().orOperator(*scopeOr.toTypedArray())))
            } else {
                roleFilters.add(tasksRole)
            }
        }
        if (showNeedReaction) {
            // K reakci items are GLOBAL — no scope filter. Badge is global, display must match.
            roleFilters.add(Criteria().andOperator(
                Criteria.where("role").`in`(MessageRole.BACKGROUND.name, MessageRole.ALERT.name),
                Criteria().orOperator(
                    Criteria.where("metadata.needsReaction").`is`("true"),
                    Criteria.where("metadata.success").`is`("false"),
                ),
                Criteria.where("metadata.dismissed").ne("true"),
            ))
        }
        if (roleFilters.isNotEmpty()) {
            andConditions.add(Criteria().orOperator(*roleFilters.toTypedArray()))
        } else {
            // All filters OFF → return nothing
            return emptyList()
        }

        // Simple find query — no aggregation for now (isOutOfScope computed in-code for reliability)
        val combinedCriteria = Criteria().andOperator(*andConditions.toTypedArray())
        val query = Query(combinedCriteria)
            .with(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "_id"))
            .limit(limit)

        logger.info {
            "SCOPE_QUERY_START | conversationId=$conversationId | clientId=$filterClientId | projectId=$filterProjectId | " +
                "groupProjectIds=$groupProjectIds | showChat=$showChat | showTasks=$showTasks | showReaction=$showNeedReaction | " +
                "andConditions=${andConditions.size} | roleFilters=${roleFilters.size}"
        }

        val messages = try {
            kotlinx.coroutines.withTimeout(10_000) {
                mongoTemplate.find(query, ChatMessageDocument::class.java, "chat_messages")
                    .collectList()
                    .awaitSingle()
            }
        } catch (e: Exception) {
            logger.error(e) { "SCOPE_QUERY_ERROR | query=$combinedCriteria" }
            emptyList()
        }

        logger.info { "SCOPE_QUERY_RESULT | count=${messages.size} | limit=$limit" }

        return messages.reversed()
    }

    /**
     * Atomic sequence counter using MongoDB findAndModify with $inc.
     * Prevents race conditions when parallel callers request next sequence.
     *
     * Uses a dedicated "chat_sequence_counters" collection (same as Python handler).
     */
    private suspend fun getNextSequenceAtomic(conversationId: ObjectId): Long {
        data class SequenceCounter(val counter: Long = 0)

        val result = mongoTemplate.findAndModify(
            Query(Criteria.where("_id").`is`("seq_$conversationId")),
            Update().inc("counter", 1),
            FindAndModifyOptions.options().returnNew(true).upsert(true),
            SequenceCounter::class.java,
            "chat_sequence_counters",
        ).awaitSingle()

        return result.counter
    }
}
