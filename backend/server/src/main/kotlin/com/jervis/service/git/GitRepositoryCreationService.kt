package com.jervis.service.git

import com.jervis.common.types.ClientId
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ProviderEnum
import com.jervis.service.connection.ConnectionService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Creates git repositories on GitHub / GitLab via their REST APIs.
 *
 * Uses the client's REPOSITORY connection to find the bearer token
 * and base URL, then calls the provider-specific endpoint:
 * - GitHub:  POST /user/repos
 * - GitLab:  POST /api/v4/projects
 *
 * Returns the clone URL of the created repository.
 */
@Service
class GitRepositoryCreationService(
    private val connectionService: ConnectionService,
) {

    /**
     * Create a new repository for the given client.
     *
     * @param clientId       Client owning the connection
     * @param connectionId   Specific connection to use (null = auto-detect)
     * @param name           Repository name
     * @param description    Optional description
     * @param isPrivate      Private repository flag (default true)
     * @return [GitRepoCreationResult] with clone URL and repo details
     */
    suspend fun createRepository(
        clientId: ClientId,
        connectionId: String? = null,
        name: String,
        description: String? = null,
        isPrivate: Boolean = true,
    ): GitRepoCreationResult {
        val conn = resolveConnection(clientId, connectionId)

        return when (conn.provider) {
            ProviderEnum.GITHUB -> createGitHubRepo(conn, name, description, isPrivate)
            ProviderEnum.GITLAB -> createGitLabRepo(conn, name, description, isPrivate)
            else -> throw IllegalArgumentException(
                "Unsupported provider for repo creation: ${conn.provider}. " +
                    "Supported: GITHUB, GITLAB",
            )
        }
    }

    private suspend fun resolveConnection(
        clientId: ClientId,
        connectionId: String?,
    ): ConnectionDocument {
        if (connectionId != null) {
            return connectionService.findById(
                com.jervis.common.types.ConnectionId(org.bson.types.ObjectId(connectionId)),
            ) ?: throw IllegalArgumentException("Connection $connectionId not found")
        }

        // Auto-detect: find a valid REPOSITORY connection for the client
        return connectionService.findAllValid()
            .filter { it.availableCapabilities.contains(ConnectionCapability.REPOSITORY) }
            .firstOrNull()
            ?: throw IllegalArgumentException(
                "No valid REPOSITORY connection found for client $clientId",
            )
    }

    private suspend fun createGitHubRepo(
        conn: ConnectionDocument,
        name: String,
        description: String?,
        isPrivate: Boolean,
    ): GitRepoCreationResult {
        val baseUrl = conn.baseUrl.ifBlank { "https://api.github.com" }
        val token = conn.bearerToken
            ?: throw IllegalStateException("GitHub connection missing bearer token")

        val body = buildMap<String, Any?> {
            put("name", name)
            if (description != null) put("description", description)
            put("private", isPrivate)
            put("auto_init", true)
        }

        val client = HttpClient(CIO)
        try {
            val resp = client.post("$baseUrl/user/repos") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                header("Accept", "application/vnd.github+json")
                setBody(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(),
                    kotlinx.serialization.json.JsonObject(body.mapValues { (_, v) ->
                        when (v) {
                            is Boolean -> kotlinx.serialization.json.JsonPrimitive(v)
                            is String -> kotlinx.serialization.json.JsonPrimitive(v)
                            else -> kotlinx.serialization.json.JsonPrimitive(v?.toString())
                        }
                    }),
                ))
            }

            if (resp.status.value !in 200..299) {
                throw RuntimeException("GitHub API error ${resp.status.value}: ${resp.bodyAsText()}")
            }

            val result = json.decodeFromString<JsonObject>(resp.bodyAsText())
            return GitRepoCreationResult(
                cloneUrl = result["clone_url"]?.jsonPrimitive?.content
                    ?: result["html_url"]?.jsonPrimitive?.content ?: "",
                htmlUrl = result["html_url"]?.jsonPrimitive?.content ?: "",
                name = result["name"]?.jsonPrimitive?.content ?: name,
                fullName = result["full_name"]?.jsonPrimitive?.content ?: name,
                provider = "GITHUB",
            )
        } finally {
            client.close()
        }
    }

    private suspend fun createGitLabRepo(
        conn: ConnectionDocument,
        name: String,
        description: String?,
        isPrivate: Boolean,
    ): GitRepoCreationResult {
        val baseUrl = conn.baseUrl.ifBlank { "https://gitlab.com" }
        val token = conn.bearerToken
            ?: throw IllegalStateException("GitLab connection missing bearer token")

        val visibility = if (isPrivate) "private" else "public"

        val body = buildMap<String, Any?> {
            put("name", name)
            if (description != null) put("description", description)
            put("visibility", visibility)
            put("initialize_with_readme", true)
        }

        val client = HttpClient(CIO)
        try {
            val resp = client.post("$baseUrl/api/v4/projects") {
                contentType(ContentType.Application.Json)
                header("PRIVATE-TOKEN", token)
                setBody(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(),
                    kotlinx.serialization.json.JsonObject(body.mapValues { (_, v) ->
                        when (v) {
                            is Boolean -> kotlinx.serialization.json.JsonPrimitive(v)
                            is String -> kotlinx.serialization.json.JsonPrimitive(v)
                            else -> kotlinx.serialization.json.JsonPrimitive(v?.toString())
                        }
                    }),
                ))
            }

            if (resp.status.value !in 200..299) {
                throw RuntimeException("GitLab API error ${resp.status.value}: ${resp.bodyAsText()}")
            }

            val result = json.decodeFromString<JsonObject>(resp.bodyAsText())
            return GitRepoCreationResult(
                cloneUrl = result["http_url_to_repo"]?.jsonPrimitive?.content ?: "",
                htmlUrl = result["web_url"]?.jsonPrimitive?.content ?: "",
                name = result["name"]?.jsonPrimitive?.content ?: name,
                fullName = result["path_with_namespace"]?.jsonPrimitive?.content ?: name,
                provider = "GITLAB",
            )
        } finally {
            client.close()
        }
    }
}

@Serializable
data class GitRepoCreationResult(
    val cloneUrl: String,
    val htmlUrl: String,
    val name: String,
    val fullName: String,
    val provider: String,
)
