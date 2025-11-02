package com.jervis.service.jira

import com.jervis.domain.jira.JiraAccountId
import com.jervis.domain.jira.JiraBoardId
import com.jervis.domain.jira.JiraConnection
import com.jervis.domain.jira.JiraIssue
import com.jervis.domain.jira.JiraProjectKey
import kotlinx.coroutines.flow.Flow

interface JiraApiClient {
    suspend fun getMyself(conn: JiraConnection): JiraAccountId

    suspend fun listBoards(
        conn: JiraConnection,
        project: JiraProjectKey? = null,
    ): List<Pair<JiraBoardId, String>>

    suspend fun projectExists(
        conn: JiraConnection,
        key: JiraProjectKey,
    ): Boolean

    suspend fun searchIssues(
        conn: JiraConnection,
        jql: String,
        updatedSinceEpochMs: Long? = null,
        fields: List<String> = listOf("summary", "status", "assignee", "reporter", "updated", "created"),
        expand: List<String> = emptyList(),
        pageSize: Int = 100,
    ): Flow<JiraIssue>

    suspend fun fetchIssueComments(
        conn: JiraConnection,
        issueKey: String,
    ): Flow<Pair<String /*commentId*/, String /*body*/>>
}
