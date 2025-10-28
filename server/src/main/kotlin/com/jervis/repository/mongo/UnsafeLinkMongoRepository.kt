package com.jervis.repository.mongo

import com.jervis.entity.UnsafeLinkDocument
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface UnsafeLinkMongoRepository : CoroutineCrudRepository<UnsafeLinkDocument, ObjectId> {
    /**
     * Find cached UNSAFE classification by exact URL match.
     */
    suspend fun findByUrl(url: String): UnsafeLinkDocument?

    /**
     * Check if URL is in UNSAFE cache.
     */
    suspend fun existsByUrl(url: String): Boolean
}
