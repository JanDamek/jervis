package com.jervis.chat

import com.mongodb.client.model.Filters
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import mu.KotlinLogging
import org.bson.Document
import org.springframework.core.annotation.Order
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.stereotype.Component

/**
 * One-shot migration that splits the legacy `timestamp` field on
 * `chat_messages` into the domain pair `requestTime` (USER role) +
 * `responseTime` (every other role).
 *
 * Idempotent — runs `updateMany` filtered on `timestamp: { $exists: true }`,
 * so once the migration has touched a row the next startup is a no-op.
 *
 * Uses Mongo's pipeline-style `updateMany` (driver:
 * `updateMany(filter, pipeline)`) so `$set: {requestTime: "$timestamp"}`
 * can reference the existing field — Spring Data's `Update` DSL doesn't
 * expose this, so we go through the raw collection API.
 *
 * Runs at @Order(10) — after MongoIndexInitializer (@Order 0) so the new
 * `conversation_requestTime_idx` / `conversation_responseTime_idx` exist
 * by the time scans start.
 */
@Component
@Order(10)
class ChatMessageTimestampMigration(
    private val template: ReactiveMongoTemplate,
) {
    private val logger = KotlinLogging.logger {}

    @PostConstruct
    fun migrate() {
        try {
            kotlinx.coroutines.runBlocking { run() }
        } catch (e: Exception) {
            // Don't block server start — chat still works with the new schema;
            // only legacy rows that the migration somehow missed end up with
            // null requestTime + null responseTime, which `effectiveTimestamp`
            // falls back from defensively.
            logger.error(e) { "ChatMessage timestamp migration failed (continuing)" }
        }
    }

    private suspend fun run() {
        val collectionExists = template.collectionExists("chat_messages").awaitSingle()
        if (!collectionExists) {
            logger.info { "chat_messages timestamp migration | collection missing — nothing to do" }
            return
        }

        val collection = template.getCollection("chat_messages").awaitSingle()

        val pendingFilter = Filters.exists("timestamp", true)
        val pending = collection.countDocuments(pendingFilter).awaitSingle()
        if (pending == 0L) {
            logger.info { "chat_messages timestamp migration | no legacy rows — skipping" }
            return
        }
        logger.info { "chat_messages timestamp migration | $pending legacy rows — migrating" }

        // USER role → copy timestamp into requestTime, then unset timestamp.
        val userPipeline: List<Document> = listOf(
            Document("\$set", Document("requestTime", "\$timestamp")),
            Document("\$unset", "timestamp"),
        )
        val userFilter = Filters.and(
            Filters.eq("role", "USER"),
            Filters.exists("timestamp", true),
        )
        val userResult = collection.updateMany(userFilter, userPipeline).awaitFirstOrNull()
        val userModified = userResult?.modifiedCount ?: 0L

        // Every non-USER role → timestamp into responseTime, unset timestamp.
        val responsePipeline: List<Document> = listOf(
            Document("\$set", Document("responseTime", "\$timestamp")),
            Document("\$unset", "timestamp"),
        )
        val responseFilter = Filters.and(
            Filters.ne("role", "USER"),
            Filters.exists("timestamp", true),
        )
        val responseResult = collection.updateMany(responseFilter, responsePipeline).awaitFirstOrNull()
        val responseModified = responseResult?.modifiedCount ?: 0L

        logger.info {
            "chat_messages timestamp migration | done | " +
                "userMigrated=$userModified responseMigrated=$responseModified"
        }
    }
}
