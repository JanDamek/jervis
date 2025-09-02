package com.jervis.entity.mongo

import com.jervis.domain.project.IndexingRules
import com.jervis.domain.project.ProjectOverrides
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * MongoDB document representing a project.
 * Extended to support Client/Project configuration per specification while keeping legacy fields for backward compatibility.
 */
@Document(collection = "projects")
data class ProjectDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    @Indexed
    val clientId: ObjectId? = null,
    val name: String,
    val description: String? = null,
    val path: String = "",
    val languages: List<String> = emptyList(),
    // Repository/Git settings (optional - project doesn't have to be in Git)
    val primaryUrl: String? = null,
    val extraUrls: List<String> = emptyList(),
    val credentialsRef: String? = null,
    val defaultBranch: String = "main",
    // Per-project overrides (nullable fields).
    val overrides: ProjectOverrides = ProjectOverrides(),
    // Inspiration-only flag.
    val inspirationOnly: Boolean = false,
    // Indexing rules.
    val indexingRules: IndexingRules = IndexingRules(),
    // Dependencies on other projects (by id).
    val dependsOnProjects: List<ObjectId> = emptyList(),
    // Whether the project is disabled globally.
    val isDisabled: Boolean = false,
    // Whether the project is currently active (legacy "default" semantics).
    val isActive: Boolean = false,
    // Audit timestamps.
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

// --- Supporting data classes ---
