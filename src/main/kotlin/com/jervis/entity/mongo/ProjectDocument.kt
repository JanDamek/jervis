package com.jervis.entity.mongo

import com.jervis.domain.project.IndexingRules
import com.jervis.domain.project.ProjectOverrides
import com.jervis.domain.project.Repo
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
    @Indexed
    val slug: String = "", // [a-z0-9-]+
    val description: String? = null,
    val paths: List<String> = emptyList(),
    val repo: Repo = Repo(primaryUrl = ""),
    val languages: List<String> = emptyList(),
    // Per-project overrides (nullable fields).
    val overrides: ProjectOverrides = ProjectOverrides(),
    // Inspiration-only flag and default branch.
    val inspirationOnly: Boolean = false,
    val isDefaultBranch: String = "main",
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
