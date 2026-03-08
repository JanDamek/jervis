package com.jervis.rpc.internal

import com.jervis.service.guidelines.GuidelinesService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

@Serializable
data class CacheInvalidateRequest(val collection: String)

/**
 * POST /internal/cache/invalidate
 *
 * Called by Python orchestrator after mongo_update_document writes to a
 * collection that has in-memory cache in Kotlin. Clears the relevant
 * cache so the next read picks up the fresh MongoDB data.
 */
fun Routing.installInternalCacheApi(
    guidelinesService: GuidelinesService,
) {
    post("/internal/cache/invalidate") {
        val req = call.receive<CacheInvalidateRequest>()
        logger.info { "CACHE_INVALIDATE | collection=${req.collection}" }

        when (req.collection) {
            "guidelines" -> guidelinesService.clearCache()
            // Other collections read from MongoDB directly — no in-memory cache to clear.
            // If caching is added later, add invalidation here.
            else -> logger.debug { "No in-memory cache for collection '${req.collection}', skipping" }
        }

        call.respondText("{\"status\":\"ok\"}", io.ktor.http.ContentType.Application.Json, HttpStatusCode.OK)
    }
}
