package com.jervis.service.gitlab

import com.jervis.entity.connection.ConnectionDocument
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class GitLabClient(
    private val httpClient: HttpClient
) {
    private val log = KotlinLogging.logger {}
    private val json = Json { ignoreUnknownKeys = true }

    private fun getBaseUrl(connection: ConnectionDocument): String {
        return connection.baseUrl.takeIf { it.isNotBlank() } ?: "https://gitlab.com/api/v4"
    }

    /**
     * Get authenticated user info
     */
    suspend fun getUser(connection: ConnectionDocument): GitLabUser {
        val token = (connection.credentials as? ConnectionDocument.HttpCredentials.Bearer)?.token
            ?: throw IllegalArgumentException("GitLab connection requires Bearer token")

        val baseUrl = getBaseUrl(connection)
        val response = httpClient.get("$baseUrl/user") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        val responseText = response.bodyAsText()
        return json.decodeFromString(GitLabUser.serializer(), responseText)
    }

    /**
     * List projects for authenticated user
     */
    suspend fun listProjects(connection: ConnectionDocument): List<GitLabProject> {
        val token = (connection.credentials as? ConnectionDocument.HttpCredentials.Bearer)?.token
            ?: throw IllegalArgumentException("GitLab connection requires Bearer token")

        val baseUrl = getBaseUrl(connection)
        val response = httpClient.get("$baseUrl/projects") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("per_page", 100)
            parameter("order_by", "last_activity_at")
            parameter("membership", true)
        }

        val responseText = response.bodyAsText()
        return json.decodeFromString(responseText)
    }

    /**
     * Get project details
     */
    suspend fun getProject(connection: ConnectionDocument, projectId: String): GitLabProject {
        val token = (connection.credentials as? ConnectionDocument.HttpCredentials.Bearer)?.token
            ?: throw IllegalArgumentException("GitLab connection requires Bearer token")

        val baseUrl = getBaseUrl(connection)
        val response = httpClient.get("$baseUrl/projects/${projectId.encodeURLParameter()}") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        val responseText = response.bodyAsText()
        return json.decodeFromString(GitLabProject.serializer(), responseText)
    }

    /**
     * List issues for a project
     */
    suspend fun listIssues(connection: ConnectionDocument, projectId: String): List<GitLabIssue> {
        val token = (connection.credentials as? ConnectionDocument.HttpCredentials.Bearer)?.token
            ?: throw IllegalArgumentException("GitLab connection requires Bearer token")

        val baseUrl = getBaseUrl(connection)
        val response = httpClient.get("$baseUrl/projects/${projectId.encodeURLParameter()}/issues") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("per_page", 100)
        }

        val responseText = response.bodyAsText()
        return json.decodeFromString(responseText)
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
