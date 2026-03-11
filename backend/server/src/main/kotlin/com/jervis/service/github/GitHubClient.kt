package com.jervis.service.github

import com.jervis.entity.connection.ConnectionDocument
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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

    // --- PR read operations ---

    suspend fun listOpenPullRequests(
        connection: ConnectionDocument,
        owner: String,
        repo: String,
    ): List<GitHubPullRequest> {
        val token = requireToken(connection)
        val response = httpClient.get("https://api.github.com/repos/$owner/$repo/pulls") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            parameter("state", "open")
            parameter("per_page", 50)
        }
        return json.decodeFromString(response.bodyAsText())
    }

    // --- PR write operations ---

    suspend fun createPullRequest(
        connection: ConnectionDocument,
        owner: String,
        repo: String,
        title: String,
        body: String?,
        head: String,
        base: String,
        draft: Boolean = false,
    ): GitHubPullRequest {
        val token = requireToken(connection)
        val requestBody = buildJsonObject {
            put("title", title)
            body?.let { put("body", it) }
            put("head", head)
            put("base", base)
            put("draft", draft)
        }.toString()
        val response = httpClient.post("https://api.github.com/repos/$owner/$repo/pulls") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        if (!response.status.isSuccess()) {
            error("GitHub PR create failed: ${response.status} — ${response.bodyAsText()}")
        }
        return json.decodeFromString(GitHubPullRequest.serializer(), response.bodyAsText())
    }

    suspend fun commentOnPullRequest(
        connection: ConnectionDocument,
        owner: String,
        repo: String,
        prNumber: Int,
        body: String,
    ): GitHubComment {
        val token = requireToken(connection)
        val requestBody = buildJsonObject { put("body", body) }.toString()
        val response = httpClient.post("https://api.github.com/repos/$owner/$repo/issues/$prNumber/comments") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        if (!response.status.isSuccess()) {
            error("GitHub PR comment failed: ${response.status} — ${response.bodyAsText()}")
        }
        return json.decodeFromString(GitHubComment.serializer(), response.bodyAsText())
    }

    suspend fun createPullRequestReview(
        connection: ConnectionDocument,
        owner: String,
        repo: String,
        prNumber: Int,
        body: String,
        event: String = "COMMENT",
        comments: List<GitHubReviewComment> = emptyList(),
        commitId: String? = null,
    ): GitHubReviewResult {
        val token = requireToken(connection)
        val requestBody = buildJsonObject {
            put("body", body)
            put("event", event)
            commitId?.let { put("commit_id", it) }
            if (comments.isNotEmpty()) {
                putJsonArray("comments") {
                    for (c in comments) {
                        add(buildJsonObject {
                            put("path", c.path)
                            put("line", c.line)
                            put("side", "RIGHT")
                            put("body", c.body)
                        })
                    }
                }
            }
        }.toString()
        val response = httpClient.post("https://api.github.com/repos/$owner/$repo/pulls/$prNumber/reviews") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        if (!response.status.isSuccess()) {
            error("GitHub PR review failed: ${response.status} — ${response.bodyAsText()}")
        }
        return json.decodeFromString(GitHubReviewResult.serializer(), response.bodyAsText())
    }

    suspend fun getPullRequestFiles(
        connection: ConnectionDocument,
        owner: String,
        repo: String,
        prNumber: Int,
    ): List<GitHubPullRequestFile> {
        val token = requireToken(connection)
        val response = httpClient.get("https://api.github.com/repos/$owner/$repo/pulls/$prNumber/files") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            parameter("per_page", 100)
        }
        if (!response.status.isSuccess()) {
            error("GitHub PR files failed: ${response.status} — ${response.bodyAsText()}")
        }
        return json.decodeFromString(response.bodyAsText())
    }

    suspend fun mergePullRequest(
        connection: ConnectionDocument,
        owner: String,
        repo: String,
        prNumber: Int,
        commitMessage: String? = null,
        mergeMethod: String = "merge",
    ): GitHubMergeResult {
        val token = requireToken(connection)
        val requestBody = buildJsonObject {
            commitMessage?.let { put("commit_message", it) }
            put("merge_method", mergeMethod)
        }.toString()
        val response = httpClient.put("https://api.github.com/repos/$owner/$repo/pulls/$prNumber/merge") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        if (!response.status.isSuccess()) {
            error("GitHub PR merge failed: ${response.status} — ${response.bodyAsText()}")
        }
        return json.decodeFromString(GitHubMergeResult.serializer(), response.bodyAsText())
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

@Serializable
data class GitHubPullRequest(
    val id: Long,
    val number: Int,
    val title: String,
    val body: String? = null,
    val state: String,
    val html_url: String,
    val head: GitHubRef,
    val base: GitHubRef,
    val draft: Boolean = false,
    val merged: Boolean = false,
    val user: GitHubPullRequestUser? = null,
)

@Serializable
data class GitHubPullRequestUser(
    val login: String,
)

@Serializable
data class GitHubRef(val ref: String, val sha: String)

@Serializable
data class GitHubComment(val id: Long, val body: String, val html_url: String)

@Serializable
data class GitHubMergeResult(val sha: String? = null, val merged: Boolean, val message: String)

@Serializable
data class GitHubPullRequestFile(
    val sha: String? = null,
    val filename: String,
    val status: String,
    val additions: Int = 0,
    val deletions: Int = 0,
    val changes: Int = 0,
    val patch: String? = null,
)

@Serializable
data class GitHubReviewComment(
    val path: String,
    val line: Int,
    val body: String,
)

@Serializable
data class GitHubReviewResult(
    val id: Long,
    val state: String,
    val body: String? = null,
    val html_url: String? = null,
)
