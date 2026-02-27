package com.jervis.github.service

import com.jervis.common.http.checkProviderResponse
import com.jervis.common.http.paginateViaLinkHeader
import com.jervis.common.ratelimit.DomainRateLimiter
import com.jervis.common.ratelimit.UrlUtils
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.setBody
import io.ktor.http.*
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import mu.KotlinLogging

/**
 * Low-level GitHub API client with response validation, rate limiting, and pagination.
 */
class GitHubApiClient(
    private val httpClient: HttpClient,
    private val rateLimiter: DomainRateLimiter,
) {
    private val log = KotlinLogging.logger {}
    private val json = Json { ignoreUnknownKeys = true }

    private fun getApiUrl(baseUrl: String?): String {
        val base = baseUrl?.takeIf { it.isNotBlank() } ?: "https://api.github.com"
        return base.trimEnd('/')
    }

    private suspend fun rateLimit(url: String) {
        if (!UrlUtils.isInternalUrl(url)) {
            rateLimiter.acquire(UrlUtils.extractDomain(url))
        }
    }

    suspend fun getUser(token: String, baseUrl: String? = null): GitHubUser {
        val apiUrl = getApiUrl(baseUrl)
        val url = "$apiUrl/user"
        rateLimit(url)
        val response = httpClient.get(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
        }
        val responseText = response.checkProviderResponse("GitHub", "getUser")
        return json.decodeFromString(GitHubUser.serializer(), responseText)
    }

    suspend fun listRepositories(token: String, baseUrl: String? = null): List<GitHubRepository> {
        val apiUrl = getApiUrl(baseUrl)
        val url = "$apiUrl/user/repos?per_page=100&sort=updated"
        rateLimit(url)
        return paginateViaLinkHeader(
            httpClient = httpClient,
            initialUrl = url,
            provider = "GitHub",
            context = "listRepositories",
            requestBuilder = {
                header(HttpHeaders.Authorization, "Bearer $token")
                header(HttpHeaders.Accept, "application/vnd.github+json")
            },
            deserialize = { body -> json.decodeFromString(body) },
        )
    }

    suspend fun getRepository(token: String, owner: String, repo: String, baseUrl: String? = null): GitHubRepository {
        val apiUrl = getApiUrl(baseUrl)
        val url = "$apiUrl/repos/$owner/$repo"
        rateLimit(url)
        val response = httpClient.get(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
        }
        val responseText = response.checkProviderResponse("GitHub", "getRepository")
        return json.decodeFromString(GitHubRepository.serializer(), responseText)
    }

    suspend fun listIssues(token: String, owner: String, repo: String, baseUrl: String? = null): List<GitHubIssue> {
        val apiUrl = getApiUrl(baseUrl)
        val url = "$apiUrl/repos/$owner/$repo/issues?per_page=100&state=all"
        rateLimit(url)
        return paginateViaLinkHeader(
            httpClient = httpClient,
            initialUrl = url,
            provider = "GitHub",
            context = "listIssues($owner/$repo)",
            requestBuilder = {
                header(HttpHeaders.Authorization, "Bearer $token")
                header(HttpHeaders.Accept, "application/vnd.github+json")
            },
            deserialize = { body -> json.decodeFromString(body) },
        )
    }

    suspend fun getIssue(token: String, owner: String, repo: String, issueNumber: Int, baseUrl: String? = null): GitHubIssue {
        val apiUrl = getApiUrl(baseUrl)
        val url = "$apiUrl/repos/$owner/$repo/issues/$issueNumber"
        rateLimit(url)
        val response = httpClient.get(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
        }
        val responseText = response.checkProviderResponse("GitHub", "getIssue(#$issueNumber)")
        return json.decodeFromString(GitHubIssue.serializer(), responseText)
    }

    suspend fun getFile(token: String, owner: String, repo: String, path: String, ref: String?, baseUrl: String? = null): GitHubFile {
        val apiUrl = getApiUrl(baseUrl)
        val url = "$apiUrl/repos/$owner/$repo/contents/$path"
        rateLimit(url)
        val response = httpClient.get(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            ref?.let { parameter("ref", it) }
        }
        val responseText = response.checkProviderResponse("GitHub", "getFile($path)")
        return json.decodeFromString(GitHubFile.serializer(), responseText)
    }

    // ── Write operations ──────────────────────────────────────────────

    suspend fun createIssue(
        token: String,
        owner: String,
        repo: String,
        title: String,
        body: String? = null,
        labels: List<String> = emptyList(),
        assignee: String? = null,
        baseUrl: String? = null,
    ): GitHubIssue {
        val apiUrl = getApiUrl(baseUrl)
        val url = "$apiUrl/repos/$owner/$repo/issues"
        rateLimit(url)
        val payload = buildJsonObject {
            put("title", title)
            body?.let { put("body", it) }
            if (labels.isNotEmpty()) {
                putJsonArray("labels") { labels.forEach { add(it) } }
            }
            assignee?.let {
                putJsonArray("assignees") { add(it) }
            }
        }
        val response = httpClient.post(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        val responseText = response.checkProviderResponse("GitHub", "createIssue($owner/$repo)")
        return json.decodeFromString(GitHubIssue.serializer(), responseText)
    }

    suspend fun updateIssue(
        token: String,
        owner: String,
        repo: String,
        issueNumber: Int,
        title: String? = null,
        body: String? = null,
        state: String? = null,
        labels: List<String>? = null,
        assignee: String? = null,
        baseUrl: String? = null,
    ): GitHubIssue {
        val apiUrl = getApiUrl(baseUrl)
        val url = "$apiUrl/repos/$owner/$repo/issues/$issueNumber"
        rateLimit(url)
        val payload = buildJsonObject {
            title?.let { put("title", it) }
            body?.let { put("body", it) }
            state?.let { put("state", it) }
            assignee?.let { put("assignee", it) }
            labels?.let { list ->
                putJsonArray("labels") { list.forEach { add(it) } }
            }
        }
        val response = httpClient.patch(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        val responseText = response.checkProviderResponse("GitHub", "updateIssue(#$issueNumber)")
        return json.decodeFromString(GitHubIssue.serializer(), responseText)
    }

    suspend fun addComment(
        token: String,
        owner: String,
        repo: String,
        issueNumber: Int,
        body: String,
        baseUrl: String? = null,
    ): GitHubComment {
        val apiUrl = getApiUrl(baseUrl)
        val url = "$apiUrl/repos/$owner/$repo/issues/$issueNumber/comments"
        rateLimit(url)
        val payload = buildJsonObject {
            put("body", body)
        }
        val response = httpClient.post(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        val responseText = response.checkProviderResponse("GitHub", "addComment(#$issueNumber)")
        return json.decodeFromString(GitHubComment.serializer(), responseText)
    }
}

@Serializable
data class GitHubUser(
    val login: String,
    val id: Long,
    val name: String? = null,
    val email: String? = null,
    val avatar_url: String? = null
)

@Serializable
data class GitHubRepository(
    val id: Long,
    val name: String,
    val full_name: String,
    val description: String? = null,
    val private: Boolean,
    val html_url: String,
    val clone_url: String,
    val ssh_url: String,
    val default_branch: String = "main",
    val owner: GitHubOwner
)

@Serializable
data class GitHubOwner(
    val login: String,
    val id: Long
)

@Serializable
data class GitHubIssue(
    val id: Long,
    val number: Int,
    val title: String,
    val body: String? = null,
    val state: String,
    val html_url: String,
    val created_at: String,
    val updated_at: String,
    val repository_url: String? = null
)

@Serializable
data class GitHubFile(
    val name: String,
    val path: String,
    val size: Long,
    val content: String? = null,
    val encoding: String? = null,
    val download_url: String? = null
)

@Serializable
data class GitHubComment(
    val id: Long,
    val body: String,
    val created_at: String,
    val user: GitHubCommentUser? = null,
)

@Serializable
data class GitHubCommentUser(
    val login: String,
)
