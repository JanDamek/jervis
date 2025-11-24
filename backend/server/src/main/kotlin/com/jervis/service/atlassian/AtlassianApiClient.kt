package com.jervis.service.atlassian

import com.jervis.entity.connection.Connection
import com.jervis.entity.connection.HttpCredentials
import com.jervis.entity.jira.JiraAttachment
import com.jervis.entity.jira.JiraComment
import com.jervis.entity.jira.JiraIssueIndexDocument
import com.jervis.service.http.getWithConnection
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Atlassian API client for Jira and Confluence.
 * Fetches COMPLETE data for storage in MongoDB.
 */
@Service
class AtlassianApiClient(
    private val httpClient: HttpClient,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Get current user info (for connection testing).
     */
    suspend fun getMyself(
        connection: Connection.HttpConnection,
        credentials: HttpCredentials?,
    ): JiraMyselfDto {
        val response = httpClient.getWithConnection(
            url = "${connection.baseUrl}/rest/api/3/myself",
            connection = connection,
            credentials = credentials
        )

        return response.body<JiraMyselfDto>()
    }

    /**
     * Search Jira issues and fetch FULL details for each.
     * Returns complete JiraIssueIndexDocument ready for MongoDB.
     */
    suspend fun searchAndFetchFullIssues(
        connection: Connection.HttpConnection,
        credentials: HttpCredentials,
        clientId: ObjectId,
        jql: String,
        maxResults: Int = 100,
    ): List<JiraIssueIndexDocument> {
        // 1. Search for issues with all fields
        val searchResponse = httpClient.getWithConnection(
            url = "${connection.baseUrl}/rest/api/3/search",
            connection = connection,
            credentials = credentials
        ) {
            parameter("jql", jql)
            parameter("maxResults", maxResults)
            parameter("fields", "key,summary,description,issuetype,status,priority,assignee,reporter,labels,created,updated,comment,attachment")
            parameter("expand", "renderedFields")
        }

        val searchResult = searchResponse.body<JiraSearchResponseDto>()

        // 2. Convert to full documents
        return searchResult.issues.mapNotNull { issueDto ->
            try {
                issueDto.toDocument(connection.id, clientId)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse issue ${issueDto.key}" }
                null
            }
        }
    }

    @Serializable
    private data class JiraSearchResponseDto(
        val issues: List<JiraIssueDto>,
        val total: Int,
    )

    @Serializable
    private data class JiraIssueDto(
        val id: String,
        val key: String,
        val fields: JiraFieldsDto,
    ) {
        fun toDocument(connectionId: ObjectId, clientId: ObjectId) = JiraIssueIndexDocument(
            connectionId = connectionId,
            clientId = clientId,
            issueKey = key,
            projectKey = key.substringBefore('-'),
            summary = fields.summary ?: "",
            description = fields.description,
            issueType = fields.issuetype?.name ?: "Unknown",
            status = fields.status?.name ?: "Unknown",
            priority = fields.priority?.name,
            assignee = fields.assignee?.accountId,
            reporter = fields.reporter?.accountId,
            labels = fields.labels ?: emptyList(),
            comments = fields.comment?.comments?.map { it.toComment() } ?: emptyList(),
            attachments = fields.attachment?.map { it.toAttachment() } ?: emptyList(),
            linkedIssues = emptyList(), // TODO: Parse from fields if needed
            createdAt = Instant.parse(fields.created),
            jiraUpdatedAt = Instant.parse(fields.updated),
            state = "NEW",
        )
    }

    @Serializable
    private data class JiraFieldsDto(
        val summary: String? = null,
        val description: String? = null,
        val updated: String,
        val created: String,
        val issuetype: JiraIssueTypeDto? = null,
        val status: JiraStatusDto? = null,
        val priority: JiraPriorityDto? = null,
        val assignee: JiraUserDto? = null,
        val reporter: JiraUserDto? = null,
        val labels: List<String>? = null,
        val comment: JiraCommentsDto? = null,
        val attachment: List<JiraAttachmentDto>? = null,
    )

    @Serializable
    private data class JiraIssueTypeDto(
        val name: String,
    )

    @Serializable
    private data class JiraStatusDto(
        val name: String,
    )

    @Serializable
    private data class JiraPriorityDto(
        val name: String,
    )

    @Serializable
    private data class JiraUserDto(
        val accountId: String,
        val displayName: String? = null,
    )

    @Serializable
    private data class JiraCommentsDto(
        val comments: List<JiraCommentDto>,
    )

    @Serializable
    private data class JiraCommentDto(
        val id: String,
        val author: JiraUserDto,
        val body: String? = null, // ADF format
        val created: String,
        val updated: String,
    ) {
        fun toComment() = JiraComment(
            id = id,
            author = author.displayName ?: author.accountId,
            body = body ?: "",
            created = Instant.parse(created),
            updated = Instant.parse(updated),
        )
    }

    @Serializable
    private data class JiraAttachmentDto(
        val id: String,
        val filename: String,
        val mimeType: String,
        val size: Long,
        val content: String, // Download URL
        val created: String,
    ) {
        fun toAttachment() = JiraAttachment(
            id = id,
            filename = filename,
            mimeType = mimeType,
            size = size,
            downloadUrl = content,
            created = Instant.parse(created),
        )
    }

    @Serializable
    data class JiraMyselfDto(
        val accountId: String,
        val displayName: String,
        val emailAddress: String? = null,
        val active: Boolean = true,
    )
}
