package com.jervis.service.jira

import com.jervis.domain.jira.JiraAccountId
import com.jervis.domain.jira.JiraBoardId
import com.jervis.domain.jira.JiraConnection
import com.jervis.domain.jira.JiraIssue
import com.jervis.domain.jira.JiraProjectKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.springframework.stereotype.Service

@Service
class StubJiraApiClient : JiraApiClient {
    override suspend fun getMyself(conn: JiraConnection): JiraAccountId = JiraAccountId("auto-detected")

    override suspend fun listBoards(
        conn: JiraConnection,
        project: JiraProjectKey?,
    ): List<Pair<JiraBoardId, String>> = emptyList()

    override suspend fun projectExists(
        conn: JiraConnection,
        key: JiraProjectKey,
    ): Boolean = true

    override suspend fun searchIssues(
        conn: JiraConnection,
        jql: String,
        updatedSinceEpochMs: Long?,
        fields: List<String>,
        expand: List<String>,
        pageSize: Int,
    ): Flow<JiraIssue> = emptyFlow()

    override suspend fun fetchIssueComments(
        conn: JiraConnection,
        issueKey: String,
    ): Flow<Pair<String, String>> = emptyFlow()
}
