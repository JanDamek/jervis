package com.jervis.repository

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
     * Find all NEW pages across all accounts, ordered by lastModifiedAt descending (newest first).
     * Used by single indexer instance that processes all accounts.
     */
    @Query(value = "{ 'state': 'NEW' }", sort = "{ 'lastModifiedAt': -1 }")
    fun findNewPagesAllAccountsOrderByModifiedDesc(): Flow<ConfluencePageDocument>

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
     * Count pages by state for monitoring.
     */
    @Query(value = "{ 'accountId': ?0, 'state': ?1 }", count = true)
    suspend fun countByAccountIdAndState(
        accountId: ObjectId,
        state: ConfluencePageStateEnum,
    ): Long

    /** Global counts (across accounts) for UI overview */
    @Query(value = "{ 'state': ?0 }", count = true)
    suspend fun countByState(state: ConfluencePageStateEnum): Long
}
