package com.jervis.entity.mongo

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "clients")
data class ClientDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    @Indexed(unique = true)
    val name: String,
    @Indexed(unique = true)
    val slug: String, // [a-z0-9-]+
    val description: String? = null,
    val defaultCodingGuidelines: Guidelines = Guidelines(),
    val defaultReviewPolicy: ReviewPolicy = ReviewPolicy(),
    val defaultFormatting: Formatting = Formatting(),
    val defaultSecretsPolicy: SecretsPolicy = SecretsPolicy(),
    val defaultAnonymization: Anonymization = Anonymization(),
    val defaultInspirationPolicy: InspirationPolicy = InspirationPolicy(),
    val tools: ClientTools = ClientTools(),
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

// --- Config objects (kept in same file for simplicity/minimal change) ---

data class Guidelines(
    val codeStyleDocUrl: String? = null,
    val commitMessageConvention: String = "conventional-commits",
    val branchingModel: String = "git-flow",
    val testCoverageTarget: Int = 80,
)

data class ReviewPolicy(
    val requireCodeOwner: Boolean = true,
    val minApprovals: Int = 1,
    val reviewersHints: List<String> = emptyList(),
)

data class Formatting(
    val formatter: String = "ktlint",
    val version: String? = null,
    val lineWidth: Int = 120,
    val tabWidth: Int = 2,
    val rules: Map<String, String> = emptyMap(),
)

data class SecretsPolicy(
    val bannedPatterns: List<String> = listOf(
        "AKIA[0-9A-Z]{16}",
        "xoxb-[0-9a-zA-Z-]+",
        "(?i)api[_-]?key\\s*[:=]\\s*[A-Za-z0-9-_]{20,}"
    ),
    val cloudUploadAllowed: Boolean = false,
    val allowPII: Boolean = false,
)

data class Anonymization(
    val enabled: Boolean = false,
    val rules: List<String> = listOf(
        "(?i)Acme(\\s+Corp)? -> [CLIENT]",
        "([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+) -> [EMAIL]",
    ),
)

// Inspiration Policy controls how and whether cross-client inspiration is used
// Mirrors the spec fields and defaults

data class InspirationPolicy(
    val allowCrossClientInspiration: Boolean = true,
    val allowedClientSlugs: List<String> = emptyList(),
    val disallowedClientSlugs: List<String> = emptyList(),
    val enforceFullAnonymization: Boolean = true,
    val maxSnippetsPerForeignClient: Int = 5,
)

data class ClientTools(
    val git: GitConn? = null,
    val jira: JiraConn? = null,
    val slack: SlackConn? = null,
    val teams: TeamsConn? = null,
    val email: EmailConn? = null,
)

data class GitConn(
    val provider: String? = null, // github/gitlab/bitbucket
    val baseUrl: String? = null,
    val authType: String? = null, // pat/ssh/oauth
    val credentialsRef: String? = null,
)

data class JiraConn(
    val baseUrl: String? = null,
    val tenant: String? = null,
    val scopes: List<String>? = null,
    val credentialsRef: String? = null,
)

data class SlackConn(
    val workspace: String? = null,
    val scopes: List<String>? = null,
    val credentialsRef: String? = null,
)

data class TeamsConn(
    val tenant: String? = null,
    val scopes: List<String>? = null,
    val credentialsRef: String? = null,
)

data class EmailConn(
    val protocol: String? = null, // imap/graph
    val server: String? = null,
    val username: String? = null,
    val credentialsRef: String? = null,
)
