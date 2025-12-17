package com.jervis.common.dto.atlassian

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

// Custom serializer for java.time.Instant
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Instant,
    ) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

// ============= Authentication & Common =============

@Serializable
data class AtlassianMyselfRequest(
    val baseUrl: String,
    val authType: String,
    val basicUsername: String? = null,
    val basicPassword: String? = null,
    val bearerToken: String? = null,
)

@Serializable
data class AtlassianUserDto(
    val accountId: String? = null,
    val emailAddress: String? = null,
    val displayName: String? = null,
)

// ============= Jira =============

@Serializable
data class JiraSearchRequest(
    val baseUrl: String,
    val authType: String,
    val basicUsername: String? = null,
    val basicPassword: String? = null,
    val bearerToken: String? = null,
    val jql: String,
    val maxResults: Int = 1000,
    val startAt: Int = 0,
)

@Serializable
data class JiraSearchResponse(
    val total: Int,
    val startAt: Int,
    val maxResults: Int,
    val issues: List<JiraIssueSummary>,
)

@Serializable
data class JiraIssueSummary(
    val key: String,
    val id: String,
    val self: String?, // Issue URL
    val fields: JiraIssueFields,
)

@Serializable
data class JiraIssueFields(
    val summary: String?,
    val description: kotlinx.serialization.json.JsonElement? = null, // Can be string or ADF object
    val updated: String?, // ISO timestamp
    val created: String?,
    val status: JiraStatus?,
    val priority: JiraPriority?,
    val assignee: JiraUser?,
    val reporter: JiraUser?,
    val issueType: JiraIssueType?,
    val project: JiraProject?,
    val labels: List<String>?,
    val components: List<JiraComponent>?,
    val fixVersions: List<JiraVersion>?,
    val parent: JiraIssueRef?, // Parent issue (for subtasks)
    val subtasks: List<JiraIssueRef>?,
    val attachments: List<JiraAttachment>? = null,
)

@Serializable
data class JiraStatus(
    val name: String,
    val id: String?,
)

@Serializable
data class JiraPriority(
    val name: String,
    val id: String?,
)

@Serializable
data class JiraUser(
    val accountId: String,
    val displayName: String?,
    val emailAddress: String?,
)

@Serializable
data class JiraIssueType(
    val id: String,
    val name: String,
    val description: String?,
    val subtask: Boolean = false,
)

@Serializable
data class JiraProject(
    val id: String,
    val key: String,
    val name: String,
)

@Serializable
data class JiraComponent(
    val id: String,
    val name: String,
    val description: String?,
)

@Serializable
data class JiraVersion(
    val id: String,
    val name: String,
    val released: Boolean = false,
    val releaseDate: String?,
)

@Serializable
data class JiraIssueRef(
    val id: String,
    val key: String,
    val self: String?,
)

@Serializable
data class JiraIssueRequest(
    val baseUrl: String,
    val authType: String,
    val basicUsername: String? = null,
    val basicPassword: String? = null,
    val bearerToken: String? = null,
    val issueKey: String,
)

@Serializable
data class JiraIssueResponse(
    val key: String,
    val id: String,
    val fields: JiraIssueFields,
    val changelog: JiraChangelogResponse? = null,
    val comments: List<JiraComment>? = null,
    val attachments: List<JiraAttachment>? = null,
    val renderedDescription: String? = null, // HTML-rendered description from renderedFields
)

@Serializable
data class JiraChangelogResponse(
    val histories: List<JiraChangelog> = emptyList(),
)

@Serializable
data class JiraChangelog(
    val id: String, // Unique changelog ID from Jira
    val created: String?, // ISO timestamp
    val author: JiraUser?,
    val items: List<JiraChangelogItem> = emptyList(),
)

@Serializable
data class JiraChangelogItem(
    val field: String?,
    val fieldType: String?,
    val from: String?,
    val fromString: String?,
    val to: String?,
    val toString: String?,
)

@Serializable
data class JiraComment(
    val id: String,
    val body: kotlinx.serialization.json.JsonElement? = null, // String or ADF
    val author: JiraUser?,
    val created: String?,
    val updated: String?,
)

@Serializable
data class JiraAttachment(
    val id: String,
    val filename: String,
    val mimeType: String?,
    val size: Long?,
    val content: String?, // URL
)

@Serializable
data class JiraAttachmentDownloadRequest(
    val baseUrl: String,
    val authType: String,
    val basicUsername: String? = null,
    val basicPassword: String? = null,
    val bearerToken: String? = null,
    val attachmentUrl: String, // Full URL to attachment content
)

// ============= Confluence =============

@Serializable
data class ConfluenceSearchRequest(
    val baseUrl: String,
    val authType: String,
    val basicUsername: String? = null,
    val basicPassword: String? = null,
    val bearerToken: String? = null,
    val spaceKey: String? = null,
    val cql: String? = null,
    @Serializable(with = InstantSerializer::class)
    val lastModifiedSince: Instant? = null,
    val maxResults: Int = 1000,
    val startAt: Int = 0,
)

@Serializable
data class ConfluenceSearchResponse(
    val total: Int,
    val startAt: Int,
    val maxResults: Int,
    val pages: List<ConfluencePageSummary>,
)

@Serializable
data class ConfluencePageSummary(
    val id: String,
    val title: String,
    val type: String?, // page, blogpost, attachment, etc.
    val status: String?, // current, archived, draft
    val spaceKey: String?,
    val spaceName: String?,
    val version: ConfluenceVersion?,
    val lastModified: String?, // ISO timestamp
    val createdDate: String?, // ISO timestamp from version.when
    val body: ConfluenceBody?,
    val excerpt: String?, // Search result excerpt
    val labels: List<String>?,
    val parentId: String?, // Parent page ID from ancestors
    val ancestors: List<String>?, // List of ancestor IDs
)

@Serializable
data class ConfluenceVersion(
    val number: Int,
    val `when`: String?, // ISO timestamp (backticks because 'when' is a Kotlin keyword)
    val by: ConfluenceUser?,
)

@Serializable
data class ConfluenceUser(
    val accountId: String,
    val displayName: String?,
    val email: String?,
)

@Serializable
data class ConfluencePageRequest(
    val baseUrl: String,
    val authType: String,
    val basicUsername: String? = null,
    val basicPassword: String? = null,
    val bearerToken: String? = null,
    val pageId: String,
)

@Serializable
data class ConfluencePageResponse(
    val id: String,
    val title: String,
    val type: String?, // page, blogpost, attachment, etc.
    val status: String?, // current, archived, draft
    val spaceKey: String?,
    val spaceName: String?,
    val version: ConfluenceVersion?,
    val body: ConfluenceBody?,
    val lastModified: String?,
    val createdDate: String?,
    val labels: List<String>?,
    val parentId: String?,
    val ancestors: List<String>?,
    val attachments: List<ConfluenceAttachment>? = null,
)

@Serializable
data class ConfluenceBody(
    val storage: ConfluenceStorage?,
    val view: ConfluenceStorage?,
)

@Serializable
data class ConfluenceStorage(
    val value: String,
    val representation: String, // "storage", "view", etc.
)

@Serializable
data class ConfluenceAttachment(
    val id: String,
    val title: String,
    val type: String, // "attachment"
    val mediaType: String?, // MIME type (e.g., "image/png")
    val fileSize: Long?,
    val downloadUrl: String?, // Relative URL, typically in _links.download
)

@Serializable
data class ConfluenceAttachmentDownloadRequest(
    val baseUrl: String,
    val authType: String,
    val basicUsername: String? = null,
    val basicPassword: String? = null,
    val bearerToken: String? = null,
    val attachmentDownloadUrl: String, // Full download URL from attachment._links.download
)
