package com.jervis.integration.bugtracker.internal.repository

import com.jervis.domain.PollingStatusEnum
import com.jervis.integration.bugtracker.internal.entity.BugTrackerIssueIndexDocument
import com.jervis.types.ConnectionId
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface BugTrackerIssueIndexRepository : CoroutineCrudRepository<BugTrackerIssueIndexDocument, ObjectId> {
    suspend fun existsByConnectionIdAndIssueKeyAndLatestChangelogId(
        connectionId: ConnectionId,
        issueKey: String,
        latestChangelogId: String,
    ): Boolean

    suspend fun findAllByStatusIs(status: PollingStatusEnum = PollingStatusEnum.NEW): Flow<BugTrackerIssueIndexDocument>

    suspend fun findByConnectionIdAndIssueKey(
        connectionId: ConnectionId,
        issueKey: String,
    ): BugTrackerIssueIndexDocument?
}
