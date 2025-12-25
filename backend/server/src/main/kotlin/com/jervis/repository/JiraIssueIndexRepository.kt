package com.jervis.repository

import com.jervis.domain.PollingStatusEnum
import com.jervis.entity.jira.JiraIssueIndexDocument
import com.jervis.types.ConnectionId
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface JiraIssueIndexRepository : CoroutineCrudRepository<JiraIssueIndexDocument, ObjectId> {
    suspend fun existsByConnectionIdAndIssueKeyAndLatestChangelogId(
        connectionId: ConnectionId,
        issueKey: String,
        latestChangelogId: String,
    ): Boolean

    suspend fun findAllByStatusIs(status: PollingStatusEnum = PollingStatusEnum.NEW): Flow<JiraIssueIndexDocument>

    suspend fun findByConnectionIdAndIssueKey(
        connectionId: ConnectionId,
        issueKey: String,
    ): JiraIssueIndexDocument?
}
