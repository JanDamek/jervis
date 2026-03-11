package com.jervis.service.gitlab

import com.jervis.entity.connection.ConnectionDocument
import com.jervis.common.http.checkProviderResponse
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
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class GitLabClient(
    private val httpClient: HttpClient
) {
    private val log = KotlinLogging.logger {}
    private val json = Json { ignoreUnknownKeys = true }

    private fun getBaseUrl(connection: ConnectionDocument): String {
        val base = connection.baseUrl.takeIf { it.isNotBlank() } ?: "https://gitlab.com"
        return "${base.trimEnd('/')}/api/v4"
    }

    private fun requireToken(connection: ConnectionDocument): String =
        connection.bearerToken
            ?: throw IllegalArgumentException("GitLab connection requires Bearer token")

    suspend fun getUser(connection: ConnectionDocument): GitLabUser {
        val token = requireToken(connection)
        val baseUrl = getBaseUrl(connection)
        val response = httpClient.get("$baseUrl/user") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val body = response.checkProviderResponse("GitLab", "getUser")
        return json.decodeFromString(GitLabUser.serializer(), body)
    }

    suspend fun listProjects(connection: ConnectionDocument): List<GitLabProject> {
        val token = requireToken(connection)
        val baseUrl = getBaseUrl(connection)
        val response = httpClient.get("$baseUrl/projects") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("per_page", 100)
            parameter("order_by", "last_activity_at")
            parameter("membership", true)
        }
        val body = response.checkProviderResponse("GitLab", "listProjects")
        return json.decodeFromString(body)
    }

    suspend fun getProject(connection: ConnectionDocument, projectId: String): GitLabProject {
        val token = requireToken(connection)
        val baseUrl = getBaseUrl(connection)
        val response = httpClient.get("$baseUrl/projects/${projectId.encodeURLParameter()}") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val body = response.checkProviderResponse("GitLab", "getProject")
        return json.decodeFromString(GitLabProject.serializer(), body)
    }

    suspend fun listIssues(connection: ConnectionDocument, projectId: String): List<GitLabIssue> {
        val token = requireToken(connection)
        val baseUrl = getBaseUrl(connection)
        val response = httpClient.get("$baseUrl/projects/${projectId.encodeURLParameter()}/issues") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("per_page", 100)
        }
        val body = response.checkProviderResponse("GitLab", "listIssues")
        return json.decodeFromString(body)
    }

    // ── Merge Request operations ──────────────────────────────────

    suspend fun listOpenMergeRequests(
        connection: ConnectionDocument,
        projectId: String,
    ): List<GitLabMergeRequest> {
        val token = requireToken(connection)
        val baseUrl = getBaseUrl(connection)
        val response = httpClient.get("$baseUrl/projects/${projectId.encodeURLParameter()}/merge_requests") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("state", "opened")
            parameter("per_page", 50)
        }
        val body = response.checkProviderResponse("GitLab", "listOpenMergeRequests")
        return json.decodeFromString(body)
    }

    suspend fun createMergeRequest(
        connection: ConnectionDocument,
        projectId: String,
        sourceBranch: String,
        targetBranch: String,
        title: String,
        description: String? = null,
    ): GitLabMergeRequest {
        val token = requireToken(connection)
        val baseUrl = getBaseUrl(connection)
        val payload = buildJsonObject {
            put("source_branch", sourceBranch)
            put("target_branch", targetBranch)
            put("title", title)
            description?.let { put("description", it) }
            put("remove_source_branch", false)
        }
        val response = httpClient.post("$baseUrl/projects/${projectId.encodeURLParameter()}/merge_requests") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        val body = response.checkProviderResponse("GitLab", "createMergeRequest")
        return json.decodeFromString(GitLabMergeRequest.serializer(), body)
    }

    suspend fun getMergeRequest(
        connection: ConnectionDocument,
        projectId: String,
        mrIid: Int,
    ): GitLabMergeRequest {
        val token = requireToken(connection)
        val baseUrl = getBaseUrl(connection)
        val response = httpClient.get("$baseUrl/projects/${projectId.encodeURLParameter()}/merge_requests/$mrIid") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val body = response.checkProviderResponse("GitLab", "getMergeRequest(#$mrIid)")
        return json.decodeFromString(GitLabMergeRequest.serializer(), body)
    }

    suspend fun getMergeRequestDiffs(
        connection: ConnectionDocument,
        projectId: String,
        mrIid: Int,
    ): List<GitLabMergeRequestDiff> {
        val token = requireToken(connection)
        val baseUrl = getBaseUrl(connection)
        val response = httpClient.get("$baseUrl/projects/${projectId.encodeURLParameter()}/merge_requests/$mrIid/diffs") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("per_page", 100)
        }
        val body = response.checkProviderResponse("GitLab", "getMergeRequestDiffs(#$mrIid)")
        return json.decodeFromString(body)
    }

    suspend fun addMergeRequestNote(
        connection: ConnectionDocument,
        projectId: String,
        mrIid: Int,
        noteBody: String,
    ): GitLabNote {
        val token = requireToken(connection)
        val baseUrl = getBaseUrl(connection)
        val payload = buildJsonObject { put("body", noteBody) }
        val response = httpClient.post("$baseUrl/projects/${projectId.encodeURLParameter()}/merge_requests/$mrIid/notes") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        val body = response.checkProviderResponse("GitLab", "addMergeRequestNote(#$mrIid)")
        return json.decodeFromString(GitLabNote.serializer(), body)
    }

    suspend fun getMergeRequestVersions(
        connection: ConnectionDocument,
        projectId: String,
        mrIid: Int,
    ): List<GitLabMergeRequestVersion> {
        val token = requireToken(connection)
        val baseUrl = getBaseUrl(connection)
        val response = httpClient.get("$baseUrl/projects/${projectId.encodeURLParameter()}/merge_requests/$mrIid/versions") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val body = response.checkProviderResponse("GitLab", "getMergeRequestVersions(#$mrIid)")
        return json.decodeFromString(body)
    }

    suspend fun createMergeRequestDiscussion(
        connection: ConnectionDocument,
        projectId: String,
        mrIid: Int,
        body: String,
        newPath: String? = null,
        newLine: Int? = null,
        oldPath: String? = null,
        oldLine: Int? = null,
        baseSha: String? = null,
        headSha: String? = null,
        startSha: String? = null,
    ): GitLabNote {
        val token = requireToken(connection)
        val baseUrl = getBaseUrl(connection)
        val payload = buildJsonObject {
            put("body", body)
            if (newPath != null && baseSha != null && headSha != null && startSha != null) {
                put("position", buildJsonObject {
                    put("position_type", "text")
                    put("base_sha", baseSha)
                    put("head_sha", headSha)
                    put("start_sha", startSha)
                    put("new_path", newPath)
                    put("old_path", oldPath ?: newPath)
                    newLine?.let { put("new_line", it) }
                    oldLine?.let { put("old_line", it) }
                })
            }
        }
        val response = httpClient.post("$baseUrl/projects/${projectId.encodeURLParameter()}/merge_requests/$mrIid/discussions") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        val bodyText = response.checkProviderResponse("GitLab", "createMergeRequestDiscussion(#$mrIid)")
        val discussion: GitLabDiscussion = json.decodeFromString(bodyText)
        return discussion.notes.firstOrNull() ?: GitLabNote(id = 0, body = body, created_at = "")
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
    val title: String,
    val description: String? = null,
    val state: String,
    val web_url: String,
    val created_at: String,
    val updated_at: String
)

@Serializable
data class GitLabMergeRequest(
    val id: Long,
    val iid: Int,
    val title: String,
    val description: String? = null,
    val state: String,
    val source_branch: String,
    val target_branch: String,
    val web_url: String,
    val draft: Boolean = false,
    val created_at: String,
    val author: GitLabMergeRequestAuthor? = null,
)

@Serializable
data class GitLabMergeRequestAuthor(
    val username: String,
    val name: String? = null,
)

@Serializable
data class GitLabNote(
    val id: Long,
    val body: String,
    val created_at: String,
)

@Serializable
data class GitLabMergeRequestDiff(
    val old_path: String,
    val new_path: String,
    val new_file: Boolean = false,
    val renamed_file: Boolean = false,
    val deleted_file: Boolean = false,
    val diff: String = "",
)

@Serializable
data class GitLabMergeRequestVersion(
    val id: Long,
    val head_commit_sha: String,
    val base_commit_sha: String,
    val start_commit_sha: String,
    val created_at: String,
)

@Serializable
data class GitLabDiscussion(
    val id: String,
    val notes: List<GitLabNote> = emptyList(),
)
