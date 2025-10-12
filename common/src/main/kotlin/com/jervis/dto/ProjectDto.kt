package com.jervis.dto

import com.jervis.domain.language.Language
import com.jervis.domain.project.IndexingRules
import com.jervis.domain.project.ProjectOverrides
import kotlinx.serialization.Serializable

@Serializable
data class ProjectDto(
    val id: String,
    val clientId: String,
    val name: String,
    val description: String? = null,
    val shortDescription: String? = null,
    val fullDescription: String? = null,
    val path: String = "",
    val meetingPath: String? = null,
    val audioPath: String? = null,
    val documentationPath: String? = null,
    val documentationUrls: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val communicationLanguage: Language = Language.getDefault(),
    val primaryUrl: String? = null,
    val extraUrls: List<String> = emptyList(),
    val credentialsRef: String? = null,
    val defaultBranch: String = "main",
    val overrides: ProjectOverrides = ProjectOverrides(),
    val inspirationOnly: Boolean = false,
    val indexingRules: IndexingRules = IndexingRules(),
    val dependsOnProjects: List<String> = emptyList(),
    val isDisabled: Boolean = false,
    val isActive: Boolean = false,
    val createdAt: String,
    val updatedAt: String,
)
