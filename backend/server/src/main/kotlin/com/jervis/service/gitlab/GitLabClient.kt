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
)

@Serializable
data class GitLabNote(
    val id: Long,
    val body: String,
    val created_at: String,
)
