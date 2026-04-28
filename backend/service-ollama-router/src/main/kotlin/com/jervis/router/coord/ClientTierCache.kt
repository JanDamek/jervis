package com.jervis.router.coord

import com.mongodb.reactivestreams.client.MongoClient
import com.mongodb.reactivestreams.client.MongoClients
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import org.bson.Document
import org.bson.types.ObjectId
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Resolves a client's OpenRouter tier (NONE / FREE / PAID / PREMIUM) from
 * the `clients` collection. Cached in-memory with TTL.
 *
 * Mirrors Python `client_tier_cache.resolve_client_tier`. Falls back to
 * "FREE" on missing client / DB error so routing never crashes.
 */
class ClientTierCache(
    mongoUri: String,
    private val ttlS: Long,
) {
    private val mongoClient: MongoClient? = if (mongoUri.isBlank()) null else runCatching {
        MongoClients.create(mongoUri)
    }.onFailure { logger.warn { "Mongo client init failed: ${it.message}" } }.getOrNull()

    private val cache: MutableMap<String, Pair<String, Long>> = HashMap()
    private val mutex = Mutex()

    suspend fun resolve(clientId: String?): String {
        if (clientId.isNullOrBlank()) return DEFAULT_TIER
        mutex.withLock {
            val cached = cache[clientId]
            if (cached != null && (Instant.now().epochSecond - cached.second) < ttlS) {
                return cached.first
            }
        }

        val tier = lookup(clientId) ?: DEFAULT_TIER
        mutex.withLock { cache[clientId] = tier to Instant.now().epochSecond }
        logger.info { "Client tier resolved: $clientId → $tier" }
        return tier
    }

    fun invalidate(clientId: String?) {
        if (clientId == null) cache.clear() else cache.remove(clientId)
    }

    fun close() {
        mongoClient?.close()
    }

    private suspend fun lookup(clientId: String): String? {
        val client = mongoClient ?: return null
        return runCatching {
            val db = client.getDatabase("jervis")
            val coll = db.getCollection("clients")
            val objId = runCatching { ObjectId(clientId) }.getOrNull() ?: return@runCatching null
            val doc = coll.find(Document("_id", objId))
                .projection(Document("cloudModelPolicy.maxOpenRouterTier", 1))
                .first()
                .awaitFirstOrNull() ?: return@runCatching null
            val policy = doc.get("cloudModelPolicy") as? Document
            policy?.getString("maxOpenRouterTier")
        }.onFailure {
            logger.warn { "Failed to resolve tier for $clientId: ${it.message}" }
        }.getOrNull()
    }

    companion object {
        const val DEFAULT_TIER: String = "FREE"
    }
}
