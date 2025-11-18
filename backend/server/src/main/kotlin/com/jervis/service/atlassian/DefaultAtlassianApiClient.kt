package com.jervis.service.atlassian

import com.jervis.domain.atlassian.AtlassianConnection
import com.jervis.domain.jira.JiraAccountId
import com.jervis.domain.jira.JiraBoardId
import com.jervis.domain.jira.JiraIssue
import com.jervis.domain.jira.JiraProjectKey
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Default implementation of Atlassian API client for Jira Cloud.
 * Uses Ktor HttpClient (coroutines-first) for consistency with ConfluenceApiClient.
 * Handles REST API v3 requests with rate limiting and error handling.
 *
 * Documentation: https://developer.atlassian.com/cloud/jira/platform/rest/v3/
 */
@Service
class DefaultAtlassianApiClient(
    private val rateLimiter: com.jervis.service.ratelimit.DomainRateLimiterService,
) : AtlassianApiClient {

    private val json =
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

    override suspend fun getMyself(conn: AtlassianConnection): JiraAccountId {
        val siteUrl = "https://${conn.tenant.value}"
        val client = createHttpClient(conn)
        return try {
            val url = "$siteUrl/rest/api/3/myself"
            rateLimiter.acquirePermit(url)

            val httpResponse: HttpResponse = client.get(url)

            if (!httpResponse.status.isSuccess()) {
                handleHttpError(conn, httpResponse)
            }

            val response = httpResponse.body<MyselfDto>()
            JiraAccountId(response.accountId)
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch myself for connection ${conn.tenant.value}" }
            client.close()
            throw e
        } finally {
            client.close()
        }
    }

    override suspend fun listBoards(
        conn: AtlassianConnection,
        project: JiraProjectKey?,
    ): List<Pair<JiraBoardId, String>> {
        val siteUrl = "https://${conn.tenant.value}"
        val results = mutableListOf<Pair<JiraBoardId, String>>()
        var startAt = 0
        val max = 50

        do {
            val client = createHttpClient(conn)
            try {
                val url = "$siteUrl/rest/agile/1.0/board"
                rateLimiter.acquirePermit(url)

                val httpResponse: HttpResponse =
                    client.get(url) {
                        parameter("startAt", startAt)
                        parameter("maxResults", max)
                        project?.let { parameter("projectKeyOrId", it.value) }
                    }

                if (!httpResponse.status.isSuccess()) {
                    handleHttpError(conn, httpResponse)
                }

                val response = httpResponse.body<BoardsResponseDto>()
                val values = response.values.orEmpty()

                for (v in values) {
                    val id = v.id ?: continue
                    val name = v.name ?: ("Board $id")
                    results += JiraBoardId(id.toLong()) to name
                }

                val pageCount = values.size
                startAt += pageCount
                val total = response.total ?: (startAt + 1)

                client.close()

                if (pageCount == 0 || startAt >= total) break
            } catch (e: Exception) {
                logger.error(e) { "Failed to list boards for connection ${conn.tenant.value}" }
                client.close()
                throw e
            }
        } while (true)

        return results
    }

    override suspend fun listProjects(conn: AtlassianConnection): List<Pair<JiraProjectKey, String>> {
        val siteUrl = "https://${conn.tenant.value}"
        val results = mutableListOf<Pair<JiraProjectKey, String>>()
        var startAt = 0
        val max = 50

        do {
            val client = createHttpClient(conn)
            try {
                val url = "$siteUrl/rest/api/3/project/search"
                rateLimiter.acquirePermit(url)

                val httpResponse: HttpResponse =
                    client.get(url) {
                        parameter("startAt", startAt)
                        parameter("maxResults", max)
                        parameter("status", "live")
                    }

                if (!httpResponse.status.isSuccess()) {
                    handleHttpError(conn, httpResponse)
                }

                val response = httpResponse.body<ProjectSearchResponseDto>()
                val values = response.values.orEmpty()

                for (p in values) {
                    val key = p.key ?: continue
                    val name = p.name ?: key
                    results += JiraProjectKey(key) to name
                }

                val pageCount = values.size
                startAt += pageCount
                val total = response.total ?: (startAt + 1)

                client.close()

                if (pageCount == 0 || startAt >= total) break
            } catch (e: Exception) {
                logger.error(e) { "Failed to list projects for connection ${conn.tenant.value}" }
                client.close()
                throw e
            }
        } while (true)

        return results
    }

    override suspend fun projectExists(
        conn: AtlassianConnection,
        key: JiraProjectKey,
    ): Boolean =
        try {
            val siteUrl = "https://${conn.tenant.value}"
            val client = createHttpClient(conn)
            try {
                val url = "$siteUrl/rest/api/3/project/${key.value}"
                rateLimiter.acquirePermit(url)

                val httpResponse: HttpResponse = client.get(url)
                client.close()
                httpResponse.status.isSuccess()
            } catch (e: Exception) {
                client.close()
                false
            }
        } catch (e: Exception) {
            false
        }

    override suspend fun searchIssues(
        conn: AtlassianConnection,
        jql: String,
        updatedSinceEpochMs: Long?,
        fields: List<String>,
        expand: List<String>,
        pageSize: Int,
    ): Flow<JiraIssue> =
        flow {
            val siteUrl = "https://${conn.tenant.value}"
            var startAt = 0
            val max = pageSize.coerceIn(1, 100)

            do {
                val client = createHttpClient(conn)
                try {
                    val url = "$siteUrl/rest/api/3/search"
                    rateLimiter.acquirePermit(url)

                    logger.debug { "JIRA search (POST): JQL='$jql' startAt=$startAt maxResults=$max" }

                    // Use POST instead of GET (Atlassian best practice)
                    // This avoids URL encoding issues with JQL containing quotes, spaces, etc.
                    val searchRequest =
                        SearchRequestDto(
                            jql = jql,
                            startAt = startAt,
                            maxResults = max,
                            fields = fields,
                            expand = expand.takeIf { it.isNotEmpty() },
                        )

                    val httpResponse: HttpResponse =
                        client.post(url) {
                            setBody(searchRequest)
                            contentType(ContentType.Application.Json)
                        }

                    if (!httpResponse.status.isSuccess()) {
                        val status = httpResponse.status.value
                        val body = runCatching { httpResponse.bodyAsText() }.getOrElse { "" }

                        // Special handling for 410 Gone
                        if (status == 410) {
                            client.close()
                            throw IllegalStateException(
                                "JIRA API 410 Gone for ${conn.tenant.value}: JQL='$jql', fields='${fields.joinToString(",")}'. " +
                                    "Possible causes: 1) Invalid JQL syntax, 2) Deprecated field in query, 3) Project access removed. " +
                                    "Response body: ${body.take(500)}",
                            )
                        }

                        client.close()
                        handleHttpError(conn, httpResponse)
                    }

                    val response = httpResponse.body<SearchResponseDto>()
                    val issues = response.issues.orEmpty()

                    logger.debug { "JIRA search returned ${issues.size} issues (total=${response.total})" }

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
                                    description = f.description?.let { extractTextFromAdf(it) },
                                    type = f.issuetype?.name ?: "Task",
                                    status = f.status?.name ?: "Unknown",
                                    assignee = f.assignee?.accountId?.let { JiraAccountId(it) },
                                    reporter = f.reporter?.accountId?.let { JiraAccountId(it) },
                                    created = created,
                                    updated = updated ?: created,
                                )
                            emit(issue)
                        }
                    }

                    val pageCount = issues.size
                    startAt += pageCount
                    val total = response.total ?: 0

                    client.close()

                    if (pageCount == 0 || startAt >= total) break
                } catch (e: Exception) {
                    logger.error(e) { "JIRA search failed for ${conn.tenant.value}: JQL='$jql'" }
                    client.close()
                    throw e
                }
            } while (true)
        }

    override suspend fun fetchIssueComments(
        conn: AtlassianConnection,
        issueKey: String,
    ): Flow<Pair<String, String>> =
        flow {
            val siteUrl = "https://${conn.tenant.value}"
            var startAt = 0
            val max = 100

            do {
                val client = createHttpClient(conn)
                try {
                    val url = "$siteUrl/rest/api/3/issue/$issueKey/comment"
                    rateLimiter.acquirePermit(url)

                    val httpResponse: HttpResponse =
                        client.get(url) {
                            parameter("startAt", startAt)
                            parameter("maxResults", max)
                        }

                    if (!httpResponse.status.isSuccess()) {
                        client.close()
                        handleHttpError(conn, httpResponse)
                    }

                    val response = httpResponse.body<CommentsResponseDto>()
                    val comments = response.comments.orEmpty()

                    for (c in comments) {
                        val commentId = c.id ?: continue
                        val bodyAdf = c.body ?: continue
                        val bodyText = extractTextFromAdf(bodyAdf)
                        if (bodyText.isNotBlank()) {
                            emit(commentId to bodyText)
                        }
                    }

                    val pageCount = comments.size
                    startAt += pageCount
                    val total = response.total ?: 0

                    client.close()

                    if (pageCount == 0 || startAt >= total) break
                } catch (e: Exception) {
                    logger.error(e) { "Failed to fetch comments for issue $issueKey" }
                    client.close()
                    throw e
                }
            } while (true)
        }

    private fun createHttpClient(conn: AtlassianConnection): HttpClient =
        HttpClient {
            install(ContentNegotiation) {
                json(json)
            }
            defaultRequest {
                // Atlassian API uses Basic auth with email:token
                val credentials = "${conn.email ?: ""}:${conn.accessToken}"
                val encoded = java.util.Base64.getEncoder().encodeToString(credentials.toByteArray())
                header("Authorization", "Basic $encoded")
                contentType(ContentType.Application.Json)
            }
        }

    private suspend fun handleHttpError(conn: AtlassianConnection, httpResponse: HttpResponse) {
        val status = httpResponse.status.value
        val body = runCatching { httpResponse.bodyAsText() }.getOrElse { "" }
        if (status == 401 || status == 403) {
            throw JiraAuthException("Authentication failed for connection ${conn.tenant.value}: HTTP $status ${body.take(200)}")
        } else {
            throw IllegalStateException("JIRA API error: HTTP $status ${body.take(200)}")
        }
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

    private fun extractTextFromAdf(adf: AdfDocumentDto): String {
        val buf = StringBuilder()
        fun visit(content: List<AdfNodeDto>?) {
            content?.forEach { node ->
                node.text?.let { buf.append(it).append(" ") }
                visit(node.content)
            }
        }
        visit(adf.content)
        return buf.toString().trim()
    }

    // ========== Internal API Response DTOs ==========

    @Serializable
    private data class MyselfDto(
        val accountId: String,
    )

    @Serializable
    private data class BoardsResponseDto(
        val values: List<BoardDto>? = null,
        val total: Int? = null,
    )

    @Serializable
    private data class BoardDto(
        val id: Int? = null,
        val name: String? = null,
    )

    @Serializable
    private data class ProjectSearchResponseDto(
        val values: List<ProjectDto>? = null,
        val total: Int? = null,
    )

    @Serializable
    private data class ProjectDto(
        val key: String? = null,
        val name: String? = null,
    )

    @Serializable
    private data class SearchRequestDto(
        val jql: String,
        val startAt: Int,
        val maxResults: Int,
        val fields: List<String>,
        val expand: List<String>? = null,
    )

    @Serializable
    private data class SearchResponseDto(
        val startAt: Int? = null,
        val maxResults: Int? = null,
        val total: Int? = null,
        val issues: List<IssueDto>? = null,
    )

    @Serializable
    private data class IssueDto(
        val key: String? = null,
        val fields: FieldsDto? = null,
    )

    @Serializable
    private data class FieldsDto(
        val summary: String? = null,
        val description: AdfDocumentDto? = null,
        val status: StatusDto? = null,
        val assignee: UserRefDto? = null,
        val reporter: UserRefDto? = null,
        val updated: String? = null,
        val created: String? = null,
        val issuetype: IssueTypeDto? = null,
        val project: ProjectRefDto? = null,
    )

    @Serializable
    private data class StatusDto(
        val name: String? = null,
    )

    @Serializable
    private data class IssueTypeDto(
        val name: String? = null,
    )

    @Serializable
    private data class UserRefDto(
        val accountId: String? = null,
    )

    @Serializable
    private data class ProjectRefDto(
        val key: String? = null,
    )

    @Serializable
    private data class AdfDocumentDto(
        val version: Int? = null,
        val type: String? = null,
        val content: List<AdfNodeDto>? = null,
    )

    @Serializable
    private data class AdfNodeDto(
        val type: String? = null,
        val text: String? = null,
        val content: List<AdfNodeDto>? = null,
    )

    @Serializable
    private data class CommentsResponseDto(
        val comments: List<CommentDto>? = null,
        val startAt: Int? = null,
        val maxResults: Int? = null,
        val total: Int? = null,
    )

    @Serializable
    private data class CommentDto(
        val id: String? = null,
        val body: AdfDocumentDto? = null,
    )
}

class JiraAuthException(message: String) : RuntimeException(message)
