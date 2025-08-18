package com.jervis.entity.mongo

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * MongoDB document representing a project.
 * Extended to support Client/Project configuration per spec while keeping legacy fields for backward compatibility.
 */
@Document(collection = "projects")
@CompoundIndexes(
    CompoundIndex(name = "client_slug_idx", def = "{ 'clientId': 1, 'slug': 1 }", unique = true)
)
data class ProjectDocument(
    // --- Identifiers ---
    @Id
    val id: ObjectId = ObjectId.get(),

    // Link to Client
    @Indexed
    val clientId: ObjectId? = null,

    // Human name and slug
    val name: String,
    @Indexed
    val slug: String = "", // [a-z0-9-]+

    // Description
    val description: String? = null,

    // --- Legacy field to keep desktop UI and indexer working ---
    val path: String = "", // legacy single path

    // New multi-paths for indexer/agent
    val paths: List<String> = emptyList(),

    // Repo configuration
    val repo: Repo = Repo(primaryUrl = ""),

    // Project languages
    val languages: List<String> = emptyList(),

    // Per-project overrides (nullable fields)
    val overrides: ProjectOverrides = ProjectOverrides(),

    // Inspiration-only flag and default branch
    val inspirationOnly: Boolean = false,
    val isDefaultBranch: String = "main",

    // Indexing rules
    val indexingRules: IndexingRules = IndexingRules(),

    // Whether the project is currently active (legacy "default" semantics)
    val isActive: Boolean = false,

    // Audit
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

// --- Supporting data classes ---

data class Repo(
    val primaryUrl: String,
    val extraUrls: List<String> = emptyList(),
    val credentialsRef: String? = null,
)

data class IndexingRules(
    val includeGlobs: List<String> = listOf("**/*.kt", "**/*.java", "**/*.md"),
    val excludeGlobs: List<String> = listOf("**/build/**", "**/.git/**", "**/*.min.*"),
    val maxFileSizeMB: Int = 5,
)

data class ProjectOverrides(
    val codingGuidelines: Guidelines? = null,
    val reviewPolicy: ReviewPolicy? = null,
    val formatting: Formatting? = null,
    val secretsPolicy: SecretsPolicy? = null,
    val anonymization: Anonymization? = null,
    val inspirationPolicy: InspirationPolicy? = null,
    val tools: ClientTools? = null,
)
