package com.jervis.infrastructure.grpc

import com.jervis.contracts.server.CacheInvalidateRequest
import com.jervis.contracts.server.CacheInvalidateResponse
import com.jervis.contracts.server.ServerCacheServiceGrpcKt
import com.jervis.guidelines.GuidelinesService
import mu.KotlinLogging
import org.springframework.stereotype.Component

// gRPC surface for in-memory cache invalidation on the Kotlin server.
// Called by the Python orchestrator after writing to a Mongo collection
// that has a Kotlin-side cache. Unknown collection = no-op, returns ok.
@Component
class ServerCacheGrpcImpl(
    private val guidelinesService: GuidelinesService,
) : ServerCacheServiceGrpcKt.ServerCacheServiceCoroutineImplBase() {
    private val logger = KotlinLogging.logger {}

    override suspend fun invalidate(request: CacheInvalidateRequest): CacheInvalidateResponse {
        val collection = request.collection
        logger.info { "CACHE_INVALIDATE | collection=$collection" }

        when (collection) {
            "guidelines" -> guidelinesService.clearCache()
            else -> logger.debug { "No in-memory cache for collection '$collection', skipping" }
        }

        return CacheInvalidateResponse.newBuilder().setOk(true).build()
    }
}
