package com.jervis.github.service

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging

/**
 * Low-level GitHub API client
 */
class GitHubApiClient(
    private val httpClient: HttpClient
) {
    private val log = KotlinLogging.logger {}
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getUser(token: String): GitHubUser {
        val response = httpClient.get("https://api.github.com/user") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
        }
        val responseText = response.bodyAsText()
        return json.decodeFromString(GitHubUser.serializer(), responseText)
    }

    suspend fun listRepositories(token: String): List<GitHubRepository> {
        val response = httpClient.get("https://api.github.com/user/repos") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            parameter("per_page", 100)
            parameter("sort", "updated")
        }
        val responseText = response.bodyAsText()
        return json.decodeFromString(responseText)
    }

    suspend fun getRepository(token: String, owner: String, repo: String): GitHubRepository {
        val response = httpClient.get("https://api.github.com/repos/$owner/$repo") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
        }
        val responseText = response.bodyAsText()
        return json.decodeFromString(GitHubRepository.serializer(), responseText)
    }

    suspend fun listIssues(token: String, owner: String, repo: String): List<GitHubIssue> {
        val response = httpClient.get("https://api.github.com/repos/$owner/$repo/issues") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            parameter("per_page", 100)
            parameter("state", "all")
        }
        val responseText = response.bodyAsText()
        return json.decodeFromString(responseText)
    }

    suspend fun getFile(token: String, owner: String, repo: String, path: String, ref: String?): GitHubFile {
        val response = httpClient.get("https://api.github.com/repos/$owner/$repo/contents/$path") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            ref?.let { parameter("ref", it) }
        }
        val responseText = response.bodyAsText()
        return json.decodeFromString(GitHubFile.serializer(), responseText)
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
