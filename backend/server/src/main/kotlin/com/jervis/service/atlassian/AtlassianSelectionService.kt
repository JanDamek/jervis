package com.jervis.service.atlassian

import com.jervis.domain.jira.JiraAccountId
import com.jervis.domain.jira.JiraBoardId
import com.jervis.domain.atlassian.AtlassianConnection
import com.jervis.domain.jira.JiraProjectKey
import org.bson.types.ObjectId

interface AtlassianSelectionService {
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
    suspend fun getConnection(clientId: ObjectId): AtlassianConnection

    /** Ensure project and preferred user are set; fail fast if missing (no background task creation). */
    suspend fun ensureSelectionsOrCreateTasks(
        clientId: ObjectId,
        allowAutoDetectUser: Boolean = true,
    ): Pair<JiraProjectKey, JiraAccountId>
}
