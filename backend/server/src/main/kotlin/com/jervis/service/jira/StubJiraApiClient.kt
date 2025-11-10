package com.jervis.service.jira

import com.jervis.domain.jira.JiraAccountId
import com.jervis.domain.jira.JiraBoardId
import com.jervis.domain.jira.JiraConnection
import com.jervis.domain.jira.JiraIssue
import com.jervis.domain.jira.JiraProjectKey
import kotlinx.coroutines.flow.Flow
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.awaitBodilessEntity
import org.springframework.web.reactive.function.client.awaitBody

@Service
class StubJiraApiClient(
    private val webClientBuilder: org.springframework.web.reactive.function.client.WebClient.Builder,
) : JiraApiClient {
    override suspend fun getMyself(conn: JiraConnection): JiraAccountId {
        val client = clientFor(conn)
        val resp: MyselfDto =
            client
                .get()
                .uri("/rest/api/3/myself")
                .header("Authorization", basic(conn))
                .retrieve()
                .awaitBody()
        return JiraAccountId(resp.accountId)
    }

    override suspend fun listBoards(
        conn: JiraConnection,
        project: JiraProjectKey?,
    ): List<Pair<JiraBoardId, String>> {
        val client = clientFor(conn)
        val results = mutableListOf<Pair<JiraBoardId, String>>()
        var startAt = 0
        val max = 50
        do {
            val response: BoardsResponseDto =
                client
                    .get()
                    .uri { b ->
                        b
                            .path("/rest/agile/1.0/board")
                            .queryParam("startAt", startAt)
                            .queryParam("maxResults", max)
                            .apply { project?.let { queryParam("projectKeyOrId", it.value) } }
                            .build()
                    }.header("Authorization", basic(conn))
                    .retrieve()
                    .awaitBody<BoardsResponseDto>()
            val values = response.values.orEmpty()
            for (v in values) {
                val id = v.id ?: continue
                val name = v.name ?: ("Board $id")
                results += JiraBoardId(id) to name
            }
            val pageCount = values.size
            startAt += pageCount
            val total = response.total ?: (startAt + 1)
        } while (pageCount > 0 && startAt < total)
        return results
    }

    override suspend fun listProjects(conn: JiraConnection): List<Pair<JiraProjectKey, String>> {
        val client = clientFor(conn)
        val results = mutableListOf<Pair<JiraProjectKey, String>>()
        var startAt = 0
        val max = 50
        do {
            val response: ProjectSearchResponseDto =
                client
                    .get()
                    .uri { b ->
                        b
                            .path("/rest/api/3/project/search")
                            .queryParam("startAt", startAt)
                            .queryParam("maxResults", max)
                            .build()
                    }.header("Authorization", basic(conn))
                    .retrieve()
                    .awaitBody()
            val values = response.values.orEmpty()
            for (p in values) {
                val key = p.key ?: continue
                val name = p.name ?: key
                results += JiraProjectKey(key) to name
            }
            val pageCount = values.size
            startAt += pageCount
            val total = response.total ?: (startAt + 1)
        } while (pageCount > 0 && startAt < total)
        return results
    }

    override suspend fun projectExists(
        conn: JiraConnection,
        key: JiraProjectKey,
    ): Boolean =
        try {
            val client = clientFor(conn)
            client
                .get()
                .uri { b ->
                    b.path("/rest/api/3/project/{key}").build(key.value)
                }.header("Authorization", basic(conn))
                .retrieve()
                .awaitBodilessEntity()
            true
        } catch (e: Exception) {
            false
        }

    override suspend fun searchIssues(
        conn: JiraConnection,
        jql: String,
        updatedSinceEpochMs: Long?,
        fields: List<String>,
        expand: List<String>,
        pageSize: Int,
    ): Flow<JiraIssue> =
        kotlinx.coroutines.flow.flow {
            val client = clientFor(conn)
            var startAt = 0
            val max = pageSize.coerceIn(1, 100)
            do {
                val response: SearchResponseDto =
                    client
                        .get()
                        .uri { b ->
                            b
                                .path("/rest/api/3/search")
                                .queryParam("jql", jql)
                                .queryParam("startAt", startAt)
                                .queryParam("maxResults", max)
                                .queryParam("fields", fields.joinToString(","))
                                .apply { if (expand.isNotEmpty()) queryParam("expand", expand.joinToString(",")) }
                                .build()
                        }.header("Authorization", basic(conn))
                        .retrieve()
                        .awaitBody()

                val issues = response.issues.orEmpty()
                for (i in issues) {
                    val f = i.fields
                    if (f != null) {
                        val updated = f.updated?.let { parseJiraDate(it) }
                        if (updatedSinceEpochMs != null && updated != null && updated.toEpochMilli() < updatedSinceEpochMs) continue
                        val created = f.created?.let { parseJiraDate(it) } ?: updated ?: java.time.Instant.now()
                        val issue =
                            JiraIssue(
                                key = i.key ?: continue,
                                project = JiraProjectKey(f.project?.key ?: i.key.substringBefore("-")),
                                summary = f.summary ?: "",
                                type = f.issuetype?.name ?: "",
                                status = f.status?.name ?: "",
                                assignee = f.assignee?.accountId?.let { JiraAccountId(it) },
                                reporter = f.reporter?.accountId?.let { JiraAccountId(it) },
                                updated = updated ?: java.time.Instant.now(),
                                created = created,
                            )
                        emit(issue)
                    }
                }

                val pageCount = issues.size
                startAt += pageCount
                response.total ?: (startAt + 1)
            } while (pageCount > 0 && startAt < (response.total ?: startAt))
        }

    override suspend fun fetchIssueComments(
        conn: JiraConnection,
        issueKey: String,
    ): Flow<Pair<String, String>> =
        kotlinx.coroutines.flow.flow<Pair<String, String>> {
            val client = clientFor(conn)
            var startAt = 0
            val max = 100
            do {
                val response =
                    client
                        .get()
                        .uri { b ->
                            b
                                .path("/rest/api/3/issue/{key}/comment")
                                .queryParam("startAt", startAt)
                                .queryParam("maxResults", max)
                                .queryParam("expand", "renderedBody")
                                .build(issueKey)
                        }.header("Authorization", basic(conn))
                        .retrieve()
                        .awaitBody<CommentsResponseDto>()

                val comments = response.comments.orEmpty()
                for (c in comments) {
                    val id = c.id ?: continue
                    val bodyHtml = c.renderedBody ?: c.body
                    val text = bodyHtml?.let { stripHtml(it) } ?: ""
                    emit(id to text)
                }

                val pageCount = comments.size
                startAt += pageCount
            } while (pageCount > 0 && startAt < (response.total ?: startAt))
        }

    private fun clientFor(conn: JiraConnection) = webClientBuilder.baseUrl("https://${conn.tenant.value}").build()

    private fun basic(conn: JiraConnection): String {
        val tokenPair = (conn.email ?: "") + ":" + conn.accessToken
        val encoded =
            java.util.Base64
                .getEncoder()
                .encodeToString(tokenPair.toByteArray())
        return "Basic $encoded"
    }

    private fun parseJiraDate(value: String): java.time.Instant =
        runCatching { java.time.Instant.parse(value) }
            .getOrElse {
                runCatching {
                    val f =
                        java.time.format.DateTimeFormatter
                            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                    java.time.OffsetDateTime
                        .parse(value, f)
                        .toInstant()
                }.getOrElse {
                    val f2 =
                        java.time.format.DateTimeFormatter
                            .ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")
                    java.time.OffsetDateTime
                        .parse(value, f2)
                        .toInstant()
                }
            }

    // DTOs for Jira REST
    private data class SearchResponseDto(
        val startAt: Int? = null,
        val maxResults: Int? = null,
        val total: Int? = null,
        val issues: List<IssueDto>? = null,
    )

    private data class IssueDto(
        val key: String? = null,
        val fields: FieldsDto? = null,
    )

    private data class FieldsDto(
        val summary: String? = null,
        val status: StatusDto? = null,
        val assignee: UserRefDto? = null,
        val reporter: UserRefDto? = null,
        val updated: String? = null,
        val created: String? = null,
        val issuetype: IssueTypeDto? = null,
        val project: ProjectRefDto? = null,
    )

    private data class StatusDto(
        val name: String? = null,
    )

    private data class IssueTypeDto(
        val name: String? = null,
    )

    private data class UserRefDto(
        val accountId: String? = null,
    )

    private data class ProjectRefDto(
        val key: String? = null,
    )

    private data class MyselfDto(
        val accountId: String,
    )

    // DTOs for comments API
    private data class CommentsResponseDto(
        val startAt: Int? = null,
        val maxResults: Int? = null,
        val total: Int? = null,
        val comments: List<CommentDto>? = null,
    )

    private data class CommentDto(
        val id: String? = null,
        val renderedBody: String? = null,
        val body: String? = null,
    )

    // Agile boards response
    private data class BoardsResponseDto(
        val startAt: Int? = null,
        val maxResults: Int? = null,
        val total: Int? = null,
        val isLast: Boolean? = null,
        val values: List<BoardDto>? = null,
    )

    private data class BoardDto(
        val id: Long? = null,
        val name: String? = null,
        val type: String? = null,
    )

    // Project search response
    private data class ProjectSearchResponseDto(
        val startAt: Int? = null,
        val maxResults: Int? = null,
        val total: Int? = null,
        val values: List<ProjectDto>? = null,
    )

    private data class ProjectDto(
        val key: String? = null,
        val name: String? = null,
    )

    private fun stripHtml(input: String): String =
        input
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()
}
