package com.jervis.repository

import com.jervis.entity.jira.JiraIssueIndexDocument
import com.jervis.types.ConnectionId
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface JiraIssueIndexMongoRepository : CoroutineCrudRepository<JiraIssueIndexDocument, ObjectId> {
    // Continuous indexing support - per connection (ordered by jiraUpdatedAt DESC)
    @Query(value = "{ 'connectionDocumentId': ?0, 'state': ?1 }", sort = "{ 'jiraUpdatedAt': -1 }")
    fun findByConnectionDocumentIdAndStateOrderByJiraUpdatedAtDesc(
        connectionId: ObjectId,
        state: String,
    ): Flow<JiraIssueIndexDocument>

    // Continuous indexing support - all connections (newest first by jiraUpdatedAt)
    @Query(value = "{ 'state': ?0 }", sort = "{ 'jiraUpdatedAt': -1 }")
    fun findByStateOrderByJiraUpdatedAtDesc(state: String): Flow<JiraIssueIndexDocument>

    // Global counts for UI overview
    @Query(value = "{ 'state': 'INDEXED' }", count = true)
    suspend fun countIndexedActive(): Long

    @Query(value = "{ 'state': 'NEW' }", count = true)
    suspend fun countNewActive(): Long

    suspend fun findByConnectionDocumentIdAndIssueKeyAndLatestChangelogId(
        connectionDocumentId: ConnectionId,
        issueKey: String,
        latestChangelogId: String?,
    ): JiraIssueIndexDocument?
}
