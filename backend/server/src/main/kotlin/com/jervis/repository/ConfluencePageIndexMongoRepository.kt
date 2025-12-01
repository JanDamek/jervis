package com.jervis.repository

import com.jervis.entity.confluence.ConfluencePageIndexDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface ConfluencePageIndexMongoRepository : CoroutineCrudRepository<ConfluencePageIndexDocument, ObjectId> {
    // Continuous indexing support - per connection
    @Query("{ 'connectionId': ?0, 'state': ?1, 'archived': false }")
    fun findByConnectionIdAndStateAndArchivedFalseOrderByUpdatedAtAsc(
        connectionId: ObjectId,
        state: String,
    ): Flow<ConfluencePageIndexDocument>

    // Continuous indexing support - all connections (newest first)
    @Query(value = "{ 'state': ?0, 'archived': false }", sort = "{ 'updatedAt': -1 }")
    fun findByStateAndArchivedFalseOrderByUpdatedAtDesc(state: String): Flow<ConfluencePageIndexDocument>

    // Global counts for UI overview
    @Query(value = "{ 'archived': false, 'state': 'INDEXED' }", count = true)
    suspend fun countIndexedActive(): Long

    @Query(value = "{ 'archived': false, 'state': 'NEW' }", count = true)
    suspend fun countNewActive(): Long

    @Query("{ 'connectionId': ?0, 'pageId': ?1 }")
    suspend fun findByConnectionIdAndPageId(
        connectionId: ObjectId,
        pageId: String,
    ): ConfluencePageIndexDocument?
}
