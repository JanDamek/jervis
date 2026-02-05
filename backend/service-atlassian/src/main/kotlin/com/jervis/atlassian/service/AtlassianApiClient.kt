package com.jervis.atlassian.service

import com.jervis.common.dto.atlassian.AtlassianConnection
import com.jervis.common.dto.atlassian.AtlassianMyselfRequest
import com.jervis.common.dto.atlassian.AtlassianUserDto
import com.jervis.common.dto.atlassian.ConfluenceAttachmentDownloadRequest
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
import com.jervis.common.dto.bugtracker.BugTrackerProjectDto
import com.jervis.common.dto.bugtracker.BugTrackerProjectsRequest
import com.jervis.common.dto.bugtracker.BugTrackerProjectsResponse
import com.jervis.common.dto.wiki.WikiSpaceDto
import com.jervis.common.dto.wiki.WikiSpacesRequest
import com.jervis.common.dto.wiki.WikiSpacesResponse
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
import io.ktor.util.encodeBase64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.nio.charset.StandardCharsets

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
    val self: String? = null,
    val fields: Map<String, kotlinx.serialization.json.JsonElement>,
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

@Serializable
private data class JiraIssueTypeDto(
    val id: String,
    val name: String,
    val description: String? = null,
    val subtask: Boolean = false,
)

@Serializable
private data class JiraProjectDto(
    val id: String,
    val key: String,
    val name: String,
)

@Serializable
private data class JiraComponentDto(
    val id: String,
    val name: String,
    val description: String? = null,
)

@Serializable
private data class JiraVersionDto(
    val id: String,
    val name: String,
    val released: Boolean = false,
    val releaseDate: String? = null,
)

@Serializable
private data class JiraIssueRefDto(
    val id: String,
    val key: String,
    val self: String? = null,
)

@Serializable
private data class JiraAttachmentDto(
    val id: String,
    val filename: String,
    val mimeType: String? = null,
    val size: Long? = null,
    val content: String? = null,
)

@Serializable
private data class JiraIssueFieldsDto(
    val summary: String? = null,
    val description: kotlinx.serialization.json.JsonElement? = null,
    val updated: String? = null,
    val created: String? = null,
    val status: JiraStatusDto? = null,
    val priority: JiraPriorityDto? = null,
    val assignee: JiraUserDto? = null,
    val reporter: JiraUserDto? = null,
    val issuetype: JiraIssueTypeDto? = null,
    val project: JiraProjectDto? = null,
    val labels: List<String>? = null,
    val components: List<JiraComponentDto>? = null,
    val fixVersions: List<JiraVersionDto>? = null,
    val parent: JiraIssueRefDto? = null,
    val subtasks: List<JiraIssueRefDto>? = null,
    val attachment: List<JiraAttachmentDto>? = null,
)

@Serializable
private data class JiraRenderedFieldsDto(
    val description: String? = null,
)

@Serializable
private data class JiraIssueDetailDto(
    val key: String,
    val id: String,
    val fields: JiraIssueFieldsDto,
    val renderedFields: JiraRenderedFieldsDto? = null,
)

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
    val ancestors: List<ConfluenceAncestorDto>? = null,
)

@Serializable
private data class ConfluenceAncestorDto(
    val id: String,
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

@Serializable
private data class ConfluenceLabelDto(
    val name: String,
)

@Serializable
private data class ConfluenceLabelsDto(
    val results: List<ConfluenceLabelDto>? = null,
)

@Serializable
private data class ConfluenceMetadataDto(
    val labels: ConfluenceLabelsDto? = null,
)

@Serializable
private data class ConfluenceBodyStorageDto(
    val value: String,
    val representation: String,
)

@Serializable
private data class ConfluenceBodyDto(
    val storage: ConfluenceBodyStorageDto? = null,
)

@Serializable
private data class ConfluenceAttachmentLinksDto(
    val download: String? = null,
)

@Serializable
private data class ConfluenceAttachmentDto(
    val id: String,
    val title: String,
    val type: String,
    val metadata: kotlinx.serialization.json.JsonObject? = null,
    val extensions: kotlinx.serialization.json.JsonObject? = null,
    @SerialName("_links")
    val links: ConfluenceAttachmentLinksDto? = null,
)

@Serializable
private data class ConfluenceChildrenResultsDto(
    val results: List<ConfluenceAttachmentDto>? = null,
)

@Serializable
private data class ConfluenceChildrenDto(
    val attachment: ConfluenceChildrenResultsDto? = null,
)

@Serializable
private data class ConfluencePlainDescriptionDto(
    val value: String? = null,
)

@Serializable
private data class ConfluenceSpaceDescriptionDto(
    val plain: ConfluencePlainDescriptionDto? = null,
)

@Serializable
private data class ConfluenceSpaceItemDto(
    val id: Long,
    val key: String,
    val name: String,
    val type: String? = null,
    val description: ConfluenceSpaceDescriptionDto? = null,
)

@Serializable
private data class ConfluenceSpacesResultDto(
    val results: List<ConfluenceSpaceItemDto>? = null,
    val size: Int? = null,
)

@Serializable
private data class ConfluencePageDetailDto(
    val id: String,
    val title: String,
    val type: String? = null,
    val status: String? = null,
    val space: ConfluenceSpaceDto? = null,
    val version: ConfluenceVersionDto? = null,
    val body: ConfluenceBodyDto? = null,
    val history: ConfluenceHistoryDto? = null,
    val ancestors: List<ConfluenceAncestorDto>? = null,
    val metadata: ConfluenceMetadataDto? = null,
    val children: ConfluenceChildrenDto? = null,
)

class AtlassianApiClient(
    private val sharedHttpClient: HttpClient? = null,
) {
    private val logger = KotlinLogging.logger {}

    private val rateLimiter = DomainRateLimiter(RateLimitConfig(maxRequestsPerSecond = 10, maxRequestsPerMinute = 100))

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private fun httpClient(timeoutMs: Long?): HttpClient =
        sharedHttpClient ?: HttpClient(CIO) {
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
                    ("${auth.username}:${auth.password}").toByteArray(StandardCharsets.UTF_8).encodeBase64()
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
        if (!UrlUtils.isInternalUrl(url)) {
            val domain = UrlUtils.extractDomain(url)
            logger.debug { "Applying rate limit for domain: $domain" }
            rateLimiter.acquire(domain)
        }

        val client = httpClient(null)
        return block(client, url)
    }

    suspend fun getMyself(request: AtlassianMyselfRequest): AtlassianUserDto {
        logger.info { "Resolving Atlassian user for baseUrl=${request.baseUrl}" }
        val auth =
            when (request.authType.uppercase()) {
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

        val isCloud = request.baseUrl.contains("atlassian.net")
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
                    client.request(url) {
                        method = HttpMethod.Get
                        applyAuth(conn)
                        url {
                            parameters.append("jql", request.jql)
                            parameters.append("startAt", request.startAt.toString())
                            parameters.append("maxResults", request.maxResults.toString())
                            parameters.append(
                                "fields",
                                "summary,updated,created,status,project,issuetype,priority,assignee,reporter,labels,components,fixversions",
                            )
                        }
                    }
                } else {
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
                                        "updated",
                                        "created",
                                        "status",
                                        "project",
                                        "issuetype",
                                        "priority",
                                        "assignee",
                                        "reporter",
                                        "labels",
                                        "components",
                                        "fixversions",
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
                response.body<String>().let {
                    logger.debug { "Jira search response body: $it" }
                    json.decodeFromString(JiraSearchResultDto.serializer(), it)
                }
            }.onFailure {
                logger.error(it) { "Failed to deserialize Jira search response" }
            }.getOrNull()

        val issues =
            dto?.issues?.map { issue ->
                val fields = issue.fields
                com.jervis.common.dto.atlassian.JiraIssueSummary(
                    key = issue.key,
                    id = issue.id,
                    self = issue.self,
                    fields =
                        com.jervis.common.dto.atlassian.JiraIssueFields(
                            summary = fields["summary"]?.let { json.decodeFromJsonElement(String.serializer(), it) },
                            description = null,
                            updated = fields["updated"]?.let { json.decodeFromJsonElement(String.serializer(), it) },
                            created = fields["created"]?.let { json.decodeFromJsonElement(String.serializer(), it) },
                            status =
                                fields["status"]?.let {
                                    runCatching {
                                        json.decodeFromJsonElement(JiraStatusDto.serializer(), it)
                                    }.getOrNull()?.let { dto ->
                                        com.jervis.common.dto.atlassian
                                            .JiraStatus(name = dto.name, id = dto.id)
                                    }
                                },
                            priority =
                                fields["priority"]?.let {
                                    runCatching {
                                        json.decodeFromJsonElement(JiraPriorityDto.serializer(), it)
                                    }.getOrNull()?.let { dto ->
                                        com.jervis.common.dto.atlassian
                                            .JiraPriority(name = dto.name, id = dto.id)
                                    }
                                },
                            assignee =
                                fields["assignee"]?.let {
                                    runCatching {
                                        json.decodeFromJsonElement(JiraUserDto.serializer(), it)
                                    }.getOrNull()?.let { dto ->
                                        com.jervis.common.dto.atlassian.JiraUser(
                                            accountId = dto.accountId,
                                            displayName = dto.displayName,
                                            emailAddress = dto.emailAddress,
                                        )
                                    }
                                },
                            reporter =
                                fields["reporter"]?.let {
                                    runCatching {
                                        json.decodeFromJsonElement(JiraUserDto.serializer(), it)
                                    }.getOrNull()?.let { dto ->
                                        com.jervis.common.dto.atlassian.JiraUser(
                                            accountId = dto.accountId,
                                            displayName = dto.displayName,
                                            emailAddress = dto.emailAddress,
                                        )
                                    }
                                },
                            issueType =
                                fields["issuetype"]?.let {
                                    runCatching {
                                        json.decodeFromJsonElement(JiraIssueTypeDto.serializer(), it)
                                    }.getOrNull()?.let { dto ->
                                        com.jervis.common.dto.atlassian.JiraIssueType(
                                            id = dto.id,
                                            name = dto.name,
                                            description = dto.description,
                                            subtask = dto.subtask,
                                        )
                                    }
                                },
                            project =
                                fields["project"]?.let {
                                    runCatching {
                                        json.decodeFromJsonElement(JiraProjectDto.serializer(), it)
                                    }.getOrNull()?.let { dto ->
                                        com.jervis.common.dto.atlassian.JiraProject(
                                            id = dto.id,
                                            key = dto.key,
                                            name = dto.name,
                                        )
                                    }
                                },
                            labels =
                                fields["labels"]?.let {
                                    runCatching {
                                        json.decodeFromJsonElement(
                                            kotlinx.serialization.builtins.ListSerializer(String.serializer()),
                                            it,
                                        )
                                    }.getOrNull()
                                },
                            components =
                                fields["components"]?.let {
                                    runCatching {
                                        json.decodeFromJsonElement(
                                            kotlinx.serialization.builtins.ListSerializer(
                                                JiraComponentDto.serializer(),
                                            ),
                                            it,
                                        )
                                    }.getOrNull()?.map { dto ->
                                        com.jervis.common.dto.atlassian.JiraComponent(
                                            id = dto.id,
                                            name = dto.name,
                                            description = dto.description,
                                        )
                                    }
                                },
                            fixVersions =
                                fields["fixVersions"]?.let {
                                    runCatching {
                                        json.decodeFromJsonElement(
                                            kotlinx.serialization.builtins.ListSerializer(
                                                JiraVersionDto.serializer(),
                                            ),
                                            it,
                                        )
                                    }.getOrNull()?.map { dto ->
                                        com.jervis.common.dto.atlassian.JiraVersion(
                                            id = dto.id,
                                            name = dto.name,
                                            released = dto.released,
                                            releaseDate = dto.releaseDate,
                                        )
                                    }
                                },
                            parent = null,
                            subtasks = null,
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
        val isCloud = request.baseUrl.contains("atlassian.net")
        val apiVersion = if (isCloud) "3" else "2"
        val url =
            "${
                conn.baseUrl.trimEnd(
                    '/',
                )
            }/rest/api/$apiVersion/issue/${request.issueKey}"

        val response =
            rateLimitedRequest(url) { client, _ ->
                client.request(url) {
                    method = HttpMethod.Get
                    applyAuth(conn)
                    url {
                        parameters.append("fields", "*all")
                        parameters.append("expand", "changelog,renderedFields")
                    }
                }
            }

        require(response.status.value in 200..299) {
            "Jira API request failed with status=${response.status.value}"
        }

        val dto = response.body<JiraIssueDetailDto>()

        return JiraIssueResponse(
            key = dto.key,
            id = dto.id,
            fields =
                JiraIssueFields(
                    summary = dto.fields.summary,
                    description = dto.fields.description,
                    updated = dto.fields.updated,
                    created = dto.fields.created,
                    status =
                        dto.fields.status?.let {
                            com.jervis.common.dto.atlassian.JiraStatus(
                                name = it.name,
                                id = it.id,
                            )
                        },
                    priority =
                        dto.fields.priority?.let {
                            com.jervis.common.dto.atlassian.JiraPriority(
                                name = it.name,
                                id = it.id,
                            )
                        },
                    assignee =
                        dto.fields.assignee?.let {
                            com.jervis.common.dto.atlassian.JiraUser(
                                accountId = it.accountId,
                                displayName = it.displayName,
                                emailAddress = it.emailAddress,
                            )
                        },
                    reporter =
                        dto.fields.reporter?.let {
                            com.jervis.common.dto.atlassian.JiraUser(
                                accountId = it.accountId,
                                displayName = it.displayName,
                                emailAddress = it.emailAddress,
                            )
                        },
                    issueType =
                        dto.fields.issuetype?.let {
                            com.jervis.common.dto.atlassian.JiraIssueType(
                                id = it.id,
                                name = it.name,
                                description = it.description,
                                subtask = it.subtask,
                            )
                        },
                    project =
                        dto.fields.project?.let {
                            com.jervis.common.dto.atlassian.JiraProject(
                                id = it.id,
                                key = it.key,
                                name = it.name,
                            )
                        },
                    labels = dto.fields.labels,
                    components =
                        dto.fields.components?.map {
                            com.jervis.common.dto.atlassian.JiraComponent(
                                id = it.id,
                                name = it.name,
                                description = it.description,
                            )
                        },
                    fixVersions =
                        dto.fields.fixVersions?.map {
                            com.jervis.common.dto.atlassian.JiraVersion(
                                id = it.id,
                                name = it.name,
                                released = it.released,
                                releaseDate = it.releaseDate,
                            )
                        },
                    parent =
                        dto.fields.parent?.let {
                            com.jervis.common.dto.atlassian.JiraIssueRef(
                                id = it.id,
                                key = it.key,
                                self = it.self,
                            )
                        },
                    subtasks =
                        dto.fields.subtasks?.map {
                            com.jervis.common.dto.atlassian.JiraIssueRef(
                                id = it.id,
                                key = it.key,
                                self = it.self,
                            )
                        },
                    attachments =
                        dto.fields.attachment?.map {
                            com.jervis.common.dto.atlassian.JiraAttachment(
                                id = it.id,
                                filename = it.filename,
                                mimeType = it.mimeType,
                                size = it.size,
                                content = it.content,
                            )
                        },
                ),
            renderedDescription = dto.renderedFields?.description,
        )
    }

    suspend fun searchConfluencePages(request: ConfluenceSearchRequest): ConfluenceSearchResponse {
        logger.info {
            "Searching Confluence pages with CQL: ${request.cql}, spaceKey: ${request.spaceKey}, lastModifiedSince: ${request.lastModifiedSince}"
        }
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

        val cqlQuery: String =
            when {
                request.cql.isNullOrBlank().not() -> {
                    request.cql
                }

                request.lastModifiedSince != null -> {
                    val instant = java.time.Instant.parse(request.lastModifiedSince)
                    val fmt =
                        java.time.format.DateTimeFormatter
                            .ofPattern("yyyy-MM-dd HH:mm")
                            .withZone(java.time.ZoneOffset.UTC)
                    val formattedDate = fmt.format(instant)
                    val datePart = "type=page AND lastModified >= \"$formattedDate\""
                    if (request.spaceKey
                            .isNullOrBlank()
                            .not()
                    ) {
                        "$datePart AND space = \"${request.spaceKey}\""
                    } else {
                        datePart
                    }
                }

                request.spaceKey.isNullOrBlank().not() -> {
                    "type=page AND space = \"${request.spaceKey}\""
                }

                else -> {
                    "type=page"
                }
            }.toString()

        val baseUrl = "${conn.baseUrl.trimEnd('/')}/wiki/rest/api/content/search"

        val response =
            rateLimitedRequest(baseUrl) { client, _ ->
                client.request(baseUrl) {
                    method = HttpMethod.Get
                    applyAuth(conn)

                    url {
                        parameters.append("cql", cqlQuery)
                        parameters.append("start", request.startAt.toString())
                        parameters.append("limit", request.maxResults.toString())
                        parameters.append("expand", "version")
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
                    type = page.type,
                    status = null,
                    spaceKey = page.space?.key,
                    spaceName = page.space?.name,
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
                    lastModified = page.version?.`when`,
                    createdDate = page.version?.`when`,
                    body = null,
                    excerpt = null,
                    labels = null,
                    parentId = null,
                    ancestors = null,
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
        val url = "${conn.baseUrl.trimEnd('/')}/wiki/rest/api/content/${request.pageId}"

        val response =
            rateLimitedRequest(url) { client, _ ->
                client.request(url) {
                    method = HttpMethod.Get
                    applyAuth(conn)
                    url {
                        parameters.append(
                            "expand",
                            "version,space,body.storage,metadata.labels,ancestors,history.lastUpdated,children.attachment",
                        )
                    }
                }
            }

        if (response.status.value !in 200..299) {
            logger.warn { "Confluence get page failed with status=${response.status.value}" }
            return ConfluencePageResponse(
                id = "",
                title = "",
                type = null,
                status = null,
                spaceKey = null,
                spaceName = null,
                version = null,
                body = null,
                lastModified = null,
                createdDate = null,
                labels = null,
                parentId = null,
                ancestors = null,
                attachments = null,
            )
        }

        val dto =
            runCatching {
                response.body<String>().let { json.decodeFromString(ConfluencePageDetailDto.serializer(), it) }
            }.getOrNull()

        return ConfluencePageResponse(
            id = dto?.id ?: "",
            title = dto?.title ?: "",
            type = dto?.type,
            status = dto?.status,
            spaceKey = dto?.space?.key,
            spaceName = dto?.space?.name,
            version =
                dto?.version?.let {
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
            body =
                dto?.body?.let {
                    com.jervis.common.dto.atlassian.ConfluenceBody(
                        storage =
                            it.storage?.let { storage ->
                                com.jervis.common.dto.atlassian.ConfluenceStorage(
                                    value = storage.value,
                                    representation = storage.representation,
                                )
                            },
                        view = null,
                    )
                },
            lastModified = dto?.history?.lastUpdated?.`when` ?: dto?.version?.`when`,
            createdDate = dto?.version?.`when`,
            labels =
                dto
                    ?.metadata
                    ?.labels
                    ?.results
                    ?.map { it.name },
            parentId = dto?.ancestors?.lastOrNull()?.id,
            ancestors = dto?.ancestors?.map { it.id },
            attachments =
                dto
                    ?.children
                    ?.attachment
                    ?.results
                    ?.filter { attachment ->
                        attachment.links?.download != null
                    }?.map { attachment ->
                        val mediaType =
                            attachment.extensions?.get("mediaType")?.let {
                                json.decodeFromJsonElement(String.serializer(), it)
                            }
                        val fileSize =
                            attachment.extensions?.get("fileSize")?.let {
                                runCatching { json.decodeFromJsonElement(Long.serializer(), it) }.getOrNull()
                            }
                        com.jervis.common.dto.atlassian.ConfluenceAttachment(
                            id = attachment.id,
                            title = attachment.title,
                            type = attachment.type,
                            mediaType = mediaType,
                            fileSize = fileSize,
                            downloadUrl = attachment.links?.download,
                        )
                    },
        )
    }

    suspend fun downloadJiraAttachment(request: JiraAttachmentDownloadRequest): ByteArray? {
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

        logger.debug { "Downloading Jira attachment (Ktor will follow redirects): ${request.attachmentUrl}" }

        val response =
            rateLimitedRequest(request.attachmentUrl) { client, url ->
                client.request(url) {
                    method = HttpMethod.Get
                    applyAuth(conn)
                }
            }

        if (response.status.value !in 200..299) {
            logger.warn { "Jira attachment download failed with status=${response.status.value}, URL: ${request.attachmentUrl}" }
            return null
        }

        val bytes = response.body<ByteArray>()
        logger.debug { "Successfully downloaded Jira attachment, size=${bytes?.size ?: 0} bytes" }
        return bytes
    }

    suspend fun downloadConfluenceAttachment(request: ConfluenceAttachmentDownloadRequest): ByteArray? {
        logger.info { "Downloading Confluence attachment from: ${request.attachmentDownloadUrl}" }
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

        logger.debug { "Downloading Confluence attachment (Ktor will follow redirects): ${request.attachmentDownloadUrl}" }

        val response =
            rateLimitedRequest(request.attachmentDownloadUrl) { client, url ->
                client.request(url) {
                    method = HttpMethod.Get
                    applyAuth(conn)
                }
            }

        if (response.status.value !in 200..299) {
            logger.warn {
                "Confluence attachment download failed with status=${response.status.value}, URL: ${request.attachmentDownloadUrl}"
            }
            return null
        }

        val bytes = response.body<ByteArray>()
        logger.debug { "Successfully downloaded Confluence attachment, size=${bytes?.size ?: 0} bytes" }
        return bytes
    }

    suspend fun listJiraProjects(request: BugTrackerProjectsRequest): BugTrackerProjectsResponse {
        logger.info { "Listing Jira projects for baseUrl=${request.baseUrl}" }
        val auth =
            when (request.authType.uppercase()) {
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

        val isCloud = request.baseUrl.contains("atlassian.net")
        val apiVersion = if (isCloud) "3" else "2"
        val url = "${conn.baseUrl.trimEnd('/')}/rest/api/$apiVersion/project"

        val response =
            rateLimitedRequest(url) { client, _ ->
                client.request(url) {
                    method = HttpMethod.Get
                    applyAuth(conn)
                }
            }

        if (response.status.value !in 200..299) {
            logger.warn { "Jira list projects failed with status=${response.status.value}" }
            return BugTrackerProjectsResponse(projects = emptyList())
        }

        val projects =
            runCatching {
                response.body<String>().let {
                    json.decodeFromString(
                        kotlinx.serialization.builtins.ListSerializer(JiraProjectDto.serializer()),
                        it,
                    )
                }
            }.onFailure {
                logger.error(it) { "Failed to deserialize Jira projects response" }
            }.getOrNull()

        return BugTrackerProjectsResponse(
            projects = projects?.map { project ->
                BugTrackerProjectDto(
                    id = project.id,
                    key = project.key,
                    name = project.name,
                    description = null,
                    url = "${conn.baseUrl.trimEnd('/')}/browse/${project.key}",
                )
            } ?: emptyList(),
        )
    }

    suspend fun listConfluenceSpaces(request: WikiSpacesRequest): WikiSpacesResponse {
        logger.info { "Listing Confluence spaces for baseUrl=${request.baseUrl}" }
        val auth =
            when (request.authType.uppercase()) {
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

        val url = "${conn.baseUrl.trimEnd('/')}/wiki/rest/api/space"

        val response =
            rateLimitedRequest(url) { client, _ ->
                client.request(url) {
                    method = HttpMethod.Get
                    applyAuth(conn)
                    url {
                        parameters.append("limit", "100")
                        parameters.append("expand", "description.plain")
                    }
                }
            }

        if (response.status.value !in 200..299) {
            logger.warn { "Confluence list spaces failed with status=${response.status.value}" }
            return WikiSpacesResponse(spaces = emptyList())
        }

        val dto =
            runCatching {
                response.body<String>().let {
                    json.decodeFromString(ConfluenceSpacesResultDto.serializer(), it)
                }
            }.onFailure {
                logger.error(it) { "Failed to deserialize Confluence spaces response" }
            }.getOrNull()

        return WikiSpacesResponse(
            spaces = dto?.results?.map { space ->
                WikiSpaceDto(
                    id = space.id.toString(),
                    key = space.key,
                    name = space.name,
                    description = space.description?.plain?.value,
                    url = "${conn.baseUrl.trimEnd('/')}/wiki/spaces/${space.key}",
                )
            } ?: emptyList(),
        )
    }
}
