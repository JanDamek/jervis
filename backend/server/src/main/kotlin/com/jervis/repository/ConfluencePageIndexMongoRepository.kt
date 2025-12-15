package com.jervis.repository

import com.jervis.entity.confluence.ConfluencePageIndexDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface ConfluencePageIndexMongoRepository : CoroutineCrudRepository<ConfluencePageIndexDocument, ObjectId> {
    // Continuous indexing support - per connection (ordered by confluenceUpdatedAt DESC)
    @Query(value = "{ 'connectionDocumentId': ?0, 'state': ?1 }", sort = "{ 'confluenceUpdatedAt': -1 }")
    fun findByConnectionDocumentIdAndStateOrderByConfluenceUpdatedAtDesc(
        connectionId: ObjectId,
        state: String,
    ): Flow<ConfluencePageIndexDocument>

    // Continuous indexing support - all connections (newest first by confluenceUpdatedAt)
    @Query(value = "{ 'state': ?0 }", sort = "{ 'confluenceUpdatedAt': -1 }")
    fun findByStateOrderByConfluenceUpdatedAtDesc(state: String): Flow<ConfluencePageIndexDocument>

    // Global counts for UI overview
    @Query(value = "{ 'state': 'INDEXED' }", count = true)
    suspend fun countIndexedActive(): Long

    @Query(value = "{ 'state': 'NEW' }", count = true)
    suspend fun countNewActive(): Long

    @Query("{ 'connectionDocumentId': ?0, 'pageId': ?1 }")
    suspend fun findByConnectionDocumentIdAndPageId(
        connectionId: ObjectId,
        pageId: String,
    ): ConfluencePageIndexDocument?
}
