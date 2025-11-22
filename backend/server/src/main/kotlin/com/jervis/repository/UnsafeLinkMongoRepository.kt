package com.jervis.repository

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
}
