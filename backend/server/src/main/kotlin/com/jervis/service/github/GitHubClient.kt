package com.jervis.service.github

import com.jervis.entity.connection.ConnectionDocument
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class GitHubClient(
    private val httpClient: HttpClient
) {
    private val log = KotlinLogging.logger {}
    private val json = Json { ignoreUnknownKeys = true }

    private fun requireToken(connection: ConnectionDocument): String =
        connection.bearerToken
            ?: throw IllegalArgumentException("GitHub connection requires Bearer token")

    suspend fun getUser(connection: ConnectionDocument): GitHubUser {
        val token = requireToken(connection)
        val response = httpClient.get("https://api.github.com/user") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
        }
        return json.decodeFromString(GitHubUser.serializer(), response.bodyAsText())
    }

    suspend fun listRepositories(connection: ConnectionDocument): List<GitHubRepository> {
        val token = requireToken(connection)
        val response = httpClient.get("https://api.github.com/user/repos") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            parameter("per_page", 100)
            parameter("sort", "updated")
        }
        return json.decodeFromString(response.bodyAsText())
    }

    suspend fun getRepository(connection: ConnectionDocument, owner: String, repo: String): GitHubRepository {
        val token = requireToken(connection)
        val response = httpClient.get("https://api.github.com/repos/$owner/$repo") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
        }
        return json.decodeFromString(GitHubRepository.serializer(), response.bodyAsText())
    }

    suspend fun listIssues(connection: ConnectionDocument, owner: String, repo: String): List<GitHubIssue> {
        val token = requireToken(connection)
        val response = httpClient.get("https://api.github.com/repos/$owner/$repo/issues") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            parameter("per_page", 100)
            parameter("state", "all")
        }
        return json.decodeFromString(response.bodyAsText())
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
    val updated_at: String
)
