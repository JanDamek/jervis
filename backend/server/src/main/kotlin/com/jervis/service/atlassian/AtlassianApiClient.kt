package com.jervis.service.atlassian

import com.jervis.domain.jira.JiraAccountId
import com.jervis.domain.jira.JiraBoardId
import com.jervis.domain.atlassian.AtlassianConnection
import com.jervis.domain.jira.JiraIssue
import com.jervis.domain.jira.JiraProjectKey
import kotlinx.coroutines.flow.Flow

interface AtlassianApiClient {
    suspend fun getMyself(conn: AtlassianConnection): JiraAccountId

    suspend fun listBoards(
        conn: AtlassianConnection,
        project: JiraProjectKey? = null,
    ): List<Pair<JiraBoardId, String>>

    suspend fun listProjects(conn: AtlassianConnection): List<Pair<JiraProjectKey, String>>

    suspend fun projectExists(
        conn: AtlassianConnection,
        key: JiraProjectKey,
    ): Boolean

    suspend fun searchIssues(
        conn: AtlassianConnection,
        jql: String,
        updatedSinceEpochMs: Long? = null,
        fields: List<String> = listOf("summary", "description", "status", "assignee", "reporter", "updated", "created"),
        expand: List<String> = emptyList(),
        pageSize: Int = 100,
    ): Flow<JiraIssue>

    suspend fun fetchIssueComments(
        conn: AtlassianConnection,
        issueKey: String,
    ): Flow<Pair<String /*commentId*/, String /*body*/>>
}
