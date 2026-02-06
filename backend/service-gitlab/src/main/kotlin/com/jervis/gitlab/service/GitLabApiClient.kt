package com.jervis.gitlab.service

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging

/**
 * Low-level GitLab API client
 */
class GitLabApiClient(
    private val httpClient: HttpClient
) {
    private val log = KotlinLogging.logger {}
    private val json = Json { ignoreUnknownKeys = true }

    private fun getApiUrl(baseUrl: String): String {
        val base = baseUrl.takeIf { it.isNotBlank() } ?: "https://gitlab.com"
        return "${base.trimEnd('/')}/api/v4"
    }

    private suspend fun checkResponse(response: HttpResponse, context: String): String {
        val responseText = response.bodyAsText()
        if (response.status.value !in 200..299) {
            log.error { "GitLab API error ($context): status=${response.status.value}, body=$responseText" }
            throw RuntimeException("GitLab API error ($context): ${response.status.value}")
        }
        return responseText
    }

    suspend fun getUser(baseUrl: String, token: String): GitLabUser {
        val apiUrl = getApiUrl(baseUrl)
        val response = httpClient.get("$apiUrl/user") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val responseText = checkResponse(response, "getUser")
        return json.decodeFromString(GitLabUser.serializer(), responseText)
    }

    suspend fun listProjects(baseUrl: String, token: String): List<GitLabProject> {
        val apiUrl = getApiUrl(baseUrl)
        val response = httpClient.get("$apiUrl/projects") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("per_page", 100)
            parameter("order_by", "last_activity_at")
            parameter("membership", true)
        }
        val responseText = checkResponse(response, "listProjects")
        return json.decodeFromString(responseText)
    }

    suspend fun getProject(baseUrl: String, token: String, projectId: String): GitLabProject {
        val apiUrl = getApiUrl(baseUrl)
        val response = httpClient.get("$apiUrl/projects/${projectId.encodeURLParameter()}") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val responseText = checkResponse(response, "getProject")
        return json.decodeFromString(GitLabProject.serializer(), responseText)
    }

    suspend fun listIssues(baseUrl: String, token: String, projectId: String): List<GitLabIssue> {
        val apiUrl = getApiUrl(baseUrl)
        val response = httpClient.get("$apiUrl/projects/${projectId.encodeURLParameter()}/issues") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("per_page", 100)
        }
        val responseText = checkResponse(response, "listIssues")
        return json.decodeFromString(responseText)
    }

    suspend fun getIssue(baseUrl: String, token: String, projectId: String, issueIid: Int): GitLabIssue {
        val apiUrl = getApiUrl(baseUrl)
        val response = httpClient.get("$apiUrl/projects/${projectId.encodeURLParameter()}/issues/$issueIid") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val responseText = checkResponse(response, "getIssue")
        return json.decodeFromString(GitLabIssue.serializer(), responseText)
    }

    suspend fun listWikis(baseUrl: String, token: String, projectId: String): List<GitLabWikiPage> {
        val apiUrl = getApiUrl(baseUrl)
        val response = httpClient.get("$apiUrl/projects/${projectId.encodeURLParameter()}/wikis") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val responseText = checkResponse(response, "listWikis")
        return json.decodeFromString(responseText)
    }

    suspend fun getWikiPage(baseUrl: String, token: String, projectId: String, slug: String): GitLabWikiPage {
        val apiUrl = getApiUrl(baseUrl)
        val response = httpClient.get("$apiUrl/projects/${projectId.encodeURLParameter()}/wikis/$slug") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val responseText = checkResponse(response, "getWikiPage")
        return json.decodeFromString(GitLabWikiPage.serializer(), responseText)
    }

    suspend fun getFile(baseUrl: String, token: String, projectId: String, filePath: String, ref: String?): GitLabFile {
        val apiUrl = getApiUrl(baseUrl)
        val response = httpClient.get("$apiUrl/projects/${projectId.encodeURLParameter()}/repository/files/${filePath.encodeURLParameter()}") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("ref", ref ?: "main")
        }
        val responseText = checkResponse(response, "getFile")
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
