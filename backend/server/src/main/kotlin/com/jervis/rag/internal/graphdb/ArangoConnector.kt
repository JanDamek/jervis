package com.jervis.rag.internal.graphdb

import com.arangodb.ArangoDB
import com.arangodb.ArangoDatabase
import com.arangodb.entity.DatabaseEntity
import com.arangodb.model.AqlQueryOptions
import com.jervis.configuration.properties.ArangoProperties
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Coroutine-friendly ArangoDB connector. Creates and provides access to ArangoDatabase.
 */
@Component
class ArangoConnector(
    private val properties: ArangoProperties,
) {
    private val logger = KotlinLogging.logger {}

    private val client: ArangoDB by lazy {
        val builder =
            ArangoDB
                .Builder()
                .host(properties.host, properties.port)
                .user(properties.username)
                .password(properties.password)
                .timeout(properties.timeoutMs.toInt())

        // Use SSL if scheme is https; otherwise defaults are fine
        if (properties.scheme.equals("https", ignoreCase = true)) {
            builder.useSsl(true)
        }

        logger.info { "ArangoConnector initialized for ${properties.host}:${properties.port}/${properties.database}" }
        builder.build()
    }

    @PostConstruct
    fun initializeSchema() {
        runBlocking {
            try {
                logger.info { "Checking ArangoDB schema in database '${properties.database}'..." }

                val db = ensureDatabase()

                // Step 1: Delete all graphs (we don't use ArangoDB graphs, only collections)
                // Graphs must be deleted before collections to avoid constraint violations
                var graphsDeleted = 0
                try {
                    val allGraphs =
                        withContext(Dispatchers.IO) {
                            db.graphs.map { it.name }
                        }
                    val graphNames = allGraphs.filter { !it.startsWith("_") }
                    val expectedPatternGraph = Regex("^c[a-f0-9]{24}_(graph)$")

                    val graphToDelete =
                        graphNames.filter { collectionName ->
                            !expectedPatternGraph.matches(collectionName)
                        }
                    if (graphToDelete.isNotEmpty()) {
                        logger.warn { "Found ${graphToDelete.size} graph(s) to delete: ${graphToDelete.joinToString()}" }
                        withContext(Dispatchers.IO) {
                            for (graphName in graphToDelete) {
                                try {
                                    db.graph(graphName).drop()
                                    logger.info { "Deleted graph: $graphName" }
                                    graphsDeleted++
                                } catch (e: Exception) {
                                    logger.error(e) { "Failed to delete graph: $graphName" }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to check/delete graphs - continuing with collection cleanup" }
                }

                // Step 2: Delete unexpected collections
                val existingCollections =
                    withContext(Dispatchers.IO) {
                        db.collections.map { it.name }.filter { !it.startsWith("_") }
                    }

                logger.info { "Found ${existingCollections.size} collection(s) in ArangoDB: ${existingCollections.joinToString()}" }

                // Expected collections pattern: c<clientId>_nodes, c<clientId>_edges
                // We only keep collections that match this pattern
                val expectedPattern = Regex("^c[a-f0-9]{24}_(nodes|edges)$")

                val collectionsToDelete =
                    existingCollections.filter { collectionName ->
                        !expectedPattern.matches(collectionName)
                    }

                if (collectionsToDelete.isNotEmpty()) {
                    logger.warn {
                        "Found ${collectionsToDelete.size} unexpected collection(s) to delete: ${collectionsToDelete.joinToString()}"
                    }

                    withContext(Dispatchers.IO) {
                        collectionsToDelete.forEach { collectionName ->
                            try {
                                db.collection(collectionName).drop()
                                logger.info { "Deleted unexpected collection: $collectionName" }
                            } catch (e: Exception) {
                                logger.error(e) { "Failed to delete collection: $collectionName" }
                            }
                        }
                    }

                    logger.info { "Schema cleanup completed: deleted $graphsDeleted graph(s), ${collectionsToDelete.size} collection(s)" }
                } else {
                    logger.info { "ArangoDB schema is clean - all collections match expected pattern" }
                }

                // Step 3: Migrate entityType -> type in all client node collections
                logger.info { "Starting migration: entityType -> type in all node collections..." }
                var migrationCount = 0
                try {
                    val nodeCollections = existingCollections.filter { it.matches(Regex("^c[a-f0-9]{24}_nodes$")) }

                    withContext(Dispatchers.IO) {
                        for (collectionName in nodeCollections) {
                            try {
                                val aql =
                                    """
                                    FOR doc IN @@collection
                                        FILTER HAS(doc, 'entityType') && !HAS(doc, 'type')
                                        UPDATE doc WITH { type: doc.entityType } IN @@collection
                                        OPTIONS { keepNull: false }
                                        RETURN NEW
                                    """.trimIndent()

                                val bindVars = mapOf("@collection" to collectionName)
                                val cursor = db.query(aql, Map::class.java, bindVars, AqlQueryOptions())
                                val updated = cursor.asListRemaining().size

                                if (updated > 0) {
                                    migrationCount += updated
                                    logger.info { "Migrated $updated document(s) in $collectionName: entityType -> type" }
                                }
                            } catch (e: Exception) {
                                logger.warn(e) { "Failed to migrate collection $collectionName" }
                            }
                        }
                    }

                    if (migrationCount > 0) {
                        logger.info {
                            "Migration completed: updated $migrationCount document(s) across ${nodeCollections.size} collection(s)"
                        }
                    } else {
                        logger.info { "No documents required migration" }
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "Migration entityType -> type encountered issues, but continuing startup" }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to initialize ArangoDB schema" }
            }
        }
    }

    suspend fun database(): ArangoDatabase =
        withContext(Dispatchers.IO) {
            client.db(properties.database)
        }

    suspend fun ensureDatabase(): ArangoDatabase =
        withContext(Dispatchers.IO) {
            val db = client.db(properties.database)
            if (!db.exists()) {
                client.createDatabase(properties.database)
            }
            client.db(properties.database)
        }

    suspend fun health(): Result<DatabaseEntity> =
        withContext(Dispatchers.IO) {
            runCatching { client.db(properties.database).getInfo() }
        }
}
