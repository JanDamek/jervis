package com.jervis.repository

import com.jervis.entity.jira.JiraIssueIndexDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface JiraIssueIndexMongoRepository : CoroutineCrudRepository<JiraIssueIndexDocument, ObjectId> {
    @Query("{ 'clientId': ?0, 'issueKey': ?1 }")
    suspend fun findByClientIdAndIssueKey(
        clientId: ObjectId,
        issueKey: String,
    ): JiraIssueIndexDocument?

    // Continuous indexing support
    @Query("{ 'clientId': ?0, 'state': ?1, 'archived': false }")
    fun findByClientIdAndStateAndArchivedFalseOrderByUpdatedAtAsc(
        clientId: ObjectId,
        state: String,
    ): Flow<JiraIssueIndexDocument>

    // Global counts for UI overview
    @Query(value = "{ 'archived': false, 'state': 'INDEXED' }", count = true)
    suspend fun countIndexedActive(): Long

    @Query(value = "{ 'archived': false, 'state': 'NEW' }", count = true)
    suspend fun countNewActive(): Long
}
