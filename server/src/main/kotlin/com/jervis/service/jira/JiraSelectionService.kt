package com.jervis.service.jira

import com.jervis.domain.jira.JiraAccountId
import com.jervis.domain.jira.JiraBoardId
import com.jervis.domain.jira.JiraConnection
import com.jervis.domain.jira.JiraProjectKey
import org.bson.types.ObjectId

interface JiraSelectionService {
    suspend fun setPreferredUser(
        clientId: ObjectId,
        accountId: JiraAccountId,
    )

    suspend fun setPrimaryProject(
        clientId: ObjectId,
        projectKey: JiraProjectKey,
    )

    suspend fun setMainBoard(
        clientId: ObjectId,
        boardId: JiraBoardId,
    )

    /** Get stored connection; fail fast if not configured. */
    suspend fun getConnection(clientId: ObjectId): JiraConnection

    /** Ensure project and preferred user are set; fail fast if missing (no background task creation). */
    suspend fun ensureSelectionsOrCreateTasks(
        clientId: ObjectId,
        allowAutoDetectUser: Boolean = true,
    ): Pair<JiraProjectKey, JiraAccountId>
}
