package com.jervis.dto

import com.jervis.common.Constants
import com.jervis.domain.language.Language
import kotlinx.serialization.Serializable

@Serializable
data class ProjectDto(
    val id: String = Constants.GLOBAL_ID_STRING,
    val clientId: String?,
    val name: String,
    val description: String? = null,
    val shortDescription: String? = null,
    val fullDescription: String? = null,
    val documentationUrls: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val communicationLanguage: Language = Language.getDefault(),
    val primaryUrl: String? = null,
    val extraUrls: List<String> = emptyList(),
    val credentialsRef: String? = null,
    val defaultBranch: String = "main",
    val overrides: ProjectOverridesDto = ProjectOverridesDto(),
    val inspirationOnly: Boolean = false,
    val indexingRules: IndexingRulesDto = IndexingRulesDto(),
    val dependsOnProjects: List<String> = emptyList(),
    val isDisabled: Boolean = false,
    val isActive: Boolean = false,
)
