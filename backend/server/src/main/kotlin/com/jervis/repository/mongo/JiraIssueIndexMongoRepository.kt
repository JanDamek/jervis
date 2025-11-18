package com.jervis.repository.mongo

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

    fun findByClientIdAndArchived(
        clientId: ObjectId,
        archived: Boolean,
    ): Flow<JiraIssueIndexDocument>

    // Global counts for UI overview
    @Query(value = "{ 'archived': false, 'lastIndexedAt': { \$ne: null } }", count = true)
    suspend fun countIndexedActive(): Long

    @Query(value = "{ 'archived': false, 'lastIndexedAt': null }", count = true)
    suspend fun countNewActive(): Long
}
