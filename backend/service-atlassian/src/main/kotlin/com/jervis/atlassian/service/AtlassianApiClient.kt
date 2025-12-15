package com.jervis.atlassian.service

import com.jervis.common.dto.atlassian.AtlassianConnection
import com.jervis.common.dto.atlassian.AtlassianMyselfRequest
import com.jervis.common.dto.atlassian.AtlassianUserDto
import com.jervis.common.dto.atlassian.ConfluencePageRequest
import com.jervis.common.dto.atlassian.ConfluencePageResponse
import com.jervis.common.dto.atlassian.ConfluenceSearchRequest
import com.jervis.common.dto.atlassian.ConfluenceSearchResponse
import com.jervis.common.dto.atlassian.JiraAttachmentDownloadRequest
import com.jervis.common.dto.atlassian.JiraIssueFields
import com.jervis.common.dto.atlassian.JiraIssueRequest
import com.jervis.common.dto.atlassian.JiraIssueResponse
import com.jervis.common.dto.atlassian.JiraSearchRequest
import com.jervis.common.dto.atlassian.JiraSearchResponse
import com.jervis.common.ratelimit.DomainRateLimiter
import com.jervis.common.ratelimit.RateLimitConfig
import com.jervis.common.ratelimit.UrlUtils
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.stereotype.Service

// ============= Internal DTOs for Jira API responses =============

@Serializable
private data class JiraSearchResultDto(
    val total: Int? = null,
    val startAt: Int? = null,
    val maxResults: Int? = null,
    val issues: List<JiraIssueDtoItem>? = null,
)

@Serializable
private data class JiraIssueDtoItem(
    val key: String,
    val id: String,
    val fields: JiraFieldsDto,
)

@Serializable
private data class JiraFieldsDto(
    val summary: String? = null,
    val description: kotlinx.serialization.json.JsonElement? = null,
    val updated: String? = null,
    val created: String? = null,
    val status: JiraStatusDto? = null,
    val priority: JiraPriorityDto? = null,
    val assignee: JiraUserDto? = null,
    val reporter: JiraUserDto? = null,
)

@Serializable
private data class JiraStatusDto(
    val name: String,
    val id: String? = null,
)

@Serializable
private data class JiraPriorityDto(
    val name: String,
    val id: String? = null,
)

@Serializable
private data class JiraUserDto(
    val accountId: String,
    val displayName: String? = null,
    val emailAddress: String? = null,
)

// ============= Internal DTOs for Confluence API responses =============

@Serializable
private data class ConfluenceSearchResultDto(
    val size: Int? = null,
    val start: Int? = null,
    val limit: Int? = null,
    val results: List<ConfluencePageDto>? = null,
)

@Serializable
private data class ConfluencePageDto(
    val id: String,
    val title: String,
    val type: String? = null,
    val space: ConfluenceSpaceDto? = null,
    val version: ConfluenceVersionDto? = null,
    val history: ConfluenceHistoryDto? = null,
)

@Serializable
private data class ConfluenceSpaceDto(
    val key: String? = null,
    val name: String? = null,
)

@Serializable
private data class ConfluenceVersionDto(
    val number: Int,
    val `when`: String? = null,
    val by: ConfluenceUserDto? = null,
)

@Serializable
private data class ConfluenceHistoryDto(
    val lastUpdated: ConfluenceLastUpdatedDto? = null,
)

@Serializable
private data class ConfluenceLastUpdatedDto(
    val `when`: String? = null,
)

@Serializable
private data class ConfluenceUserDto(
    val accountId: String,
    val displayName: String? = null,
    val email: String? = null,
)

@Service
class AtlassianApiClient {
    private val logger = KotlinLogging.logger {}

    // Default rate limit for Atlassian: 10 req/sec, 100 req/min
    // See: https://developer.atlassian.com/cloud/jira/platform/rate-limiting/
    private val rateLimiter = DomainRateLimiter(RateLimitConfig(maxRequestsPerSecond = 10, maxRequestsPerMinute = 100))

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private fun httpClient(timeoutMs: Long?): HttpClient =
        HttpClient(CIO) {
            install(ContentNegotiation) { json(json) }
            install(Logging) {
                logger =
                    object : Logger {
                        override fun log(message: String) {
                            this@AtlassianApiClient.logger.debug { message }
                        }
                    }
                level = LogLevel.INFO
            }
            install(HttpTimeout) {
                val t = timeoutMs ?: 30_000
                requestTimeoutMillis = t
                connectTimeoutMillis = t
                socketTimeoutMillis = t
            }
        }

    private fun HttpRequestBuilder.applyAuth(conn: AtlassianConnection) {
        when (val auth = conn.auth) {
            is com.jervis.common.dto.atlassian.AtlassianAuth.None -> {}

            is com.jervis.common.dto.atlassian.AtlassianAuth.Basic -> {
                val basic =
                    java.util.Base64
                        .getEncoder()
                        .encodeToString("${auth.username}:${auth.password}".toByteArray())
                headers.append(HttpHeaders.Authorization, "Basic $basic")
            }

            is com.jervis.common.dto.atlassian.AtlassianAuth.Bearer -> {
                headers.append(HttpHeaders.Authorization, "Bearer ${auth.token}")
            }
        }
    }

    /**
     * Execute HTTP request with rate limiting.
     * Automatically applies rate limiting based on the target domain.
     */
    private suspend fun <T> rateLimitedRequest(
        url: String,
        block: suspend (HttpClient, String) -> T,
    ): T {
        // Apply rate limiting before request (skip for internal URLs)
        if (!UrlUtils.isInternalUrl(url)) {
            val domain = UrlUtils.extractDomain(url)
            logger.debug { "Applying rate limit for domain: $domain" }
            rateLimiter.acquire(domain)
        }

        // Execute the request
        val client = httpClient(null)
        return block(client, url)
    }

    suspend fun getMyself(request: AtlassianMyselfRequest): AtlassianUserDto {
        logger.info { "Resolving Atlassian user for baseUrl=${request.baseUrl}" }
        val auth =
            when (request.authType?.uppercase()) {
                "BASIC" -> {
                    com.jervis.common.dto.atlassian.AtlassianAuth.Basic(
                        request.basicUsername.orEmpty(),
                        request.basicPassword.orEmpty(),
                    )
                }

                "BEARER" -> {
                    com.jervis.common.dto.atlassian.AtlassianAuth
                        .Bearer(request.bearerToken.orEmpty())
                }

                else -> {
                    com.jervis.common.dto.atlassian.AtlassianAuth.None
                }
            }
        val conn = AtlassianConnection(baseUrl = request.baseUrl, auth = auth)
        // Detect Jira Cloud vs Server/DC
        val isCloud = request.baseUrl.contains("atlassian.net")
        val apiVersion = if (isCloud) "3" else "2"
        val url = "${conn.baseUrl.trimEnd('/')}/rest/api/$apiVersion/myself"

        val response =
            rateLimitedRequest(url) { client, _ ->
                client.request(url) {
                    method = HttpMethod.Get
                    applyAuth(conn)
                }
            }

        if (response.status.value !in 200..299) {
            logger.warn { "Jira /myself failed with status=${response.status.value}" }
            return AtlassianUserDto(displayName = "Unknown")
        }

        @Serializable
        data class JiraMyself(
            val accountId: String? = null,
            val emailAddress: String? = null,
            val displayName: String? = null,
        )

        val dto =
            runCatching {
                response.body<String>().let { json.decodeFromString(JiraMyself.serializer(), it) }
            }.getOrNull()
        return AtlassianUserDto(
            accountId = dto?.accountId,
            emailAddress = dto?.emailAddress,
            displayName = dto?.displayName,
        )
    }

    suspend fun searchJiraIssues(request: JiraSearchRequest): JiraSearchResponse {
        logger.info { "Searching Jira issues with JQL: ${request.jql}, baseUrl: ${request.baseUrl}" }
        val auth =
            when (request.authType?.uppercase()) {
                "BASIC" -> {
                    com.jervis.common.dto.atlassian.AtlassianAuth.Basic(
                        request.basicUsername.orEmpty(),
                        request.basicPassword.orEmpty(),
                    )
                }

                "BEARER" -> {
                    com.jervis.common.dto.atlassian.AtlassianAuth
                        .Bearer(request.bearerToken.orEmpty())
                }

                else -> {
                    com.jervis.common.dto.atlassian.AtlassianAuth.None
                }
            }
        val conn = AtlassianConnection(baseUrl = request.baseUrl, auth = auth)

        // Detect Jira Cloud vs Server/DC by baseUrl
        val isCloud = request.baseUrl.contains("atlassian.net")
        // Cloud uses new /search/jql endpoint (GET), Server/DC uses old /search endpoint (POST)
        // See: https://developer.atlassian.com/changelog/#CHANGE-2046
        val url =
            if (isCloud) {
                "${conn.baseUrl.trimEnd('/')}/rest/api/3/search/jql"
            } else {
                "${conn.baseUrl.trimEnd('/')}/rest/api/2/search"
            }

        logger.debug { "Using Jira API ${if (isCloud) "v3 /search/jql (GET)" else "v2 /search (POST)"}: $url" }

        val response =
            rateLimitedRequest(url) { client, _ ->
                if (isCloud) {
                    // Cloud: Use GET with query parameters
                    client.request(url) {
                        method = HttpMethod.Get
                        applyAuth(conn)
                        url {
                            parameters.append("jql", request.jql)
                            parameters.append("startAt", request.startAt.toString())
                            parameters.append("maxResults", request.maxResults.toString())
                            parameters.append(
                                "fields",
                                "summary,status,assignee,priority,description,created,updated,reporter,project",
                            )
                        }
                    }
                } else {
                    // Server/DC: Use POST with JSON body
                    @Serializable
                    data class JiraSearchBody(
                        val jql: String,
                        val startAt: Int,
                        val maxResults: Int,
                        val fields: List<String>,
                    )

                    client.request(url) {
                        method = HttpMethod.Post
                        contentType(ContentType.Application.Json)
                        applyAuth(conn)
                        setBody(
                            JiraSearchBody(
                                jql = request.jql,
                                startAt = request.startAt,
                                maxResults = request.maxResults,
                                fields =
                                    listOf(
                                        "summary",
                                        "status",
                                        "assignee",
                                        "priority",
                                        "description",
                                        "created",
                                        "updated",
                                        "reporter",
                                        "project",
                                    ),
                            ),
                        )
                    }
                }
            }

        if (response.status.value !in 200..299) {
            val responseBody = runCatching { response.body<String>() }.getOrNull() ?: "No response body"
            logger.warn { "Jira search failed with status=${response.status.value}, url=$url, response=$responseBody" }
            return JiraSearchResponse(total = 0, startAt = 0, maxResults = 0, issues = emptyList())
        }

        val dto =
            runCatching {
                response.body<String>().let { json.decodeFromString(JiraSearchResultDto.serializer(), it) }
            }.getOrNull()

        val issues =
            dto?.issues?.map { issue ->
                com.jervis.common.dto.atlassian.JiraIssueSummary(
                    key = issue.key,
                    id = issue.id,
                    fields =
                        com.jervis.common.dto.atlassian.JiraIssueFields(
                            summary = issue.fields.summary,
                            description = issue.fields.description,
                            updated = issue.fields.updated,
                            created = issue.fields.created,
                            status =
                                issue.fields.status?.let {
                                    com.jervis.common.dto.atlassian
                                        .JiraStatus(name = it.name, id = it.id)
                                },
                            priority =
                                issue.fields.priority?.let {
                                    com.jervis.common.dto.atlassian
                                        .JiraPriority(name = it.name, id = it.id)
                                },
                            assignee =
                                issue.fields.assignee?.let {
                                    com.jervis.common.dto.atlassian.JiraUser(
                                        accountId = it.accountId,
                                        displayName = it.displayName,
                                        emailAddress = it.emailAddress,
                                    )
                                },
                            reporter =
                                issue.fields.reporter?.let {
                                    com.jervis.common.dto.atlassian.JiraUser(
                                        accountId = it.accountId,
                                        displayName = it.displayName,
                                        emailAddress = it.emailAddress,
                                    )
                                },
                        ),
                )
            } ?: emptyList()

        return JiraSearchResponse(
            total = dto?.total ?: 0,
            startAt = dto?.startAt ?: 0,
            maxResults = dto?.maxResults ?: 0,
            issues = issues,
        )
    }

    suspend fun getJiraIssue(request: JiraIssueRequest): JiraIssueResponse {
        logger.info { "Getting Jira issue: ${request.issueKey}" }
        val auth =
            when (request.authType?.uppercase()) {
                "BASIC" -> {
                    com.jervis.common.dto.atlassian.AtlassianAuth.Basic(
                        request.basicUsername.orEmpty(),
                        request.basicPassword.orEmpty(),
                    )
                }

                "BEARER" -> {
                    com.jervis.common.dto.atlassian.AtlassianAuth
                        .Bearer(request.bearerToken.orEmpty())
                }

                else -> {
                    com.jervis.common.dto.atlassian.AtlassianAuth.None
                }
            }
        val conn = AtlassianConnection(baseUrl = request.baseUrl, auth = auth)
        // Detect Jira Cloud vs Server/DC
        val isCloud = request.baseUrl.contains("atlassian.net")
        val apiVersion = if (isCloud) "3" else "2"
        val url =
            "${
                conn.baseUrl.trimEnd(
                    '/',
                )
            }/rest/api/$apiVersion/issue/${request.issueKey}?fields=summary,description,status,priority,assignee,reporter,created,updated&expand=changelog"

        val response =
            rateLimitedRequest(url) { client, _ ->
                client.request(url) {
                    method = HttpMethod.Get
                    applyAuth(conn)
                }
            }

        if (response.status.value !in 200..299) {
            logger.warn { "Jira get issue failed with status=${response.status.value}" }
            return JiraIssueResponse(
                key = "",
                id = "",
                fields =
                    JiraIssueFields(
                        summary = null,
                        description = null,
                        updated = null,
                        created = null,
                        status = null,
                        priority = null,
                        assignee = null,
                        reporter = null,
                    ),
            )
        }

        @Serializable
        data class JiraIssueDto(
            val key: String? = null,
            val id: String? = null,
            val fields: Map<String, kotlinx.serialization.json.JsonElement>? = null,
        )

        val dto =
            runCatching {
                response.body<String>().let { json.decodeFromString(JiraIssueDto.serializer(), it) }
            }.getOrNull()
        return JiraIssueResponse(
            key = dto?.key ?: "",
            id = dto?.id ?: "",
            fields =
                JiraIssueFields(
                    summary = null,
                    description = null,
                    updated = null,
                    created = null,
                    status = null,
                    priority = null,
                    assignee = null,
                    reporter = null,
                ),
        )
    }

    suspend fun searchConfluencePages(request: ConfluenceSearchRequest): ConfluenceSearchResponse {
        logger.info { "Searching Confluence pages with CQL: ${request.cql}, spaceKey: ${request.spaceKey}, lastModifiedSince: ${request.lastModifiedSince}" }
        val auth =
            when (request.authType?.uppercase()) {
                "BASIC" -> {
                    com.jervis.common.dto.atlassian.AtlassianAuth.Basic(
                        request.basicUsername.orEmpty(),
                        request.basicPassword.orEmpty(),
                    )
                }

                "BEARER" -> {
                    com.jervis.common.dto.atlassian.AtlassianAuth
                        .Bearer(request.bearerToken.orEmpty())
                }

                else -> {
                    com.jervis.common.dto.atlassian.AtlassianAuth.None
                }
            }
        val conn = AtlassianConnection(baseUrl = request.baseUrl, auth = auth)

        // Build CQL query with proper date formatting for Confluence API
        val cqlQuery: String =
            when {
                request.cql.isNullOrBlank().not() -> request.cql
                request.lastModifiedSince != null -> {
                    // Format Instant to Confluence CQL date format: "yyyy-MM-dd HH:mm"
                    val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(java.time.ZoneOffset.UTC)
                    val formattedDate = fmt.format(request.lastModifiedSince)
                    val datePart = "lastModified >= \"$formattedDate\""
                    if (request.spaceKey.isNullOrBlank().not()) "$datePart AND space = \"${request.spaceKey}\"" else datePart
                }
                request.spaceKey.isNullOrBlank().not() -> "space = \"${request.spaceKey}\""
                else -> "type=page" // Default: search all pages
            }.toString()

        val baseUrl = "${conn.baseUrl.trimEnd('/')}/wiki/rest/api/content/search"

        val response =
            rateLimitedRequest(baseUrl) { client, _ ->
                client.request(baseUrl) {
                    method = HttpMethod.Get
                    applyAuth(conn)
                    // Use url parameters builder for proper URL encoding
                    url {
                        parameters.append("cql", cqlQuery)
                        parameters.append("start", request.startAt.toString())
                        parameters.append("limit", request.maxResults.toString())
                    }
                }
            }

        if (response.status.value !in 200..299) {
            logger.warn { "Confluence search failed with status=${response.status.value}" }
            return ConfluenceSearchResponse(total = 0, startAt = 0, maxResults = 0, pages = emptyList())
        }

        val dto =
            runCatching {
                response.body<String>().let { json.decodeFromString(ConfluenceSearchResultDto.serializer(), it) }
            }.getOrNull()

        val pages =
            dto?.results?.map { page ->
                com.jervis.common.dto.atlassian.ConfluencePageSummary(
                    id = page.id,
                    title = page.title,
                    spaceKey = page.space?.key,
                    version =
                        page.version?.let {
                            com.jervis.common.dto.atlassian.ConfluenceVersion(
                                number = it.number,
                                `when` = it.`when`,
                                by =
                                    it.by?.let { user ->
                                        com.jervis.common.dto.atlassian.ConfluenceUser(
                                            accountId = user.accountId,
                                            displayName = user.displayName,
                                            email = user.email,
                                        )
                                    },
                            )
                        },
                    lastModified = page.history?.lastUpdated?.`when` ?: page.version?.`when`,
                )
            } ?: emptyList()

        return ConfluenceSearchResponse(
            total = dto?.size ?: 0,
            startAt = dto?.start ?: 0,
            maxResults = dto?.limit ?: 0,
            pages = pages,
        )
    }

    suspend fun getConfluencePage(request: ConfluencePageRequest): ConfluencePageResponse {
        logger.info { "Getting Confluence page: ${request.pageId}" }
        val auth =
            when (request.authType?.uppercase()) {
                "BASIC" -> {
                    com.jervis.common.dto.atlassian.AtlassianAuth.Basic(
                        request.basicUsername.orEmpty(),
                        request.basicPassword.orEmpty(),
                    )
                }

                "BEARER" -> {
                    com.jervis.common.dto.atlassian.AtlassianAuth
                        .Bearer(request.bearerToken.orEmpty())
                }

                else -> {
                    com.jervis.common.dto.atlassian.AtlassianAuth.None
                }
            }
        val conn = AtlassianConnection(baseUrl = request.baseUrl, auth = auth)
        val url = "${conn.baseUrl.trimEnd('/')}/wiki/rest/api/content/${request.pageId}?expand=body.storage,version"

        val response =
            rateLimitedRequest(url) { client, _ ->
                client.request(url) {
                    method = HttpMethod.Get
                    applyAuth(conn)
                }
            }

        if (response.status.value !in 200..299) {
            logger.warn { "Confluence get page failed with status=${response.status.value}" }
            return ConfluencePageResponse(
                id = "",
                title = "",
                spaceKey = null,
                version = null,
                body = null,
                lastModified = null,
            )
        }

        @Serializable
        data class ConfluencePageDto(
            val id: String? = null,
            val title: String? = null,
        )

        val dto =
            runCatching {
                response.body<String>().let { json.decodeFromString(ConfluencePageDto.serializer(), it) }
            }.getOrNull()
        return ConfluencePageResponse(
            id = dto?.id ?: "",
            title = dto?.title ?: "",
            spaceKey = null,
            version = null,
            body = null,
            lastModified = null,
        )
    }

    suspend fun downloadJiraAttachment(request: JiraAttachmentDownloadRequest): ByteArray {
        logger.info { "Downloading Jira attachment from: ${request.attachmentUrl}" }
        val auth =
            when (request.authType?.uppercase()) {
                "BASIC" -> {
                    com.jervis.common.dto.atlassian.AtlassianAuth.Basic(
                        request.basicUsername.orEmpty(),
                        request.basicPassword.orEmpty(),
                    )
                }

                "BEARER" -> {
                    com.jervis.common.dto.atlassian.AtlassianAuth
                        .Bearer(request.bearerToken.orEmpty())
                }

                else -> {
                    com.jervis.common.dto.atlassian.AtlassianAuth.None
                }
            }
        val conn = AtlassianConnection(baseUrl = request.baseUrl, auth = auth)

        val response =
            rateLimitedRequest(request.attachmentUrl) { client, url ->
                client.request(url) {
                    method = HttpMethod.Get
                    applyAuth(conn)
                }
            }

        if (response.status.value !in 200..299) {
            logger.warn { "Jira attachment download failed with status=${response.status.value}" }
            return ByteArray(0)
        }

        return response.body<ByteArray>()
    }
}
