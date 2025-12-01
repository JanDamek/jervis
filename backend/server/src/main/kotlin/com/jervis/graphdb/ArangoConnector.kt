package com.jervis.graphdb

import com.arangodb.ArangoDB
import com.arangodb.ArangoDatabase
import com.arangodb.entity.DatabaseEntity
import com.jervis.configuration.properties.ArangoProperties
import kotlinx.coroutines.Dispatchers
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
        val builder = ArangoDB.Builder()
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

    suspend fun database(): ArangoDatabase = withContext(Dispatchers.IO) {
        client.db(properties.database)
    }

    suspend fun ensureDatabase(): ArangoDatabase = withContext(Dispatchers.IO) {
        val db = client.db(properties.database)
        if (!db.exists()) {
            client.createDatabase(properties.database)
        }
        client.db(properties.database)
    }

    suspend fun health(): Result<DatabaseEntity> = withContext(Dispatchers.IO) {
        runCatching { client.db(properties.database).getInfo() }
    }
}
