package com.jervis.repository.mongo

import com.jervis.domain.confluence.ConfluencePageStateEnum
import com.jervis.entity.ConfluencePageDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface ConfluencePageMongoRepository : CoroutineCrudRepository<ConfluencePageDocument, ObjectId> {
    /**
     * Find all pages in NEW state for given account.
     * Used by continuous indexer to process pages.
     */
    @Query("{ 'accountId': ?0, 'state': 'NEW' }")
    fun findNewPagesByAccount(accountId: ObjectId): Flow<ConfluencePageDocument>

    /**
     * Find page by accountId and pageId (Confluence page ID).
     * Used for change detection - check if page exists and compare version.
     */
    @Query("{ 'accountId': ?0, 'pageId': ?1 }")
    suspend fun findByAccountIdAndPageId(
        accountId: ObjectId,
        pageId: String,
    ): ConfluencePageDocument?

    /**
     * Find all pages for a space (for analytics/UI).
     */
    @Query("{ 'accountId': ?0, 'spaceKey': ?1 }")
    fun findByAccountIdAndSpaceKey(
        accountId: ObjectId,
        spaceKey: String,
    ): Flow<ConfluencePageDocument>

    /**
     * Count pages by state for monitoring.
     */
    @Query(value = "{ 'accountId': ?0, 'state': ?1 }", count = true)
    suspend fun countByAccountIdAndState(
        accountId: ObjectId,
        state: ConfluencePageStateEnum,
    ): Long
}
