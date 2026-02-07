package com.jervis.gitlab.service

import com.jervis.common.http.checkProviderResponse
import com.jervis.common.http.paginateViaOffset
import com.jervis.common.ratelimit.DomainRateLimiter
import com.jervis.common.ratelimit.UrlUtils
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging

/**
 * Low-level GitLab API client with response validation, rate limiting, and pagination.
 */
class GitLabApiClient(
    private val httpClient: HttpClient,
    private val rateLimiter: DomainRateLimiter,
) {
    private val log = KotlinLogging.logger {}
    private val json = Json { ignoreUnknownKeys = true }

    private fun getApiUrl(baseUrl: String): String {
        val base = baseUrl.takeIf { it.isNotBlank() } ?: "https://gitlab.com"
        return "${base.trimEnd('/')}/api/v4"
    }

    private suspend fun rateLimit(url: String) {
        if (!UrlUtils.isInternalUrl(url)) {
            rateLimiter.acquire(UrlUtils.extractDomain(url))
        }
    }

    suspend fun getUser(baseUrl: String, token: String): GitLabUser {
        val apiUrl = getApiUrl(baseUrl)
        val url = "$apiUrl/user"
        rateLimit(url)
        val response = httpClient.get(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val responseText = response.checkProviderResponse("GitLab", "getUser")
        return json.decodeFromString(GitLabUser.serializer(), responseText)
    }

    suspend fun listProjects(baseUrl: String, token: String): List<GitLabProject> {
        val apiUrl = getApiUrl(baseUrl)
        return paginateViaOffset(
            provider = "GitLab",
            context = "listProjects",
            fetchPage = { page, perPage ->
                val url = "$apiUrl/projects"
                rateLimit(url)
                val response = httpClient.get(url) {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    parameter("per_page", perPage)
                    parameter("page", page)
                    parameter("order_by", "last_activity_at")
                    parameter("membership", true)
                }
                val responseText = response.checkProviderResponse("GitLab", "listProjects")
                val projects: List<GitLabProject> = json.decodeFromString(responseText)
                projects to response
            },
        )
    }

    suspend fun getProject(baseUrl: String, token: String, projectId: String): GitLabProject {
        val apiUrl = getApiUrl(baseUrl)
        val url = "$apiUrl/projects/${projectId.encodeURLParameter()}"
        rateLimit(url)
        val response = httpClient.get(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val responseText = response.checkProviderResponse("GitLab", "getProject")
        return json.decodeFromString(GitLabProject.serializer(), responseText)
    }

    suspend fun listIssues(baseUrl: String, token: String, projectId: String): List<GitLabIssue> {
        val apiUrl = getApiUrl(baseUrl)
        return paginateViaOffset(
            provider = "GitLab",
            context = "listIssues($projectId)",
            fetchPage = { page, perPage ->
                val url = "$apiUrl/projects/${projectId.encodeURLParameter()}/issues"
                rateLimit(url)
                val response = httpClient.get(url) {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    parameter("per_page", perPage)
                    parameter("page", page)
                }
                val responseText = response.checkProviderResponse("GitLab", "listIssues")
                val issues: List<GitLabIssue> = json.decodeFromString(responseText)
                issues to response
            },
        )
    }

    suspend fun getIssue(baseUrl: String, token: String, projectId: String, issueIid: Int): GitLabIssue {
        val apiUrl = getApiUrl(baseUrl)
        val url = "$apiUrl/projects/${projectId.encodeURLParameter()}/issues/$issueIid"
        rateLimit(url)
        val response = httpClient.get(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val responseText = response.checkProviderResponse("GitLab", "getIssue(#$issueIid)")
        return json.decodeFromString(GitLabIssue.serializer(), responseText)
    }

    suspend fun listWikis(baseUrl: String, token: String, projectId: String): List<GitLabWikiPage> {
        val apiUrl = getApiUrl(baseUrl)
        val url = "$apiUrl/projects/${projectId.encodeURLParameter()}/wikis"
        rateLimit(url)
        val response = httpClient.get(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val responseText = response.checkProviderResponse("GitLab", "listWikis")
        return json.decodeFromString(responseText)
    }

    suspend fun getWikiPage(baseUrl: String, token: String, projectId: String, slug: String): GitLabWikiPage {
        val apiUrl = getApiUrl(baseUrl)
        val url = "$apiUrl/projects/${projectId.encodeURLParameter()}/wikis/$slug"
        rateLimit(url)
        val response = httpClient.get(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val responseText = response.checkProviderResponse("GitLab", "getWikiPage($slug)")
        return json.decodeFromString(GitLabWikiPage.serializer(), responseText)
    }

    suspend fun getFile(baseUrl: String, token: String, projectId: String, filePath: String, ref: String?): GitLabFile {
        val apiUrl = getApiUrl(baseUrl)
        val url = "$apiUrl/projects/${projectId.encodeURLParameter()}/repository/files/${filePath.encodeURLParameter()}"
        rateLimit(url)
        val response = httpClient.get(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("ref", ref ?: "main")
        }
        val responseText = response.checkProviderResponse("GitLab", "getFile($filePath)")
        return json.decodeFromString(GitLabFile.serializer(), responseText)
    }
}

@Serializable
data class GitLabUser(
    val id: Long,
    val username: String,
    val name: String,
    val email: String? = null,
    val avatar_url: String? = null
)

@Serializable
data class GitLabProject(
    val id: Long,
    val name: String,
    val path_with_namespace: String,
    val description: String? = null,
    val visibility: String,
    val web_url: String,
    val http_url_to_repo: String,
    val ssh_url_to_repo: String,
    val default_branch: String? = "main"
)

@Serializable
data class GitLabIssue(
    val id: Long,
    val iid: Int,
    val project_id: Long,
    val title: String,
    val description: String? = null,
    val state: String,
    val web_url: String,
    val created_at: String,
    val updated_at: String
)

@Serializable
data class GitLabWikiPage(
    val slug: String,
    val title: String,
    val content: String? = null,
    val format: String = "markdown",
    val encoding: String? = null
)

@Serializable
data class GitLabFile(
    val file_name: String,
    val file_path: String,
    val size: Long,
    val encoding: String,
    val content: String,
    val ref: String,
    val blob_id: String
)
