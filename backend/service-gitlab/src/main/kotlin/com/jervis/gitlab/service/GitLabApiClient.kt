package com.jervis.gitlab.service

import com.jervis.common.http.checkProviderResponse
import com.jervis.common.http.paginateViaOffset
import com.jervis.common.ratelimit.DomainRateLimiter
import com.jervis.common.ratelimit.UrlUtils
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.setBody
import io.ktor.client.statement.*
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

    // ── Issue write operations ────────────────────────────────────────

    suspend fun createIssue(
        baseUrl: String,
        token: String,
        projectId: String,
        title: String,
        description: String? = null,
        labels: List<String> = emptyList(),
        assigneeId: String? = null,
    ): GitLabIssue {
        val apiUrl = getApiUrl(baseUrl)
        val url = "$apiUrl/projects/${projectId.encodeURLParameter()}/issues"
        rateLimit(url)
        val payload = buildJsonObject {
            put("title", title)
            description?.let { put("description", it) }
            if (labels.isNotEmpty()) put("labels", labels.joinToString(","))
            assigneeId?.let { put("assignee_ids", it) }
        }
        val response = httpClient.post(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        val responseText = response.checkProviderResponse("GitLab", "createIssue($projectId)")
        return json.decodeFromString(GitLabIssue.serializer(), responseText)
    }

    suspend fun updateIssue(
        baseUrl: String,
        token: String,
        projectId: String,
        issueIid: Int,
        title: String? = null,
        description: String? = null,
        stateEvent: String? = null,
        labels: List<String>? = null,
        assigneeId: String? = null,
    ): GitLabIssue {
        val apiUrl = getApiUrl(baseUrl)
        val url = "$apiUrl/projects/${projectId.encodeURLParameter()}/issues/$issueIid"
        rateLimit(url)
        val payload = buildJsonObject {
            title?.let { put("title", it) }
            description?.let { put("description", it) }
            stateEvent?.let { put("state_event", it) }
            labels?.let { put("labels", it.joinToString(",")) }
            assigneeId?.let { put("assignee_ids", it) }
        }
        val response = httpClient.put(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        val responseText = response.checkProviderResponse("GitLab", "updateIssue(#$issueIid)")
        return json.decodeFromString(GitLabIssue.serializer(), responseText)
    }

    suspend fun addIssueNote(
        baseUrl: String,
        token: String,
        projectId: String,
        issueIid: Int,
        body: String,
    ): GitLabNote {
        val apiUrl = getApiUrl(baseUrl)
        val url = "$apiUrl/projects/${projectId.encodeURLParameter()}/issues/$issueIid/notes"
        rateLimit(url)
        val payload = buildJsonObject {
            put("body", body)
        }
        val response = httpClient.post(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        val responseText = response.checkProviderResponse("GitLab", "addIssueNote(#$issueIid)")
        return json.decodeFromString(GitLabNote.serializer(), responseText)
    }

    // ── Merge Request operations ────────────────────────────────────────

    suspend fun createMergeRequest(
        baseUrl: String,
        token: String,
        projectId: String,
        sourceBranch: String,
        targetBranch: String,
        title: String,
        description: String? = null,
    ): GitLabMergeRequest {
        val apiUrl = getApiUrl(baseUrl)
        val url = "$apiUrl/projects/${projectId.encodeURLParameter()}/merge_requests"
        rateLimit(url)
        val payload = buildJsonObject {
            put("source_branch", sourceBranch)
            put("target_branch", targetBranch)
            put("title", title)
            description?.let { put("description", it) }
            put("remove_source_branch", false)
        }
        val response = httpClient.post(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        val responseText = response.checkProviderResponse("GitLab", "createMergeRequest($projectId)")
        return json.decodeFromString(GitLabMergeRequest.serializer(), responseText)
    }

    suspend fun getMergeRequest(
        baseUrl: String,
        token: String,
        projectId: String,
        mrIid: Int,
    ): GitLabMergeRequest {
        val apiUrl = getApiUrl(baseUrl)
        val url = "$apiUrl/projects/${projectId.encodeURLParameter()}/merge_requests/$mrIid"
        rateLimit(url)
        val response = httpClient.get(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val responseText = response.checkProviderResponse("GitLab", "getMergeRequest(#$mrIid)")
        return json.decodeFromString(GitLabMergeRequest.serializer(), responseText)
    }

    suspend fun listMergeRequests(
        baseUrl: String,
        token: String,
        projectId: String,
        state: String = "opened",
        sourceBranch: String? = null,
    ): List<GitLabMergeRequest> {
        val apiUrl = getApiUrl(baseUrl)
        val url = "$apiUrl/projects/${projectId.encodeURLParameter()}/merge_requests"
        rateLimit(url)
        val response = httpClient.get(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("state", state)
            parameter("per_page", 100)
            sourceBranch?.let { parameter("source_branch", it) }
        }
        val responseText = response.checkProviderResponse("GitLab", "listMergeRequests($projectId)")
        return json.decodeFromString(responseText)
    }

    suspend fun addMergeRequestNote(
        baseUrl: String,
        token: String,
        projectId: String,
        mrIid: Int,
        body: String,
    ): GitLabNote {
        val apiUrl = getApiUrl(baseUrl)
        val url = "$apiUrl/projects/${projectId.encodeURLParameter()}/merge_requests/$mrIid/notes"
        rateLimit(url)
        val payload = buildJsonObject {
            put("body", body)
        }
        val response = httpClient.post(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        val responseText = response.checkProviderResponse("GitLab", "addMergeRequestNote(#$mrIid)")
        return json.decodeFromString(GitLabNote.serializer(), responseText)
    }

    suspend fun getMergeRequestDiffs(
        baseUrl: String,
        token: String,
        projectId: String,
        mrIid: Int,
    ): List<GitLabMergeRequestDiff> {
        val apiUrl = getApiUrl(baseUrl)
        val url = "$apiUrl/projects/${projectId.encodeURLParameter()}/merge_requests/$mrIid/diffs"
        rateLimit(url)
        val response = httpClient.get(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("per_page", 100)
        }
        val responseText = response.checkProviderResponse("GitLab", "getMergeRequestDiffs(#$mrIid)")
        return json.decodeFromString(responseText)
    }

    // ── Wiki write operations ─────────────────────────────────────────

    suspend fun createWikiPage(
        baseUrl: String,
        token: String,
        projectId: String,
        title: String,
        content: String,
    ): GitLabWikiPage {
        val apiUrl = getApiUrl(baseUrl)
        val url = "$apiUrl/projects/${projectId.encodeURLParameter()}/wikis"
        rateLimit(url)
        val payload = buildJsonObject {
            put("title", title)
            put("content", content)
            put("format", "markdown")
        }
        val response = httpClient.post(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        val responseText = response.checkProviderResponse("GitLab", "createWikiPage($title)")
        return json.decodeFromString(GitLabWikiPage.serializer(), responseText)
    }

    suspend fun updateWikiPage(
        baseUrl: String,
        token: String,
        projectId: String,
        slug: String,
        title: String,
        content: String,
    ): GitLabWikiPage {
        val apiUrl = getApiUrl(baseUrl)
        val url = "$apiUrl/projects/${projectId.encodeURLParameter()}/wikis/$slug"
        rateLimit(url)
        val payload = buildJsonObject {
            put("title", title)
            put("content", content)
            put("format", "markdown")
        }
        val response = httpClient.put(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        val responseText = response.checkProviderResponse("GitLab", "updateWikiPage($slug)")
        return json.decodeFromString(GitLabWikiPage.serializer(), responseText)
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
data class GitLabNote(
    val id: Long,
    val body: String,
    val created_at: String,
    val author: GitLabNoteAuthor? = null,
)

@Serializable
data class GitLabNoteAuthor(
    val username: String,
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

@Serializable
data class GitLabMergeRequest(
    val id: Long,
    val iid: Int,
    val project_id: Long,
    val title: String,
    val description: String? = null,
    val state: String,
    val source_branch: String,
    val target_branch: String,
    val web_url: String,
    val draft: Boolean = false,
    val created_at: String,
)

@Serializable
data class GitLabMergeRequestDiff(
    val old_path: String,
    val new_path: String,
    val a_mode: String? = null,
    val b_mode: String? = null,
    val new_file: Boolean = false,
    val renamed_file: Boolean = false,
    val deleted_file: Boolean = false,
    val diff: String = "",
)
