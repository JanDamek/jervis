package com.jervis.service.listener

import com.jervis.domain.authentication.ServiceType
import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.mongo.ServiceMessageDocument
import com.jervis.repository.vector.VectorStorageRepository
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Instant

/**
 * Service responsible for synchronizing external service messages with RAG
 */
@Service
class ServiceMessageSyncService(
    private val mongoTemplate: ReactiveMongoTemplate,
    private val vectorStorageRepository: VectorStorageRepository,
) {
    private val logger = LoggerFactory.getLogger(ServiceMessageSyncService::class.java)

    /**
     * Process new messages from a poll result
     */
    suspend fun processNewMessages(result: ListenerPollResult) {
        logger.info("Processing ${result.newMessages.size} new messages for service ${result.serviceType}")

        result.newMessages.forEach { message ->
            try {
                processMessage(message)
            } catch (e: Exception) {
                logger.error("Error processing message ${message.id} from ${result.serviceType}", e)
            }
        }
    }

    /**
     * Process a single message - store in DB and index in RAG
     */
    private suspend fun processMessage(message: ServiceMessage) {
        val contentHash = calculateHash(message.content)

        val existingMessage =
            mongoTemplate
                .findOne(
                    Query
                        .query(
                            Criteria
                                .where("serviceType")
                                .`is`(message.serviceType)
                                .and("serviceMessageId")
                                .`is`(message.id),
                        ),
                    ServiceMessageDocument::class.java,
                ).awaitFirstOrNull()

        if (existingMessage != null) {
            if (existingMessage.contentHash == contentHash) {
                logger.debug("Message ${message.id} already indexed with same content")
                updateLastChecked(existingMessage.id)
                return
            } else {
                logger.info("Message ${message.id} content changed, re-indexing")
                removeFromRag(existingMessage)
            }
        }

        val messageDoc =
            ServiceMessageDocument(
                clientId = message.clientId,
                projectId = message.projectId,
                serviceType = message.serviceType,
                serviceMessageId = message.id,
                threadId = message.threadId,
                channelId = message.channelId,
                author = message.author,
                content = message.content,
                contentHash = contentHash,
                messageTimestamp = message.timestamp,
                metadata = message.metadata,
            )

        val saved =
            mongoTemplate
                .save(messageDoc)
                .awaitSingle()

        indexInRag(saved)
    }

    /**
     * Index a message in RAG
     * TODO: Implement full embedding and indexing with proper embedding service
     */
    private suspend fun indexInRag(messageDoc: ServiceMessageDocument) {
        try {
            mongoTemplate
                .updateFirst(
                    Query.query(Criteria.where("_id").`is`(messageDoc.id)),
                    Update()
                        .set("isIndexedInRag", false)
                        .set("indexedAt", Instant.now())
                        .set("updatedAt", Instant.now()),
                    ServiceMessageDocument::class.java,
                ).awaitFirstOrNull()

            logger.info("Stored message ${messageDoc.serviceMessageId} for future RAG indexing")
        } catch (e: Exception) {
            logger.error("Error storing message ${messageDoc.serviceMessageId}", e)
        }
    }

    /**
     * Remove deleted messages from RAG
     */
    suspend fun processDeletedMessages(result: ListenerPollResult) {
        logger.info("Processing ${result.deletedMessageIds.size} deleted messages for service ${result.serviceType}")

        result.deletedMessageIds.forEach { messageId ->
            try {
                val message =
                    mongoTemplate
                        .findOne(
                            Query
                                .query(
                                    Criteria
                                        .where("serviceType")
                                        .`is`(result.serviceType)
                                        .and("serviceMessageId")
                                        .`is`(messageId),
                                ),
                            ServiceMessageDocument::class.java,
                        ).awaitFirstOrNull()

                if (message != null) {
                    removeFromRag(message)
                    markAsDeleted(message.id)
                }
            } catch (e: Exception) {
                logger.error("Error processing deleted message $messageId from ${result.serviceType}", e)
            }
        }
    }

    /**
     * Remove a message from RAG
     * TODO: Implement proper RAG cleanup when embedding service is integrated
     */
    private suspend fun removeFromRag(messageDoc: ServiceMessageDocument) {
        if (messageDoc.isIndexedInRag && messageDoc.ragDocumentIds.isNotEmpty()) {
            try {
                logger.info("Would remove message ${messageDoc.serviceMessageId} from RAG (not yet implemented)")
            } catch (e: Exception) {
                logger.error("Error removing message ${messageDoc.serviceMessageId} from RAG", e)
            }
        }
    }

    /**
     * Mark a message as deleted
     */
    private suspend fun markAsDeleted(messageId: ObjectId) {
        mongoTemplate
            .updateFirst(
                Query.query(Criteria.where("_id").`is`(messageId)),
                Update()
                    .set("isDeleted", true)
                    .set("deletedAt", Instant.now())
                    .set("updatedAt", Instant.now()),
                ServiceMessageDocument::class.java,
            ).awaitFirstOrNull()
    }

    /**
     * Update last checked timestamp
     */
    private suspend fun updateLastChecked(messageId: ObjectId) {
        mongoTemplate
            .updateFirst(
                Query.query(Criteria.where("_id").`is`(messageId)),
                Update()
                    .set("lastCheckedAt", Instant.now())
                    .set("updatedAt", Instant.now()),
                ServiceMessageDocument::class.java,
            ).awaitFirstOrNull()
    }

    /**
     * Calculate hash of content for change detection
     */
    private fun calculateHash(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(content.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Map ServiceType to RagSourceType
     */
    private fun mapServiceTypeToRagSourceType(serviceType: ServiceType): RagSourceType =
        when (serviceType) {
            ServiceType.EMAIL -> RagSourceType.EMAIL
            ServiceType.SLACK -> RagSourceType.SLACK
            ServiceType.TEAMS -> RagSourceType.TEAMS
            ServiceType.DISCORD -> RagSourceType.DISCORD
            ServiceType.JIRA -> RagSourceType.JIRA
            ServiceType.GIT -> RagSourceType.GIT_HISTORY
        }
}
