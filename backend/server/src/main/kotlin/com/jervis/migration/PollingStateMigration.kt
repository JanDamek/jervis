package com.jervis.migration

import com.jervis.entity.polling.PollingStateDocument
import com.jervis.repository.PollingStateMongoRepository
import com.jervis.types.ConnectionId
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.bson.Document
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.mongodb.core.ReactiveMongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import java.time.Instant

/**
 * One-time migration: Move pollingStates from ConnectionDocument to PollingStateDocument.
 *
 * This migration runs automatically on application startup (after ApplicationReadyEvent).
 * It's idempotent - safe to run multiple times.
 *
 * Steps:
 * 1. Find all connections with non-empty pollingStates field
 * 2. For each connection, extract pollingStates map
 * 3. Create PollingStateDocument for each handler in the map
 * 4. Insert into polling_states collection (upsert to avoid duplicates)
 * 5. Log migration results
 *
 * After successful migration, pollingStates field can be removed from ConnectionDocument
 * (already done in code, but may still exist in MongoDB documents).
 */
@Component
class PollingStateMigration(
    private val mongoTemplate: ReactiveMongoTemplate,
    private val pollingStateRepository: PollingStateMongoRepository,
) {
    private val logger = KotlinLogging.logger {}

    @EventListener(ApplicationReadyEvent::class)
    fun migrate() {
        logger.info { "Starting polling states migration..." }

        runBlocking {
            try {
                // Find all connections with pollingStates field (legacy data)
                val query = Query(Criteria.where("pollingStates").exists(true).ne(emptyMap<String, Any>()))
                val connections: List<Document> = mongoTemplate
                    .find(query, Document::class.java, "connections")
                    .collectList()
                    .block() ?: emptyList()

                if (connections.isEmpty()) {
                    logger.info { "No legacy polling states found. Migration skipped." }
                    return@runBlocking
                }

                logger.info { "Found ${connections.size} connections with legacy polling states" }

                var migratedCount = 0
                var errorCount = 0

                for (connectionDoc in connections) {
                    try {
                        val connectionId = ConnectionId(connectionDoc.getObjectId("_id"))
                        val connectionName = connectionDoc.getString("name")
                        val pollingStates = connectionDoc.get("pollingStates") as? Map<String, Any> ?: continue

                        logger.debug { "Processing connection: $connectionName ($connectionId)" }

                        for ((handlerType, stateData) in pollingStates) {
                            try {
                                val state = stateData as? Map<String, Any> ?: continue

                                // Extract fields from legacy PollingState
                                val lastFetchedUid = (state["lastFetchedUid"] as? Number)?.toLong()
                                val lastFetchedMessageNumber = (state["lastFetchedMessageNumber"] as? Number)?.toInt()
                                val lastSeenUpdatedAt = (state["lastSeenUpdatedAt"] as? java.util.Date)?.toInstant()
                                    ?: (state["lastSeenUpdatedAt"] as? Instant)

                                // Check if already migrated
                                val existing = pollingStateRepository.findByConnectionIdAndHandlerType(
                                    connectionId,
                                    handlerType
                                )

                                if (existing != null) {
                                    logger.debug { "  Polling state for $handlerType already exists, skipping" }
                                    continue
                                }

                                // Create new PollingStateDocument
                                val pollingStateDoc = PollingStateDocument(
                                    connectionId = connectionId,
                                    handlerType = handlerType,
                                    lastFetchedUid = lastFetchedUid,
                                    lastFetchedMessageNumber = lastFetchedMessageNumber,
                                    lastSeenUpdatedAt = lastSeenUpdatedAt,
                                    lastUpdated = Instant.now()
                                )

                                pollingStateRepository.save(pollingStateDoc)
                                migratedCount++
                                logger.debug { "  ✓ Migrated $handlerType state" }

                            } catch (e: Exception) {
                                logger.error(e) { "  ✗ Error migrating $handlerType for connection $connectionName" }
                                errorCount++
                            }
                        }
                    } catch (e: Exception) {
                        logger.error(e) { "Error processing connection ${connectionDoc.getString("name")}" }
                        errorCount++
                    }
                }

                logger.info {
                    "Polling states migration completed: migrated=$migratedCount, errors=$errorCount"
                }

                // Verify migration
                val totalStates = pollingStateRepository.findAll().toList().size
                logger.info { "Verification: polling_states collection now has $totalStates documents" }

            } catch (e: Exception) {
                logger.error(e) { "Fatal error during polling states migration" }
            }
        }
    }
}
