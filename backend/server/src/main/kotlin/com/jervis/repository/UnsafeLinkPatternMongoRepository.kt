package com.jervis.repository

import com.jervis.entity.UnsafeLinkPatternDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface UnsafeLinkPatternMongoRepository : CoroutineCrudRepository<UnsafeLinkPatternDocument, ObjectId> {
    /**
     * Find pattern by exact regex string.
     */
    suspend fun findByPattern(pattern: String): UnsafeLinkPatternDocument?

    /**
     * Get all enabled patterns for matching.
     */
    fun findByEnabledTrue(): Flow<UnsafeLinkPatternDocument>

    /**
     * Delete pattern by exact regex string.
     */
    suspend fun deleteByPattern(pattern: String): Long
}
