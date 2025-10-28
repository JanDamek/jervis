package com.jervis.entity

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
    val description: String? = null,
    val shortDescription: String? = null,
    val fullDescription: String? = null,
    val projectPath: String? = null,
    val documentationUrls: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val communicationLanguageEnum: LanguageEnum = LanguageEnum.getDefault(),
    val dependsOnProjects: List<ObjectId> = emptyList(),
    val inspirationOnly: Boolean = false,
    val indexingRules: IndexingRules = IndexingRules(),
    val isDisabled: Boolean = false,
    val isActive: Boolean = false,
    val overrides: ProjectOverrides? = null,
    // Last Git sync timestamp.
    val lastGitSyncAt: Instant? = null,
    // Audit timestamps.
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)
