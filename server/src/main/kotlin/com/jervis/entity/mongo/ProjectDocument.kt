package com.jervis.entity.mongo

import com.jervis.domain.language.LanguageEnum
import com.jervis.domain.project.IndexingRules
import com.jervis.domain.project.ProjectOverrides
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * MongoDB document representing a project.
 * Project represents a path within client's mono-repository.
 * Git settings are stored at client level.
 */
@Document(collection = "projects")
data class ProjectDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    @Indexed
    val clientId: ObjectId,
    @Indexed(unique = true)
    val name: String,
    val projectPath: String? = null, // Path within client's mono-repository
    val description: String? = null,
    val shortDescription: String? = null,
    val fullDescription: String? = null,
    val documentationUrls: List<String> = emptyList(),
    val languages: List<String> = emptyList(), // Programming languages used in the project
    val communicationLanguageEnum: LanguageEnum = LanguageEnum.getDefault(), // Communication language for this project
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
